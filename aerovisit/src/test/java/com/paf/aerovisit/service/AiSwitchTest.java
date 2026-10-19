package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.repository.PortalSettingsRepository;

import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsResponse;
import software.amazon.awssdk.services.autoscaling.model.Instance;
import software.amazon.awssdk.services.autoscaling.model.UpdateAutoScalingGroupRequest;

/** The AI features switch (saved choice, waiting for the AI service after switching on) and the AWS server control. No AWS. */
class AiSwitchTest {

    // A switch whose AI service "answers" when told to
    static class FakeSwitch extends AiSwitch {
        boolean answers;
        @Override boolean probe() { return answers; }
    }

    private static FakeSwitch withSaved(PortalSettings saved) {
        FakeSwitch s = new FakeSwitch();
        s.settingsRepo = mock(PortalSettingsRepository.class);
        when(s.settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.ofNullable(saved));
        return s;
    }

    // --- the switch ---

    @Test
    void theAiIsOnUntilSomeoneSwitchesItOff() {
        assertThat(withSaved(null).enabled()).isTrue();
        assertThat(withSaved(new PortalSettings()).state()).isEqualTo(AiSwitch.State.ON); // a row from before the switch
        AiSwitch noDatabase = new AiSwitch();
        assertThat(noDatabase.enabled()).isTrue();
    }

    @Test
    void switchingOffIsSavedAndTakesEffectAtOnce() {
        FakeSwitch s = withSaved(null);
        s.set(false);

        assertThat(s.enabled()).isFalse();
        assertThat(s.state()).isEqualTo(AiSwitch.State.OFF);
        ArgumentCaptor<PortalSettings> saved = ArgumentCaptor.forClass(PortalSettings.class);
        verify(s.settingsRepo).save(saved.capture());
        assertThat(saved.getValue().isAiEnabled()).isFalse();
    }

    @Test
    void aSavedOffSurvivesARestart() {
        PortalSettings saved = new PortalSettings();
        saved.setAiEnabled(false);
        assertThat(withSaved(saved).state()).isEqualTo(AiSwitch.State.OFF);
    }

    @Test
    void afterSwitchingOnTheFeaturesWaitUntilTheAiServiceAnswers() {
        FakeSwitch s = withSaved(null);
        s.set(false);
        s.set(true);
        assertThat(s.state()).isEqualTo(AiSwitch.State.STARTING);
        assertThat(s.enabled()).isFalse(); // nobody waits on time-outs while the server boots

        s.probeWhileStarting();             // still booting
        assertThat(s.state()).isEqualTo(AiSwitch.State.STARTING);

        s.answers = true;
        s.probeWhileStarting();
        assertThat(s.state()).isEqualTo(AiSwitch.State.ON);
        assertThat(s.enabled()).isTrue();
    }

    @Test
    void theProbeNeverRunsWhileOff() {
        FakeSwitch s = withSaved(null);
        s.set(false);
        s.answers = true;
        s.probeWhileStarting();
        assertThat(s.state()).isEqualTo(AiSwitch.State.OFF);
    }

    @Test
    void aRealProbeOfANonexistentAddressFailsQuickly() {
        AiSwitch s = new AiSwitch();
        s.apiBaseUrl = "http://127.0.0.1:9"; // nothing listens on the discard port
        long start = System.nanoTime();
        assertThat(s.probe()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(AiSwitch.PROBE_TIMEOUT.plusSeconds(2));
    }

    // --- the AWS server control ---

    private static AutoScalingGroup asg(String name, int desired, String... lifecycleStates) {
        List<Instance> instances = java.util.Arrays.stream(lifecycleStates)
                .map(state -> Instance.builder().lifecycleState(state).build()).toList();
        return AutoScalingGroup.builder().autoScalingGroupName(name).desiredCapacity(desired).instances(instances).build();
    }

    private static DescribeAutoScalingGroupsResponse found(AutoScalingGroup... groups) {
        return DescribeAutoScalingGroupsResponse.builder().autoScalingGroups(groups).build();
    }

    @Test
    void theAiGroupIsFoundByItsElasticBeanstalkTagAndRemembered() {
        AutoScalingClient aws = mock(AutoScalingClient.class);
        when(aws.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class))).thenReturn(found(asg("awseb-ai-group", 1, "InService")));
        AiServerControl control = new AiServerControl(aws, "", "Aerovisit-ai-prodv2-env");

        assertThat(control.stop()).isNull();

        ArgumentCaptor<DescribeAutoScalingGroupsRequest> asked = ArgumentCaptor.forClass(DescribeAutoScalingGroupsRequest.class);
        verify(aws).describeAutoScalingGroups(asked.capture());
        assertThat(asked.getValue().filters().get(0).name()).isEqualTo("tag:elasticbeanstalk:environment-name");
        assertThat(asked.getValue().filters().get(0).values()).containsExactly("Aerovisit-ai-prodv2-env");
        ArgumentCaptor<UpdateAutoScalingGroupRequest> sent = ArgumentCaptor.forClass(UpdateAutoScalingGroupRequest.class);
        verify(aws).updateAutoScalingGroup(sent.capture());
        assertThat(sent.getValue().autoScalingGroupName()).isEqualTo("awseb-ai-group");
        assertThat(List.of(sent.getValue().minSize(), sent.getValue().maxSize(), sent.getValue().desiredCapacity())).containsExactly(0, 0, 0);

