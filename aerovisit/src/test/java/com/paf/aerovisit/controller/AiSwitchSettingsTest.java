package com.paf.aerovisit.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.StaffUser;
import com.paf.aerovisit.repository.StaffUserRepository;
import com.paf.aerovisit.service.AiServerControl;
import com.paf.aerovisit.service.AiSwitch;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.OtpService.SendOutcome;
import com.paf.aerovisit.service.OtpService.SendResult;
import com.paf.aerovisit.service.OtpService.VerifyOutcome;

/** Settings > AI Features: the Director switches the AI on/off with the emailed security code, which also starts/stops its server. */
@WebMvcTest(StaffManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "PAFMuseumOfficial@gmail.com", roles = "DIRECTOR")
class AiSwitchSettingsTest {

    private static final String INBOX = "PAFMuseumOfficial@gmail.com";

    @Autowired private MockMvc mvc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private StaffManagementController controller;

    @MockitoBean private StaffUserRepository staffRepo;
    @MockitoBean private OtpService otpService;
    @MockitoBean private AiSwitch aiSwitch;
    @MockitoBean private AiServerControl aiServer;

    @BeforeEach
    void setUp() {
        ((AtomicReference<?>) ReflectionTestUtils.getField(controller, "pending")).set(null);
        StaffUser director = new StaffUser(INBOX, encoder.encode("director-old-1"), StaffUser.DIRECTOR);
        when(staffRepo.findByUsernameIgnoreCase(anyString())).thenReturn(Optional.of(director));
        when(otpService.sendStaffCode(anyString(), anyString(), anyString())).thenReturn(new SendResult(SendOutcome.SENT, 60));
        when(otpService.verifyStaff(INBOX, "123456")).thenReturn(VerifyOutcome.VERIFIED);
        when(aiSwitch.wanted()).thenReturn(true);
        when(aiSwitch.state()).thenReturn(AiSwitch.State.ON);
        when(aiServer.linked()).thenReturn(true);
    }

    private void requestAndConfirm(String target) throws Exception {
        mvc.perform(post("/admin/api/staff-accounts/request").with(csrf()).param("target", target))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value(containsString("Nothing has been changed yet")));
        mvc.perform(post("/admin/api/staff-accounts/confirm").with(csrf()).param("code", "123456"))
            .andExpect(status().isOk());
    }

    @Test
    void turningOffAsksForTheCodeFirstAndChangesNothingUntilThen() throws Exception {
        mvc.perform(post("/admin/api/staff-accounts/request").with(csrf()).param("target", "ai-off"))
            .andExpect(status().isOk());

        verify(otpService).sendStaffCode(eq(INBOX), anyString(), eq("turn OFF the AI features and stop the AI server"));
        verify(aiSwitch, never()).set(org.mockito.ArgumentMatchers.anyBoolean());
        verify(aiServer, never()).stop();
    }

    @Test
    void confirmingOffStopsUsingTheAiThenStopsItsServer() throws Exception {
        when(aiServer.stop()).thenReturn(null);
        requestAndConfirm("ai-off");

        InOrder order = inOrder(aiSwitch, aiServer);
        order.verify(aiSwitch).set(false);
        order.verify(aiServer).stop();
        verify(otpService).consumeStaff(INBOX);
    }

    @Test
    void confirmingOnStartsTheServerThenWaitsForIt() throws Exception {
        when(aiSwitch.wanted()).thenReturn(false);
        when(aiServer.start()).thenReturn(null);
        when(aiSwitch.state()).thenReturn(AiSwitch.State.STARTING);

        mvc.perform(post("/admin/api/staff-accounts/request").with(csrf()).param("target", "ai-on")).andExpect(status().isOk());
        mvc.perform(post("/admin/api/staff-accounts/confirm").with(csrf()).param("code", "123456"))
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.aiState").value("STARTING"))
            .andExpect(jsonPath("$.message").value(containsString("10 to 20 minutes")));

        InOrder order = inOrder(aiServer, aiSwitch);
        order.verify(aiServer).start();
        order.verify(aiSwitch).set(true);
    }

    @Test
    void aWrongCodeChangesNothing() throws Exception {
        when(otpService.verifyStaff(INBOX, "000000")).thenReturn(VerifyOutcome.WRONG_CODE);
        mvc.perform(post("/admin/api/staff-accounts/request").with(csrf()).param("target", "ai-off")).andExpect(status().isOk());
        mvc.perform(post("/admin/api/staff-accounts/confirm").with(csrf()).param("code", "000000"))
            .andExpect(status().isBadRequest());

        verify(aiSwitch, never()).set(org.mockito.ArgumentMatchers.anyBoolean());
        verify(aiServer, never()).stop();
    }

    @Test
    void ifAwsRefusesTheAppIsStillOffAndTheMessageSaysWhatToDo() throws Exception {
        when(aiServer.stop()).thenReturn("AWS refused the change (AccessDeniedException)");
        mvc.perform(post("/admin/api/staff-accounts/request").with(csrf()).param("target", "ai-off")).andExpect(status().isOk());
        mvc.perform(post("/admin/api/staff-accounts/confirm").with(csrf()).param("code", "123456"))
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.message").value(containsString("could not be stopped")))
            .andExpect(jsonPath("$.message").value(containsString("Put it to sleep in AWS")));
        verify(aiSwitch).set(false);
    }

    @Test
    void whenTheServerIsNotLinkedTheMessageSaysToStopItInAws() throws Exception {
        when(aiServer.linked()).thenReturn(false);
        mvc.perform(post("/admin/api/staff-accounts/request").with(csrf()).param("target", "ai-off")).andExpect(status().isOk());
        mvc.perform(post("/admin/api/staff-accounts/confirm").with(csrf()).param("code", "123456"))
            .andExpect(jsonPath("$.message").value(containsString("not linked to the AI server")));
        verify(aiServer, never()).stop();
    }

    @Test
    void switchingToTheCurrentStateIsRefusedWithoutSendingACode() throws Exception {
        mvc.perform(post("/admin/api/staff-accounts/request").with(csrf()).param("target", "ai-on"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("The AI features are already on."));
        verify(otpService, never()).sendStaffCode(anyString(), anyString(), anyString());
    }

    @Test
    void theStatusShowsTheSwitchAndTheServer() throws Exception {
        when(aiServer.state()).thenReturn(AiServerControl.ServerState.RUNNING);
        mvc.perform(get("/admin/api/staff-accounts/ai"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.available").value(true))
            .andExpect(jsonPath("$.state").value("ON"))
            .andExpect(jsonPath("$.server").value("RUNNING"));
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardsCanNeitherSeeNorFlipTheSwitch() throws Exception {
        mvc.perform(get("/admin/api/staff-accounts/ai")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/staff-accounts/request").with(csrf()).param("target", "ai-off")).andExpect(status().isForbidden());
        verify(otpService, never()).sendStaffCode(anyString(), anyString(), anyString());
    }
}
