package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

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
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** 📮 Reject answers without waiting for the email; a failed rejection email goes to the Director's bell. No database. */
@WebMvcTest(ManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class RejectionEmailTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;
    @MockitoBean private NotificationService notificationService;

    private Visitor pending() {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setFullName("Dela Cruz, Juan");
        v.setName("Dela Cruz, Juan");
        v.setEmail("juan@example.com");
        v.setStatus("PENDING");
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void theRejectionIsSavedAndTheEmailIsSentWithoutAnErrorOnThePage() throws Exception {
        Visitor v = pending();

        mvc.perform(post("/admin/reject-visitor").with(csrf()).param("id", "7").param("presetReason", "Expired ID presented"))
           .andExpect(redirectedUrl("/admin"))
           .andExpect(flash().attribute("success", "⚠️ Visitor rejected. The rejection email to juan@example.com is on its way."));

        assertThat(v.getStatus()).isEqualTo("REJECTED");
        verify(emailService).sendRejectionEmail(eq("juan@example.com"), eq("Juan"), eq("Expired ID presented"), any());
        verify(notificationService, never()).rejectionEmailFailed(any());
    }

    @Test
    void aFailedRejectionEmailIsReportedToTheBellAndTheRejectionStands() throws Exception {
        Visitor v = pending();
        doThrow(new EmailService.MailDeliveryException("SMTP down"))
            .when(emailService).sendRejectionEmail(anyString(), anyString(), anyString(), any());

        mvc.perform(post("/admin/reject-visitor").with(csrf()).param("id", "7").param("presetReason", "Expired ID presented"))
           .andExpect(redirectedUrl("/admin"));

        assertThat(v.getStatus()).isEqualTo("REJECTED");
        verify(notificationService).rejectionEmailFailed(v);
    }
}
