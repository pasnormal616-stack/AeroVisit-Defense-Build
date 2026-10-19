package com.paf.aerovisit.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.Filter;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;

/**
 * ☁️ Starts and stops the AI service's server in AWS, so switching the AI features off also stops its billing.
 * It resizes the AI environment's Auto Scaling group to 0 servers (off) or 1 (on), exactly like doing it by hand in
 * EC2 > Auto Scaling Groups. The group is found by itself from the tag Elastic Beanstalk puts on it
 * (elasticbeanstalk:environment-name = the AI environment's name), so a recreated AI environment is found again.
 * Credentials come from the server's IAM role, which needs permission for that environment's group only.
 * Linked only on AWS (photos stored in S3) or when a group name is given; on a laptop or in tests it is not linked and
 * the switch still works inside the app.
 */
@Service
public class AiServerControl {

    public enum ServerState { RUNNING, STARTING, STOPPING, STOPPED, NOT_LINKED, UNKNOWN }

    static final String EB_ENVIRONMENT_TAG = "elasticbeanstalk:environment-name";

    private final AutoScalingClient client; // null when not linked
    private final String fixedGroupName;    // AEROVISIT_AI_ASG_NAME, blank = find it by the environment tag
    private final String environmentName;   // AEROVISIT_AI_ENV_NAME
    private volatile String foundGroupName; // the group the tag search found last time

    @Autowired
    public AiServerControl(@Value("${aerovisit.ai.asg-name:}") String groupName,
                           @Value("${aerovisit.ai.environment-name:Aerovisit-ai-prodv2-env}") String environmentName,
                           @Value("${aerovisit.s3.bucket:}") String s3Bucket,
                           @Value("${aerovisit.s3.region:ap-southeast-2}") String region) {
        this(isBlank(groupName) && isBlank(s3Bucket) ? null : AutoScalingClient.builder().region(Region.of(region)).build(),
             groupName, environmentName);
    }

    // Tests pass a fake client
    AiServerControl(AutoScalingClient client, String groupName, String environmentName) {
        this.client = client;
        this.fixedGroupName = isBlank(groupName) ? null : groupName.trim();
        this.environmentName = isBlank(environmentName) ? null : environmentName.trim();
        if (client != null) {
            System.out.println("☁️ [AI server] Linked to " + (fixedGroupName != null ? "Auto Scaling group " + fixedGroupName
                    : "the Auto Scaling group of environment " + this.environmentName));
        }
    }

    public boolean linked() {
        return client != null;
    }

    /** Stops the AI server (0 servers). Null when AWS accepted it, else what went wrong. */
    public String stop() {
        return resize(0);
    }

    /** Starts the AI server (1 server). Null when AWS accepted it, else what went wrong. */
    public String start() {
        return resize(1);
    }

    private String resize(int servers) {
        if (client == null) return "this website is not linked to the AI server in AWS";
        try {
            AutoScalingGroup group = group();
            if (group == null) return "the AI server's Auto Scaling group was not found (environment " + environmentName + ")";
            client.updateAutoScalingGroup(UpdateAutoScalingGroupRequest.builder().autoScalingGroupName(group.autoScalingGroupName())
                    .minSize(servers).maxSize(servers).desiredCapacity(servers).build());
            System.out.println("☁️ [AI server] " + group.autoScalingGroupName() + " set to " + servers + " server(s)");
            return null;
        } catch (Exception e) {
            System.err.println("☁️ [AI server] Resize to " + servers + " failed: " + e.getMessage());
            return "AWS refused the change (" + e.getClass().getSimpleName() + ")";
        }
    }

    public ServerState state() {
        if (client == null) return ServerState.NOT_LINKED;
        try {
            AutoScalingGroup group = group();
            if (group == null) return ServerState.UNKNOWN;
            int servers = group.instances().size();
            long inService = group.instances().stream().filter(i -> "InService".equals(i.lifecycleStateAsString())).count();
            if (group.desiredCapacity() == 0) return servers == 0 ? ServerState.STOPPED : ServerState.STOPPING;
            return inService > 0 ? ServerState.RUNNING : ServerState.STARTING;
        } catch (Exception e) {
            System.err.println("☁️ [AI server] Status check failed: " + e.getMessage());
            return ServerState.UNKNOWN;
        }
    }

    // The AI's Auto Scaling group: by its given name, or found by the environment tag. A remembered group that no
    // longer exists (the AI environment was recreated) is forgotten and searched for again.
    AutoScalingGroup group() {
        String name = fixedGroupName != null ? fixedGroupName : foundGroupName;
        if (name != null) {
            AutoScalingGroup group = client.describeAutoScalingGroups(DescribeAutoScalingGroupsRequest.builder()
                    .autoScalingGroupNames(name).build()).autoScalingGroups().stream().findFirst().orElse(null);
            if (group != null || fixedGroupName != null) return group;
            foundGroupName = null;
        }
        if (environmentName == null) return null;
        AutoScalingGroup group = client.describeAutoScalingGroups(DescribeAutoScalingGroupsRequest.builder()
                .filters(Filter.builder().name("tag:" + EB_ENVIRONMENT_TAG).values(environmentName).build()).build())
                .autoScalingGroups().stream().findFirst().orElse(null);
        if (group != null) foundGroupName = group.autoScalingGroupName();
        return group;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