        control.start(); // the remembered group is looked up by name, not searched again
        ArgumentCaptor<DescribeAutoScalingGroupsRequest> second = ArgumentCaptor.forClass(DescribeAutoScalingGroupsRequest.class);
        verify(aws, org.mockito.Mockito.times(2)).describeAutoScalingGroups(second.capture());
        assertThat(second.getAllValues().get(1).autoScalingGroupNames()).containsExactly("awseb-ai-group");
    }

    @Test
    void aRecreatedAiEnvironmentIsFoundAgain() {
        AutoScalingClient aws = mock(AutoScalingClient.class);
        when(aws.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class)))
                .thenReturn(found(asg("old-group", 1, "InService")))   // first search
                .thenReturn(found())                                    // the remembered group is gone
                .thenReturn(found(asg("new-group", 1, "InService")));  // searched again by the tag
        AiServerControl control = new AiServerControl(aws, "", "Aerovisit-ai-prodv2-env");

        assertThat(control.state()).isEqualTo(AiServerControl.ServerState.RUNNING);
        assertThat(control.stop()).isNull();
        ArgumentCaptor<UpdateAutoScalingGroupRequest> sent = ArgumentCaptor.forClass(UpdateAutoScalingGroupRequest.class);
        verify(aws).updateAutoScalingGroup(sent.capture());
        assertThat(sent.getValue().autoScalingGroupName()).isEqualTo("new-group");
    }

    @Test
    void aGivenGroupNameIsUsedAsIsAndStartSetsOneServer() {
        AutoScalingClient aws = mock(AutoScalingClient.class);
        when(aws.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class))).thenReturn(found(asg("ai-group", 0)));
        AiServerControl control = new AiServerControl(aws, "ai-group", "Aerovisit-ai-prodv2-env");

        assertThat(control.start()).isNull();
        ArgumentCaptor<UpdateAutoScalingGroupRequest> sent = ArgumentCaptor.forClass(UpdateAutoScalingGroupRequest.class);
        verify(aws).updateAutoScalingGroup(sent.capture());
        assertThat(sent.getValue().autoScalingGroupName()).isEqualTo("ai-group");
        assertThat(List.of(sent.getValue().minSize(), sent.getValue().maxSize(), sent.getValue().desiredCapacity())).containsExactly(1, 1, 1);
    }

    @Test
    void noAiGroupFoundIsReportedNotThrown() {
        AutoScalingClient aws = mock(AutoScalingClient.class);
        when(aws.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class))).thenReturn(found());
        AiServerControl control = new AiServerControl(aws, "", "Aerovisit-ai-prodv2-env");
        assertThat(control.stop()).contains("was not found");
        assertThat(control.state()).isEqualTo(AiServerControl.ServerState.UNKNOWN);
    }

    @Test
    void aRefusedChangeIsReportedNotThrown() {
        AutoScalingClient aws = mock(AutoScalingClient.class);
        when(aws.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class))).thenReturn(found(asg("ai-group", 1, "InService")));
        when(aws.updateAutoScalingGroup(any(UpdateAutoScalingGroupRequest.class))).thenThrow(new IllegalStateException("AccessDenied"));
        assertThat(new AiServerControl(aws, "", "Aerovisit-ai-prodv2-env").stop()).startsWith("AWS refused the change");
    }

    @Test
    void theServerStateComesFromTheGroup() {
        AutoScalingClient aws = mock(AutoScalingClient.class);
        AiServerControl control = new AiServerControl(aws, "ai-group", null);
        when(aws.describeAutoScalingGroups(any(DescribeAutoScalingGroupsRequest.class)))
                .thenReturn(found(asg("ai-group", 1, "InService")), found(asg("ai-group", 1, "Pending")),
                            found(asg("ai-group", 0, "Terminating")), found(asg("ai-group", 0)));

        assertThat(control.state()).isEqualTo(AiServerControl.ServerState.RUNNING);
        assertThat(control.state()).isEqualTo(AiServerControl.ServerState.STARTING);
        assertThat(control.state()).isEqualTo(AiServerControl.ServerState.STOPPING);
        assertThat(control.state()).isEqualTo(AiServerControl.ServerState.STOPPED);
    }

    @Test
    void onALaptopWithoutS3ItIsNotLinked() {
        AiServerControl control = new AiServerControl("", "Aerovisit-ai-prodv2-env", "", "ap-southeast-2");
        assertThat(control.linked()).isFalse();
        assertThat(control.state()).isEqualTo(AiServerControl.ServerState.NOT_LINKED);
        assertThat(control.stop()).contains("not linked");
    }

    @Test
    void onAwsItLinksByItselfWithNoGroupNameGiven() {
        AiServerControl control = new AiServerControl("", "Aerovisit-ai-prodv2-env", "aerovisit-uploads-test", "ap-southeast-2");
        assertThat(control.linked()).isTrue(); // builds a client only; no call is made until the switch is used
    }
}
