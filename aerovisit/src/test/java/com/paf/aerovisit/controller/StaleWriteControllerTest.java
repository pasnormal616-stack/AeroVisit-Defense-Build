package com.paf.aerovisit.controller;

import com.paf.aerovisit.FixedClock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
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
import com.paf.aerovisit.service.ReceiptStorage;
import com.paf.aerovisit.service.SeatReservationService;
import com.paf.aerovisit.service.VisitorRowGuard;

/**
 * 🔒 Security fix M4 through the real endpoints: a receipt uploaded just after the payment sweep expired the booking is
 * refused (no file stored, the expiry stands), and an approval of a booking the visitor moved to another date meanwhile is
 * refused (the new date stands, no QR email). The database's "current row" is what lockById returns. No database.
 */
@WebMvcTest({PaymentController.class, GuardOperationController.class})
@Import({FixedClock.class, SecurityConfig.class, VisitorRowGuard.class, SeatReservationService.class})
class StaleWriteControllerTest {

    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate DAY = FixedClock.TODAY.plusDays(3);

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private ReceiptStorage receiptStorage;

    @AfterEach
    void removeQr() throws Exception {
        Files.deleteIfExists(Paths.get("uploads", "QR_82.png"));
    }

    private static Visitor booking(long id, String status) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan" + id + "@example.com");
        v.setReferenceId("PAF-" + id);
        v.setStatus(status);
        v.setVisitDate(DAY);
        v.setTimeWindow(MORNING);
        v.setGroupSize(1);
        v.setSecureToken("qr-token-" + id);
        return v;
    }

    @Test
    void aReceiptUploadedAfterTheSweepExpiredTheBookingIsRefusedAndNotStored() throws Exception {
        Visitor read = booking(81, Visitor.STATUS_AWAITING_RECEIPT);
        read.setAdmissionTotalCentavos(15_000L);
        read.setBillingNumber("000081");
        read.setPaymentStatus(Visitor.PAY_AWAITING);
        read.setPaymentDueAt(FixedClock.NOW.plusMinutes(1));
        read.setPaymentToken("pay-token-81");
        when(repo.findByPaymentToken("pay-token-81")).thenReturn(Optional.of(read));
        // the sweep's conditional UPDATE committed between the page's read and the upload
        Visitor current = booking(81, Visitor.STATUS_PAYMENT_EXPIRED);
        current.setAdmissionTotalCentavos(15_000L);
        current.setPaymentStatus(Visitor.PAY_AWAITING);
        when(repo.lockById(81L)).thenReturn(Optional.of(current));

        mvc.perform(multipart("/payment/proof").file(new MockMultipartFile("receipt", "r.jpg", "image/jpeg", com.paf.aerovisit.TestImages.jpeg(3)))
                        .param("token", "pay-token-81").with(csrf()))
           .andExpect(flash().attribute("error", VisitorRowGuard.CHANGED_MESSAGE));

        verify(receiptStorage, never()).saveReceipt(any());
        verify(repo, never()).save(any());
        verify(notificationService, never()).bookingRequest(any());
        assertThat(read.getReceiptImageUrl()).isNull();
    }

    @Test
    void anApprovalOfABookingRescheduledMeanwhileIsRefusedAndTheNewDateStands() throws Exception {
        Visitor read = booking(82, "PENDING");
        when(repo.findById(82L)).thenReturn(Optional.of(read));
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(MORNING);
        slot.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(DAY, MORNING)).thenReturn(List.of(slot));
        when(bookingRepo.lockBySlotDateAndTimeWindow(DAY, MORNING)).thenReturn(List.of(slot));
        // the visitor moved it to another day in Manage Booking after the Director's page was loaded
        Visitor current = booking(82, "PENDING");
        current.setVisitDate(DAY.plusDays(4));
        when(repo.lockById(82L)).thenReturn(Optional.of(current));

        mvc.perform(post("/admin/approve/82").with(csrf()).with(user("director").roles("DIRECTOR")))
           .andExpect(flash().attribute("error", VisitorRowGuard.CHANGED_MESSAGE));

        verify(repo, never()).save(any());
        verify(emailService, never()).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any(), any());
        assertThat(Paths.get("uploads", "QR_82.png")).doesNotExist(); // the QR made for it was thrown away
    }
}
