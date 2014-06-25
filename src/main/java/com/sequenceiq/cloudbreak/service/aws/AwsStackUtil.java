package com.sequenceiq.cloudbreak.service.aws;

import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import reactor.core.Reactor;
import reactor.event.Event;

import com.amazonaws.auth.BasicSessionCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cloudformation.AmazonCloudFormationClient;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.sequenceiq.cloudbreak.conf.ReactorConfig;
import com.sequenceiq.cloudbreak.domain.AwsCredential;
import com.sequenceiq.cloudbreak.domain.Stack;
import com.sequenceiq.cloudbreak.domain.Status;
import com.sequenceiq.cloudbreak.domain.WebsocketEndPoint;
import com.sequenceiq.cloudbreak.repository.RetryingStackUpdater;
import com.sequenceiq.cloudbreak.websocket.WebsocketService;
import com.sequenceiq.cloudbreak.websocket.message.StatusMessage;

@Component
public class AwsStackUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(AwsStackUtil.class);

    @Autowired
    private CrossAccountCredentialsProvider credentialsProvider;

    @Autowired
    private RetryingStackUpdater stackUpdater;

    @Autowired
    private WebsocketService websocketService;

    @Autowired
    private Reactor reactor;

    public AmazonCloudFormationClient createCloudFormationClient(Regions regions, AwsCredential credential) {
        BasicSessionCredentials basicSessionCredentials = credentialsProvider
                .retrieveSessionCredentials(CrossAccountCredentialsProvider.DEFAULT_SESSION_CREDENTIALS_DURATION, "provision-ambari", credential);
        AmazonCloudFormationClient amazonCloudFormationClient = new AmazonCloudFormationClient(basicSessionCredentials);
        amazonCloudFormationClient.setRegion(Region.getRegion(regions));
        LOGGER.info("Amazon CloudFormation client successfully created.");
        return amazonCloudFormationClient;
    }

    public AmazonEC2Client createEC2Client(Regions regions, AwsCredential credential) {
        BasicSessionCredentials basicSessionCredentials = credentialsProvider
                .retrieveSessionCredentials(CrossAccountCredentialsProvider.DEFAULT_SESSION_CREDENTIALS_DURATION, "provision-ambari", credential);
        AmazonEC2Client amazonEC2Client = new AmazonEC2Client(basicSessionCredentials);
        amazonEC2Client.setRegion(Region.getRegion(regions));
        LOGGER.info("Amazon EC2 client successfully created.");
        return amazonEC2Client;
    }

    public void createFailed(Stack stack) {
        stack = stackUpdater.updateStackStatus(stack.getId(), Status.CREATE_FAILED);
        websocketService.sendToTopicUser(stack.getUser().getEmail(), WebsocketEndPoint.STACK,
                new StatusMessage(stack.getId(), stack.getName(), Status.CREATE_FAILED.name()));
    }

    public void createSuccess(Stack stack, String ambariIp) {
        stack = stackUpdater.updateAmbariIp(stack.getId(), ambariIp);
        stack = stackUpdater.updateStackStatus(stack.getId(), Status.CREATE_COMPLETED);
        websocketService.sendToTopicUser(stack.getUser().getEmail(), WebsocketEndPoint.STACK,
                new StatusMessage(stack.getId(), stack.getName(), Status.CREATE_COMPLETED.name()));
        LOGGER.info("Publishing {} event [StackId: '{}']", ReactorConfig.AMBARI_STARTED_EVENT, stack.getId());
        reactor.notify(ReactorConfig.AMBARI_STARTED_EVENT, Event.wrap(stack));
    }

    public String encode(String userData) {
        byte[] encoded = Base64.encodeBase64(userData.getBytes());
        return new String(encoded);
    }

    public void sleep(int duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            LOGGER.info("Interrupted exception occured during polling.", e);
            Thread.currentThread().interrupt();
        }
    }

}