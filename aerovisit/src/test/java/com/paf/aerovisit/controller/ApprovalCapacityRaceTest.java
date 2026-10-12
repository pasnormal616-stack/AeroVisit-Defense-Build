package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
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
import com.paf.aerovisit.service.SeatReservationService;

/** Approve goes through SeatReservationService; when a concurrent approval took the last seats, the user gets the "full" error. No database. */
@WebMvcTest(GuardOperationController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class ApprovalCapacityRaceTest {

    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate DATE = LocalDate.now().plusDays(3);

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private SeatReservationService seatReservation;

    // A pending 1-seat request for a 100-seat slot that looks like it has 99 taken (so the first check passes)
    private Visitor pendingWithOneSeatLeft(long id) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(DATE);
        s.setTimeWindow(WINDOW);
        s.setMaxCapacity(100);
        when(bookingRepo.findBySlotDateAndTimeWindow(DATE, WINDOW)).thenReturn(List.of(s));
        Visitor big = new Visitor();
        big.setId(1L);
        big.setStatus("APPROVED");
        big.setVisitDate(DATE);
        big.setTimeWindow(WINDOW);
        big.setGroupSize(99);
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setStatus("PENDING");
        v.setVisitDate(DATE);
        v.setTimeWindow(WINDOW);
        v.setGroupSize(1);
        when(repo.findById(id)).thenReturn(Optional.of(v));
        when(repo.findByVisitDate(DATE)).thenReturn(List.of(big, v));
        return v;
    }

    @Test
    void approvalIsSavedThroughTheLockedCapacityCheck() throws Exception {
        long id = 990501L;
        Visitor v = pendingWithOneSeatLeft(id);
        when(seatReservation.saveIfSeatsRemain(v)).thenReturn(v);
        try {
            mvc.perform(post("/admin/approve/" + id).with(csrf())).andExpect(flash().attribute("success", "Visitor approved and Email sent successfully!"));
        } finally {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + id + ".png"));
        }
        verify(seatReservation).saveIfSeatsRemain(v);
        verify(repo, never()).save(any());
        verify(emailService).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aConcurrentApprovalThatTookTheLastSeatGivesTheCapacityFullError() throws Exception {
        long id = 990502L;
        Visitor v = pendingWithOneSeatLeft(id);
        when(seatReservation.saveIfSeatsRemain(v)).thenThrow(new SeatReservationService.CapacityFullException());
        Path qr = Paths.get("uploads", "QR_" + id + ".png");
        try {
            mvc.perform(post("/admin/approve/" + id).with(csrf()))
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attribute("error", "Approval failed: Not enough seats remaining in this time slot."))
                .andExpect(flash().attributeCount(1));
            assertThat(qr).as("the unused QR file is removed").doesNotExist();
        } finally {
            Files.deleteIfExists(qr);
        }
        verify(repo, never()).save(any());
        verify(emailService, never()).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aLockTimeoutIsReportedAsBusyNotAsAnApproval() throws Exception {
        long id = 990503L;
        Visitor v = pendingWithOneSeatLeft(id);
        when(seatReservation.saveIfSeatsRemain(v)).thenThrow(new CannotAcquireLockException("lock wait timeout"));
        try {
            mvc.perform(post("/admin/approve/" + id).with(csrf()))
                .andExpect(flash().attribute("error", "Approval failed: this time slot is being updated by another approval right now. Please try again."));
        } finally {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + id + ".png"));
        }
        verify(emailService, never()).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void masterBookingsSkipTheSeatLock() throws Exception {
        long id = 990504L;
        Visitor master = pendingWithOneSeatLeft(id);
        master.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        master.setTimeWindow("Master Booking Bypass");
        try {
            mvc.perform(post("/admin/approve/" + id).with(csrf())).andExpect(flash().attributeExists("success"));
        } finally {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + id + ".png"));
        }
        verify(seatReservation, never()).saveIfSeatsRemain(any());
        verify(repo).save(master);
    }
}
