package com.sequenceiq.cloudbreak.service.stack;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;

import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import com.sequenceiq.cloudbreak.conf.ReactorConfig;
import com.sequenceiq.cloudbreak.controller.BadRequestException;
import com.sequenceiq.cloudbreak.controller.NotFoundException;
import com.sequenceiq.cloudbreak.converter.StackConverter;
import com.sequenceiq.cloudbreak.domain.CloudPlatform;
import com.sequenceiq.cloudbreak.domain.InstanceMetaData;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.domain.StackDescription;
import com.sequenceiq.cloudbreak.domain.Status;
import com.sequenceiq.cloudbreak.domain.StatusRequest;
import com.sequenceiq.cloudbreak.domain.Template;
import com.sequenceiq.cloudbreak.domain.User;
import com.sequenceiq.cloudbreak.domain.UserRole;
import com.sequenceiq.cloudbreak.repository.ClusterRepository;
import com.sequenceiq.cloudbreak.repository.RetryingStackUpdater;
import com.sequenceiq.cloudbreak.repository.StackRepository;
import com.sequenceiq.cloudbreak.repository.TemplateRepository;
import com.sequenceiq.cloudbreak.repository.UserRepository;
import com.sequenceiq.cloudbreak.service.account.AccountService;
import com.sequenceiq.cloudbreak.service.stack.connector.CloudPlatformConnector;
import com.sequenceiq.cloudbreak.service.stack.event.ProvisionRequest;
import com.sequenceiq.cloudbreak.service.stack.event.StackDeleteRequest;
import com.sequenceiq.cloudbreak.service.stack.event.StackStatusUpdateRequest;
import com.sequenceiq.cloudbreak.service.stack.event.UpdateInstancesRequest;
import com.sequenceiq.cloudbreak.service.stack.flow.MetadataIncompleteException;

import reactor.core.Reactor;
import reactor.event.Event;

