package com.sequenceiq.cloudbreak.cloud.aws;

import static com.sequenceiq.cloudbreak.util.FreeMarkerTemplateUtils.processTemplateIntoString;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNoneEmpty;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.springframework.stereotype.Service;

import com.sequenceiq.cloudbreak.cloud.aws.view.AwsGroupView;
import com.sequenceiq.cloudbreak.cloud.aws.view.AwsInstanceProfileView;
import com.sequenceiq.cloudbreak.cloud.context.AuthenticatedContext;
import com.sequenceiq.cloudbreak.cloud.exception.CloudConnectorException;
import com.sequenceiq.cloudbreak.cloud.model.CloudStack;
import com.sequenceiq.cloudbreak.cloud.model.Group;
import com.sequenceiq.cloudbreak.cloud.model.InstanceTemplate;

import freemarker.template.Configuration;
import freemarker.template.TemplateException;

@Service("CloudFormationTemplateBuilder")
public class CloudFormationTemplateBuilder {
    @Inject
    private Configuration freemarkerConfiguration;

    public String build(ModelContext context) {
        Map<String, Object> model = new HashMap<>();
        AwsInstanceProfileView awsInstanceProfileView = new AwsInstanceProfileView(context.stack.getParameters());
        List<AwsGroupView> awsGroupViews = new ArrayList<>();
        for (Group group : context.stack.getGroups()) {
            InstanceTemplate instanceTemplate = group.getInstances().get(0).getTemplate();
            Boolean encrypted = instanceTemplate.getParameter("encrypted", Boolean.class);
            encrypted = encrypted == null ? Boolean.FALSE : encrypted;
            awsGroupViews.add(
                    new AwsGroupView(
                            group.getInstances().size(),
                            group.getType().name(),
                            instanceTemplate.getFlavor(),
                            group.getName(),
                            instanceTemplate.getVolumes().size(),
                            encrypted.equals(Boolean.TRUE),
                            instanceTemplate.getVolumeSize(),
                            instanceTemplate.getVolumeType(),
                            getSpotPrice(instanceTemplate),
                            group.getSecurity().getRules()
                    )
            );
        }
        model.put("instanceGroups", awsGroupViews);
        model.put("existingVPC", context.existingVPC);
        model.put("existingIGW", context.existingIGW);
        model.put("existingSubnet", isNoneEmpty(context.existingSubnetCidr));
        model.put("enableInstanceProfile", context.enableInstanceProfile || context.s3RoleAvailable);
        model.put("existingRole", context.s3RoleAvailable);
        model.put("cbSubnet", isBlank(context.existingSubnetCidr) ? context.stack.getNetwork().getSubnet().getCidr() : context.existingSubnetCidr);
        model.put("dedicatedInstances", areDedicatedInstancesRequested(context.stack));
        model.put("availabilitySetNeeded", context.ac.getCloudContext().getLocation().getAvailabilityZone().value() != null);
        model.put("mapPublicIpOnLaunch", context.mapPublicIpOnLaunch);
        if (isNoneEmpty(context.snapshotId)) {
            model.put("snapshotId", context.snapshotId);
        }
        if (context.s3RoleAvailable) {
            model.put("roleName", awsInstanceProfileView.getS3Role());
        }
        try {
            String template = processTemplateIntoString(freemarkerConfiguration.getTemplate(context.templatePath, "UTF-8"), model);
            return template.replaceAll("\\t|\\n| [\\s]+", "");
        } catch (IOException | TemplateException e) {
            throw new CloudConnectorException("Failed to process CloudFormation freemarker template", e);
        }
    }

    public boolean areDedicatedInstancesRequested(CloudStack cloudStack) {
        boolean result = false;
        if (isDedicatedInstancesParamExistAndTrue(cloudStack)) {
            result = true;
        }
        return result;
    }

    private boolean isDedicatedInstancesParamExistAndTrue(CloudStack stack) {
        return stack.getParameters().containsKey("dedicatedInstances")
                && Boolean.valueOf(stack.getParameters().get("dedicatedInstances"));
    }

    private Double getSpotPrice(InstanceTemplate instanceTemplate) {
        try {
            return instanceTemplate.getParameter("spotPrice", Double.class);
        } catch (ClassCastException e) {
            return instanceTemplate.getParameter("spotPrice", Integer.class).doubleValue();
        }
    }

    public static class ModelContext {
        private AuthenticatedContext ac;
        private CloudStack stack;
        private String snapshotId;
        private boolean existingVPC;
        private boolean existingIGW;
        private String existingSubnetCidr;
        private boolean mapPublicIpOnLaunch;
        private String templatePath;
        private boolean enableInstanceProfile;
        private boolean s3RoleAvailable;

        public ModelContext withAuthenticatedContext(AuthenticatedContext ac) {
            this.ac = ac;
            return this;
        }

        public ModelContext withStack(CloudStack stack) {
            this.stack = stack;
            return this;
        }

        public ModelContext withSnapshotId(String snapshotId) {
            this.snapshotId = snapshotId;
            return this;
        }

        public ModelContext withExistingVpc(boolean existingVpc) {
            this.existingVPC = existingVpc;
            return this;
        }

        public ModelContext withExistingIGW(boolean existingIGW) {
            this.existingIGW = existingIGW;
            return this;
        }

        public ModelContext withExistingSubnetCidr(String cidr) {
            this.existingSubnetCidr = cidr;
            return this;
        }

        public ModelContext mapPublicIpOnLaunch(boolean mapPublicIpOnLaunch) {
            this.mapPublicIpOnLaunch = mapPublicIpOnLaunch;
            return this;
        }

        public ModelContext withEnableInstanceProfile(boolean enableInstanceProfile) {
            this.enableInstanceProfile = enableInstanceProfile;
            return this;
        }

        public ModelContext withS3RoleAvailable(boolean s3RoleAvailable) {
            this.s3RoleAvailable = s3RoleAvailable;
            return this;
        }

        public ModelContext withTemplatePath(String templatePath) {
            this.templatePath = templatePath;
            return this;
        }
    }
}