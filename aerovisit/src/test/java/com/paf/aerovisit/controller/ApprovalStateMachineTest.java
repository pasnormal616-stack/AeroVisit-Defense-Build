package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
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

/** Visitor state machine: only PENDING / PENDING_MANUAL_REVIEW / VERIFIED may become APPROVED ; a paid booking still waiting for its receipt cannot. No database. */
@WebMvcTest(GuardOperationController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class ApprovalStateMachineTest {

    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate DATE = LocalDate.now().plusDays(3);

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BookingSlotRepository bookingRepo;

    private Visitor visitor(long id, String status) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Juan Dela Cruz");
        v.setFullName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setStatus(status);
        v.setVisitDate(DATE);
        v.setTimeWindow(WINDOW);
        v.setGroupSize(1);
        v.setQrCodeUrl("QR_" + id + ".png");
        when(repo.findById(id)).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void laterStatusesCanNeverGoBackToApproved() throws Exception {
        for (String status : new String[] {"CHECKED-IN", "CHECKED-OUT", "REJECTED", "CANCELLED", "CANCELLED_OVERRIDE",
                                           "CANCELLED_BY_VISITOR", "APPROVED", null}) {
            Visitor v = visitor(5L, status);

            mvc.perform(post("/admin/approve/5").with(csrf()))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attribute("error", "Invalid state transition: a booking with status "
                        + (status != null ? status : "UNKNOWN") + " cannot be approved. Only pending requests can be approved."));

            assertThat(v.getStatus()).as(String.valueOf(status)).isEqualTo(status);
            assertThat(v.getQrCodeUrl()).as(String.valueOf(status)).isEqualTo("QR_5.png");
        }
        verify(repo, never()).save(any());
        verify(emailService, never()).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void checkedOutVisitorCannotReEnterThroughApproval() throws Exception {
        // The exploit from the audit: approve a CHECKED-OUT visitor so the same QR passes the gate again
        Visitor v = visitor(5L, "CHECKED-OUT");
        v.setVisitDate(LocalDate.now());

        mvc.perform(post("/admin/approve/5").with(csrf())).andExpect(flash().attributeExists("error"));

        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
        verify(repo, never()).save(any());
    }

    @Test
    void deniedEntryKeepsItsOwnMessage() throws Exception {
        visitor(5L, "DENIED_ENTRY");
        mvc.perform(post("/admin/approve/5").with(csrf()))
            .andExpect(flash().attribute("error", "Approval refused: this visitor was denied entry at the gate. The denial is final for this booking."));
        verify(repo, never()).save(any());
    }

    @Test
    void everyPendingStatusCanStillBeApproved() throws Exception {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DATE);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(10);
        when(bookingRepo.findBySlotDateAndTimeWindow(DATE, WINDOW)).thenReturn(List.of(slot));

        long id = 990101L; // approval writes uploads/QR_<id>.png, deleted afterwards
        for (String status : new String[] {"PENDING", "PENDING_MANUAL_REVIEW", "VERIFIED"}) {
            Visitor v = visitor(id, status);
            when(repo.findByVisitDate(DATE)).thenReturn(List.of(v));
            try {
                mvc.perform(post("/admin/approve/" + id).with(csrf()))
                    .andExpect(redirectedUrl("/admin"))
                    .andExpect(flash().attribute("success", "Visitor approved. The approval email with the QR code is on its way."));
            } finally {
                Files.deleteIfExists(Paths.get("uploads", "QR_" + id + ".png"));
            }
            assertThat(v.getStatus()).as(status).isEqualTo("APPROVED");
        }
    }

    @Test
    void aPaidBookingWaitingForItsReceiptCannotBeApproved() throws Exception {
        Visitor v = visitor(5L, Visitor.STATUS_AWAITING_RECEIPT);
        v.setPaymentStatus(Visitor.PAY_AWAITING);
        mvc.perform(post("/admin/approve/5").with(csrf()))
            .andExpect(flash().attribute("error", GuardOperationController.AWAITING_RECEIPT_APPROVAL));
        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
        verify(repo, never()).save(any());
    }
}