@Service
public class DefaultStackService implements StackService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultStackService.class);

    @Autowired
    private StackConverter stackConverter;

    @Autowired
    private StackRepository stackRepository;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountService accountService;

    @Resource
    private Map<CloudPlatform, CloudPlatformConnector> cloudPlatformConnectors;

    @Autowired
    private RetryingStackUpdater stackUpdater;

    @Autowired
    private Reactor reactor;

    @Autowired
    private ClusterRepository clusterRepository;

    @Override
    public Set<Stack> getAll(User user) {
        Set<Stack> legacyStacks = new HashSet<>();
        Set<Stack> userStacks = user.getStacks();
        LOGGER.debug("User stacks: #{}", userStacks.size());

        if (user.getUserRoles().contains(UserRole.ACCOUNT_ADMIN)) {
            LOGGER.debug("Getting company user stacks for company admin; id: [{}]", user.getId());
            legacyStacks = getCompanyUserStacks(user);
        } else if (user.getUserRoles().contains(UserRole.ACCOUNT_USER)) {
            LOGGER.debug("Getting company wide stacks for company user; id: [{}]", user.getId());
            legacyStacks = getCompanyStacks(user);
        }
        LOGGER.debug("Found #{} legacy stacks for user [{}]", legacyStacks.size(), user.getId());
        userStacks.addAll(legacyStacks);
        return userStacks;
    }

    private Set<Stack> getCompanyStacks(User user) {
        Set<Stack> companyStacks = new HashSet<>();
        User adminWithFilteredData = accountService.accountUserData(user.getAccount().getId(), user.getUserRoles().iterator().next());
        if (adminWithFilteredData != null) {
            companyStacks = adminWithFilteredData.getStacks();
        } else {
            LOGGER.debug("There's no company admin for user: [{}]", user.getId());
        }
        return companyStacks;
    }

    private Set<Stack> getCompanyUserStacks(User user) {
        Set<Stack> companyUserStacks = new HashSet<>();
        Set<User> companyUsers = accountService.accountUsers(user.getAccount().getId());
        companyUsers.remove(user);
        for (User cUser : companyUsers) {
            LOGGER.debug("Adding blueprints of company user: [{}]", cUser.getId());
            companyUserStacks.addAll(cUser.getStacks());
        }
        return companyUserStacks;
    }

    @Override
    public Stack get(User user, Long id) {
        Stack stack = stackRepository.findOne(id);
        if (stack == null) {
            throw new NotFoundException(String.format("Stack '%s' not found", id));
        }
        return stack;
    }

    @Override
    public Stack create(User user, Stack stack) {
        Template template = templateRepository.findOne(stack.getTemplate().getId());
        stack.setUser(user);
        stack.setHash(generateHash(stack));
        stack = stackRepository.save(stack);
        LOGGER.info("Publishing {} event [StackId: '{}']", ReactorConfig.PROVISION_REQUEST_EVENT, stack.getId());
        reactor.notify(ReactorConfig.PROVISION_REQUEST_EVENT, Event.wrap(new ProvisionRequest(template.cloudPlatform(), stack.getId())));
        return stack;
    }

    @Override
    public void delete(User user, Long id) {
        LOGGER.info("Stack delete requested. [StackId: {}]", id);
        Stack stack = stackRepository.findOne(id);
        if (stack == null) {
            throw new NotFoundException(String.format("Stack '%s' not found", id));
        }
        LOGGER.info("Publishing {} event [StackId: '{}']", ReactorConfig.DELETE_REQUEST_EVENT, stack.getId());
        reactor.notify(ReactorConfig.DELETE_REQUEST_EVENT, Event.wrap(new StackDeleteRequest(stack.getTemplate().cloudPlatform(), stack.getId())));
    }

    @Override
    public void updateStatus(User user, Long stackId, StatusRequest status) {
        Stack stack = stackRepository.findOne(stackId);
        Status stackStatus = stack.getStatus();
        if (status.equals(StatusRequest.STARTED)) {
            if (!Status.STOPPED.equals(stackStatus)) {
                throw new BadRequestException(String.format("Cannot update the status of stack '%s' to STARTED, because it isn't in STOPPED state.", stackId));
            }
            stack.setStatus(Status.START_IN_PROGRESS);
            stackRepository.save(stack);
            LOGGER.info("Publishing {} event [StackId: '{}']", ReactorConfig.STACK_STATUS_UPDATE_EVENT, stack.getId());
            reactor.notify(ReactorConfig.STACK_STATUS_UPDATE_EVENT,
                    Event.wrap(new StackStatusUpdateRequest(stack.getUser(), stack.getTemplate().cloudPlatform(), stack.getId(), status)));
        } else {
            Status clusterStatus = clusterRepository.findOneWithLists(stack.getCluster().getId()).getStatus();
            if (Status.STOP_IN_PROGRESS.equals(clusterStatus)) {
                stack.setStatus(Status.STOP_REQUESTED);
                stackRepository.save(stack);
            } else {
                if (!Status.AVAILABLE.equals(stackStatus)) {
                    throw new BadRequestException(
                            String.format("Cannot update the status of stack '%s' to STOPPED, because it isn't in AVAILABLE state.", stackId));
                }
                if (!Status.STOPPED.equals(clusterStatus)) {
                    throw new BadRequestException(
                            String.format("Cannot update the status of stack '%s' to STOPPED, because the cluster is not in STOPPED state.", stackId));
                }
                LOGGER.info("Publishing {} event [StackId: '{}']", ReactorConfig.STACK_STATUS_UPDATE_EVENT, stack.getId());
                reactor.notify(ReactorConfig.STACK_STATUS_UPDATE_EVENT,
                        Event.wrap(new StackStatusUpdateRequest(stack.getUser(), stack.getTemplate().cloudPlatform(), stack.getId(), status)));
            }
        }
    }

    @Override
    public void updateNodeCount(User user, Long stackId, Integer scalingAdjustment) {
        Stack stack = stackRepository.findOne(stackId);
        if (!Status.AVAILABLE.equals(stack.getStatus())) {
            throw new BadRequestException(String.format("Stack '%s' is currently in '%s' state. Node count can only be updated if it's running.", stackId,
                    stack.getStatus()));
        }
        if (0 == scalingAdjustment) {
            throw new BadRequestException(String.format("Requested scaling adjustment on stack '%s' is 0. Nothing to do.", stackId));
        }
        if (0 > scalingAdjustment) {
            if (-1 * scalingAdjustment > stack.getNodeCount()) {
                throw new BadRequestException(String.format("There are %s instances in stack '%s'. Cannot remove %s instances.", stack.getNodeCount(), stackId,
                        -1 * scalingAdjustment));
            }
            int removeableHosts = 0;
            for (InstanceMetaData metadataEntry : stack.getInstanceMetaData()) {
                if (metadataEntry.isRemovable()) {
                    removeableHosts++;
                }
            }
            if (removeableHosts < -1 * scalingAdjustment) {
                throw new BadRequestException(
                        String.format("There are %s removable hosts on stack '%s' but %s were requested. Decomission nodes from the cluster first!",
                                removeableHosts, stackId, scalingAdjustment * -1));
            }
        }
        stackUpdater.updateStackStatus(stack.getId(), Status.UPDATE_IN_PROGRESS);
        LOGGER.info("Publishing {} event [stackId: '{}', scalingAdjustment: '{}']", ReactorConfig.UPDATE_INSTANCES_REQUEST_EVENT, stack.getId(),
                scalingAdjustment);
        reactor.notify(ReactorConfig.UPDATE_INSTANCES_REQUEST_EVENT,
                Event.wrap(new UpdateInstancesRequest(stack.getTemplate().cloudPlatform(), stack.getId(), scalingAdjustment)));
    }

    @Override
    public StackDescription getStackDescription(User user, Stack stack) {
        CloudPlatform cp = stack.getTemplate().cloudPlatform();
        LOGGER.debug("Getting stack description for cloud platform: {} ...", cp);
        StackDescription description = cloudPlatformConnectors.get(cp).describeStackWithResources(user, stack, stack.getCredential());
        LOGGER.debug("Found stack description {}", description.getClass());
        return description;
    }

    @Override
    public Set<InstanceMetaData> getMetaData(String hash) {
        Stack stack = stackRepository.findStackByHash(hash);
        if (stack != null) {
            if (!stack.isMetadataReady()) {
                throw new MetadataIncompleteException("Instance metadata is incomplete.");
            }
            if (!stack.getInstanceMetaData().isEmpty()) {
                return stack.getInstanceMetaData();
            }
        }
        throw new NotFoundException("Metadata not found on stack.");
    }

    private String generateHash(Stack stack) {
        int hashCode = HashCodeBuilder.reflectionHashCode(stack);
        return DigestUtils.md5DigestAsHex(String.valueOf(hashCode).getBytes());
    }

}