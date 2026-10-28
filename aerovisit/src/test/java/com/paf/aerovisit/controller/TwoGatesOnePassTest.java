package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.GateClaim;

/**
 * 🚦 Two gates (or two guards sharing the Guard account) acting on the same pass at the same moment: only the request
 * whose GateClaim succeeds checks the visitor in or out and sends the email; the other one is told it already happened.
 */
@WebMvcTest(GuardOperationController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "GUARD")
class TwoGatesOnePassTest {

    private static final String TOKEN = "3f1c2d9e-7b1a-4c55-9f0e-2a6b8d4c1e77";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private GateClaim gateClaim;

    private Visitor visitor(String status) {
        Visitor v = new Visitor();
        v.setId(5L);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setStatus(status);
        v.setVisitDate(LocalDate.now());
        v.setSecureToken(TOKEN);
        v.setReferenceId("PAF-TWO123");
        when(repo.findById(5L)).thenReturn(Optional.of(v));
        when(repo.findBySecureToken(TOKEN)).thenReturn(Optional.of(v));
        when(repo.findByReferenceId("PAF-TWO123")).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void theFirstAcceptChecksInAndEmailsOnce() throws Exception {
        Visitor v = visitor("APPROVED");
        when(gateClaim.claim(5L, "APPROVED", "CHECKED-IN")).thenReturn(true);

        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.status").value("success"));

        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        verify(repo).save(v);
        verify(emailService, timeout(2000)).sendCheckInGuidelines("juan@example.com", "Juan Dela Cruz", null);
    }

    @Test
    void theSecondGatesAcceptAtTheSameMomentChangesNothingAndSendsNoEmail() throws Exception {
        // Both gates loaded the pass while it was APPROVED; the other gate's UPDATE got there first
        Visitor v = visitor("APPROVED");
        when(gateClaim.claim(5L, "APPROVED", "CHECKED-IN")).thenReturn(false);

        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value(GuardOperationController.ALREADY_CHECKED_IN));

        assertThat(v.getCheckInTime()).isNull();
        assertThat(v.getCheckedInBy()).isNull();
        verify(repo, never()).save(any());
        Thread.sleep(300); // a background email would have started by now
        verify(emailService, never()).sendCheckInGuidelines(anyString(), anyString(), any());
    }

    @Test
    void twoCheckOutScansAtTheSameMomentSendOneFeedbackEmail() throws Exception {
        Visitor v = visitor("CHECKED-IN");
        v.setCheckInTime(LocalDateTime.now().minusHours(2));
        when(gateClaim.claim(5L, "CHECKED-IN", "CHECKED-OUT")).thenReturn(false);

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value(GuardOperationController.ALREADY_CHECKED_OUT));

        assertThat(v.getCheckOutTime()).isNull();
        verify(repo, never()).save(any());
        Thread.sleep(300);
        verify(emailService, never()).sendCheckOutFeedback(anyString(), anyString(), anyString());
    }

    @Test
    void aManualCheckOutThatLosesTheRaceSaysSo() throws Exception {
        Visitor v = visitor("CHECKED-IN");
        v.setCheckInTime(LocalDateTime.now().minusHours(2));
        when(gateClaim.claim(5L, "CHECKED-IN", "CHECKED-OUT")).thenReturn(false);

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-TWO123"))
            .andExpect(flash().attribute("error", GuardOperationController.ALREADY_CHECKED_OUT));
        verify(repo, never()).save(any());
    }

    @Test
    void aManualCheckOutThatWinsChecksOut() throws Exception {
        Visitor v = visitor("CHECKED-IN");
        v.setCheckInTime(LocalDateTime.now().minusHours(2));
        when(gateClaim.claim(5L, "CHECKED-IN", "CHECKED-OUT")).thenReturn(true);

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-TWO123"))
            .andExpect(flash().attribute("success", "👋 CHECKED-OUT via Manual Override: Juan Dela Cruz"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
        verify(repo).save(v);
    }
}
