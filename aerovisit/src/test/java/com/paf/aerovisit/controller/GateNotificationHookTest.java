package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.NotificationService;

/** Gate denials, approval emails that fail, and slots filling up reach the bell. No database. */
@WebMvcTest(GuardOperationController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class GateNotificationHookTest {

    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate DATE = LocalDate.now().plusDays(3);

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private NotificationService notificationService;

    private Visitor visitor(String status, LocalDate date, int groupSize) {
        Visitor v = new Visitor();
        v.setId(5L);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setStatus(status);
        v.setVisitDate(date);
        v.setTimeWindow(WINDOW);
        v.setGroupSize(groupSize);
        when(repo.findById(5L)).thenReturn(Optional.of(v));
        return v;
    }

    private void slot(int capacity) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(DATE);
        s.setTimeWindow(WINDOW);
        s.setMaxCapacity(capacity);
        when(bookingRepo.findBySlotDateAndTimeWindow(DATE, WINDOW)).thenReturn(List.of(s));
    }

    @Test
    void aGateDenialIsReportedWithItsReason() throws Exception {
        Visitor v = visitor("APPROVED", LocalDate.now(), 1);
        mvc.perform(post("/admin/api/deny-entry").with(csrf()).param("visitorId", "5").param("reason", "ID does not match lead booker"))
            .andExpect(jsonPath("$.status").value("success"));
        verify(notificationService).gateDenied(v, "ID does not match lead booker");
    }

    @Test
    void aRefusedDenialIsNotReported() throws Exception {
        visitor("PENDING", LocalDate.now(), 1);
        mvc.perform(post("/admin/api/deny-entry").with(csrf()).param("visitorId", "5").param("reason", "Security concern"));
        verify(notificationService, never()).gateDenied(any(), anyString());
    }

    @Test
    void anApprovalReportsTheSeatsLeftInItsSlot() throws Exception {
        slot(10);
        Visitor v = visitor("PENDING", DATE, 6);
        Visitor other = new Visitor();
        other.setId(6L);
        other.setStatus("APPROVED");
        other.setTimeWindow(WINDOW);
        other.setGroupSize(1);
        when(repo.findByVisitDate(DATE)).thenReturn(List.of(v, other));

        mvc.perform(post("/admin/approve/5").with(csrf()))
            .andExpect(flash().attribute("success", "Visitor approved. The approval email with the QR code is on its way."));
        verify(notificationService).slotFilling(DATE, WINDOW, 3, 10); // 6 + 1 of 10 taken
        verify(notificationService, never()).approvalEmailFailed(any());
    }

    @Test
    void anApprovalWhoseEmailFailsIsReported() throws Exception {
        slot(10);
        Visitor v = visitor("PENDING", DATE, 1);
        when(repo.findByVisitDate(DATE)).thenReturn(List.of(v));
        doThrow(new EmailService.MailDeliveryException("SMTP down"))
            .when(emailService).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any(), any());

        mvc.perform(post("/admin/approve/5").with(csrf()));
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        verify(notificationService).approvalEmailFailed(v);
    }

    @Test
    void anApprovalRefusedForCapacityRaisesNothing() throws Exception {
        slot(2);
        Visitor v = visitor("PENDING", DATE, 3);
        when(repo.findByVisitDate(DATE)).thenReturn(List.of(v));
        mvc.perform(post("/admin/approve/5").with(csrf()))
            .andExpect(flash().attribute("error", "Approval failed: Not enough seats remaining in this time slot."));
        verify(notificationService, never()).slotFilling(any(), anyString(), anyInt(), anyInt());
        verify(notificationService, never()).approvalEmailFailed(any());
    }

    @Test
    void aForceApprovalWhoseEmailFailsIsReportedToo() throws Exception {
        slot(2);
        Visitor v = visitor("PENDING", DATE, 3);
        when(repo.findByVisitDate(DATE)).thenReturn(List.of(v));
        doThrow(new EmailService.MailDeliveryException("SMTP down"))
            .when(emailService).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any(), any());

        mvc.perform(post("/admin/approve-override/5").with(csrf()));
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        verify(notificationService).slotFilling(eq(DATE), eq(WINDOW), eq(-1), eq(2));
        verify(notificationService).approvalEmailFailed(v);
    }
}
