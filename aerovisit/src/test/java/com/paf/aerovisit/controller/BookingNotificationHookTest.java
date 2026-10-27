package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Manage Booking tells the Director's bell about online cancellations and changes that need approval again. No database. */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
class BookingNotificationHookTest {

    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private NotificationService notificationService;

    private static MockHttpSession signedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("manageVisitorId", 7L);
        session.setAttribute("manageFormToken", "tok-123");
        return session;
    }

    private Visitor upcoming(String status) {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setStatus(status);
        v.setReferenceId("PAF-A7X92K");
        v.setVisitDate(LocalDate.now().plusDays(5));
        v.setTimeWindow(WINDOW);
        v.setGroupSize(2);
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void anOnlineCancellationIsReported() throws Exception {
        Visitor v = upcoming("APPROVED");
        mvc.perform(post("/manage-booking/cancel").with(csrf()).session(signedIn()).param("formToken", "tok-123"))
            .andExpect(redirectedUrl("/manage-booking"));
        assertThat(v.getStatus()).isEqualTo("CANCELLED_BY_VISITOR");
        verify(notificationService).bookingCancelled(v);
    }

    @Test
    void aNewScheduleIsReportedAsNeedingApprovalAgain() throws Exception {
        Visitor v = upcoming("APPROVED");
        LocalDate newDate = LocalDate.now().plusDays(9);
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(newDate);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(10);
        when(bookingRepo.findAll()).thenReturn(List.of(slot));
        when(repo.findByVisitDate(newDate)).thenReturn(List.of());
        when(repo.findBuyoutDates()).thenReturn(List.of());
        when(repo.findByEmailAndVisitDate("juan@example.com", newDate)).thenReturn(new java.util.ArrayList<>());

        mvc.perform(post("/manage-booking/update").with(csrf()).session(signedIn()).param("formToken", "tok-123")
                .param("slot", newDate + "|" + WINDOW).param("groupSize", "2"))
            .andExpect(redirectedUrl("/manage-booking"));
        assertThat(v.getStatus()).isEqualTo("PENDING");
        verify(notificationService).bookingRescheduled(v);
    }

    @Test
    void aContactNumberChangeIsNotReported() throws Exception {
        upcoming("APPROVED");
        mvc.perform(post("/manage-booking/update").with(csrf()).session(signedIn()).param("formToken", "tok-123")
                .param("slot", "").param("groupSize", "2").param("contactNumber", "0918-111-1111"))
            .andExpect(redirectedUrl("/manage-booking"));
        verify(notificationService, never()).bookingRescheduled(any());
    }
}
