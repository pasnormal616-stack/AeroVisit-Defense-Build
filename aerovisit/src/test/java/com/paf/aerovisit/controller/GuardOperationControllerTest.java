package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.paf.aerovisit.model.StaffSchedule;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;

/** Gate QR scanning rules. No database: the repository is mocked. */
@WebMvcTest(GuardOperationController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "GUARD")
class GuardOperationControllerTest {

    private static final String TOKEN = "3f1c2d9e-7b1a-4c55-9f0e-2a6b8d4c1e77";

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BookingSlotRepository bookingRepo;

    private Visitor booking(String status, LocalDate visitDate) {
        Visitor v = new Visitor();
        v.setId(5L);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setStatus(status);
        v.setVisitDate(visitDate);
        v.setSecureToken(TOKEN);
        when(repo.findBySecureToken(TOKEN)).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void unknownTokenIsRejectedWithoutLoadingEveryVisitor() throws Exception {
        when(repo.findBySecureToken(anyString())).thenReturn(Optional.empty());

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", "not-a-real-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("SECURITY ALERT: Fake or unassigned QR Code."));
        verify(repo, never()).findAll();
    }

    @Test
    void oldForgeableIdAndNameFormatIsRejected() throws Exception {
        when(repo.findBySecureToken(anyString())).thenReturn(Optional.empty());

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", "ID:5 | Name:Juan Dela Cruz"))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("SECURITY ALERT: Fake or unassigned QR Code."));
        verify(repo, never()).save(any());
    }

    @Test
    void pendingBookingCannotEnterEvenWithValidToken() throws Exception {
        booking("PENDING", LocalDate.now());

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("ACCESS DENIED: Booking is not approved (status: PENDING)."));
        verify(repo, never()).save(any());
    }

    @Test
    void approvedBookingOnAnotherDayCannotEnter() throws Exception {
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        booking("APPROVED", tomorrow);

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("ACCESS DENIED: Scheduled for " + tomorrow + "."));
        verify(repo, never()).save(any());
    }

    @Test
    void rejectedBookingCannotEnter() throws Exception {
        booking("REJECTED", LocalDate.now());

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("ACCESS DENIED: Visitor rejected."));
    }

    // --- Verify, then confirm: the scan only reads, Accept (/admin/api/confirm-check-in) checks in ---

    @Test
    void scanOfValidPassOnlyVerifiesAndChangesNothing() throws Exception {
        Visitor v = booking("APPROVED", LocalDate.now());

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("ready"))
            .andExpect(jsonPath("$.visitorId").value("5"))
            .andExpect(jsonPath("$.masterBooking").value("false"))
            .andExpect(jsonPath("$.message").value("🔎 VERIFY: Juan Dela Cruz"));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getCheckInTime()).isNull();
        assertThat(v.getCheckedInBy()).isNull();
        verify(repo, never()).save(any());
        verify(emailService, never()).sendCheckInGuidelines(anyString(), anyString(), any());
    }

    @Test
    void acceptChecksInRecordsGuardAndTimeAndSendsGuidelines() throws Exception {
        Visitor v = booking("APPROVED", LocalDate.now());
        when(repo.findById(5L)).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.status").value("success"))
            .andExpect(jsonPath("$.message").value("🟢 CHECKED-IN: Juan Dela Cruz"));

        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(v.getCheckInTime()).isNotNull();
        assertThat(v.getCheckedInBy()).isNotBlank();
        verify(repo).save(v);
        verify(emailService, timeout(2000)).sendCheckInGuidelines("juan@example.com", "Juan Dela Cruz", null);
    }

    @Test
    void acceptRechecksTheBookingServerSide() throws Exception {
        Visitor v = booking("PENDING", LocalDate.now());
        when(repo.findById(5L)).thenReturn(Optional.of(v));
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("ACCESS DENIED: Booking is not approved (status: PENDING)."));

        v.setStatus("APPROVED");
        v.setVisitDate(LocalDate.now().plusDays(1));
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.message").value("ACCESS DENIED: Scheduled for " + LocalDate.now().plusDays(1) + "."));

        v.setStatus("DENIED_ENTRY");
        v.setVisitDate(LocalDate.now());
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.message").value("ACCESS DENIED: Booking is not approved (status: DENIED_ENTRY)."));

        when(repo.findById(6L)).thenReturn(Optional.empty());
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "6"))
            .andExpect(jsonPath("$.message").value("Visitor ID not found in database."));
        verify(repo, never()).save(any());
    }

    @Test
    void acceptTwiceDoesNotCheckInAgain() throws Exception {
        Visitor v = booking("CHECKED-IN", LocalDate.now());
        when(repo.findById(5L)).thenReturn(Optional.of(v));
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("Visitor is already CHECKED-IN."));
        verify(repo, never()).save(any());
    }

    @Test
    void groupScanReturnsLeadHeadcountAndManifest() throws Exception {
        Visitor v = booking("APPROVED", LocalDate.now());
        v.setFullName("Dela Cruz, Juan P.");
        v.setGroupSize(3);
        v.setCompanionNames("Santos, Maria L.\nReyes, Pedro");

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("ready"))
            .andExpect(jsonPath("$.leadName").value("Dela Cruz, Juan P."))
            .andExpect(jsonPath("$.headcount").value("3"))
            .andExpect(jsonPath("$.companions").value("Santos, Maria L.\nReyes, Pedro"));
        verify(repo, never()).save(any());
    }

    @Test
    void soloScanHasEmptyManifestAndDeniedScanStillIdentifiesTheLead() throws Exception {
        booking("APPROVED", LocalDate.now());
        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.leadName").value("Juan Dela Cruz"))
            .andExpect(jsonPath("$.headcount").value("1"))
            .andExpect(jsonPath("$.companions").value(""));

        booking("PENDING", LocalDate.now());
        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.leadName").value("Juan Dela Cruz"))
            .andExpect(jsonPath("$.visitorId").doesNotExist());
    }

    @Test
    void checkInEmailNamesTheTourGuideOnDuty() throws Exception {
        Visitor v = booking("APPROVED", LocalDate.now());
        when(repo.findById(5L)).thenReturn(Optional.of(v));
        StaffSchedule guide = new StaffSchedule();
        guide.setStaffName("Lt. Santos");
        guide.setRole("Tour Guide");
        guide.setDutyDate(LocalDate.now());
        guide.setShiftTime("Full Day (0800H-1700H)");
        when(staffRepo.findTourGuidesOnDuty(LocalDate.now())).thenReturn(List.of(guide));

        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.status").value("success"));

        verify(emailService, timeout(2000)).sendCheckInGuidelines("juan@example.com", "Juan Dela Cruz", "Lt. Santos");
    }

    @Test
    void masterBookingScanShowsBookedHeadcountAndChangesNothing() throws Exception {
        Visitor v = booking("APPROVED", LocalDate.now());
        v.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        v.setGroupSize(50);

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("ready"))
            .andExpect(jsonPath("$.masterBooking").value("true"))
            .andExpect(jsonPath("$.headcount").value("50"))
            .andExpect(jsonPath("$.message").value("🚌 MASTER BOOKING: Juan Dela Cruz"));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        verify(repo, never()).save(any());
    }

    @Test
    void masterBookingAcceptRecordsActualHeadcount() throws Exception {
        Visitor v = booking("APPROVED", LocalDate.now());
        v.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        v.setGroupSize(50);
        when(repo.findById(5L)).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5").param("headcount", "47"))
            .andExpect(jsonPath("$.status").value("success"))
            .andExpect(jsonPath("$.message").value("🟢 CHECKED-IN: Juan Dela Cruz (47 of 50 arrived)"));

        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(v.getGroupSize()).isEqualTo(47);
        assertThat(v.getBookedGroupSize()).isEqualTo(50);
    }

    @Test
    void masterBookingAcceptNeedsAHeadcountWithinTheApprovedSize() throws Exception {
        Visitor v = booking("APPROVED", LocalDate.now());
        v.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        v.setGroupSize(50);
        when(repo.findById(5L)).thenReturn(Optional.of(v));

        for (String headcount : new String[] { "60", "0" }) {
            mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5").param("headcount", headcount))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("Headcount must be between 1 and 50 (the approved group size)."));
        }
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.status").value("error"));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        verify(repo, never()).save(any());
    }

    @Test
    void manualCheckInEmailAlsoNamesTheTourGuideOnDuty() throws Exception {
        Visitor v = booking("APPROVED", LocalDate.now());
        when(repo.findByReferenceId("PAF-A7X92K")).thenReturn(Optional.of(v));
        StaffSchedule guide = new StaffSchedule();
        guide.setStaffName("Lt. Santos");
        guide.setRole("Tour Guide");
        guide.setDutyDate(LocalDate.now());
        guide.setShiftTime("Full Day (0800H-1700H)");
        when(staffRepo.findTourGuidesOnDuty(LocalDate.now())).thenReturn(List.of(guide));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-A7X92K"))
            .andExpect(status().is3xxRedirection());

        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        verify(emailService).sendCheckInGuidelines("juan@example.com", "Juan Dela Cruz", "Lt. Santos");
    }

    // --- Least privilege: admitting a booking nobody approved is the Director's override, never a Guard's ---

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"PENDING", "VERIFIED", "PENDING_MANUAL_REVIEW"})
    void aGuardCannotManuallyCheckInABookingThatWasNeverApproved(String status) throws Exception {
        Visitor v = booking(status, LocalDate.now());
        when(repo.findByReferenceId("PAF-A7X92K")).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-A7X92K"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                .attribute("error", "ACCESS DENIED: Booking is not approved (status: " + status + ")."));
        assertThat(v.getStatus()).isEqualTo(status);
        verify(repo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void theDirectorKeepsTheManualCheckInOverrideForAPendingBooking() throws Exception {
        Visitor v = booking("PENDING", LocalDate.now());
        when(repo.findByReferenceId("PAF-A7X92K")).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-A7X92K"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                .attribute("success", "🟢 Checked-In via Manual Override: Juan Dela Cruz"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
    }

    @Test
    void aGuardCanStillCheckOutSomeoneInsideFromTheGateDashboard() throws Exception {
        Visitor v = booking("CHECKED-IN", LocalDate.now());
        v.setCheckInTime(java.time.LocalDateTime.now().minusHours(1));
        when(repo.findByReferenceId("PAF-A7X92K")).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-A7X92K"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                .attribute("success", "👋 CHECKED-OUT via Manual Override: Juan Dela Cruz"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
    }

    @Test
    void aGuardCanDenyEntryToTodaysApprovedArrivalWithoutABan() throws Exception {
        Visitor v = booking("APPROVED", LocalDate.now());
        v.setQrCodeUrl("QR_5.png");
        when(repo.findById(5L)).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/deny-entry").with(csrf()).param("visitorId", "5").param("reason", "ID does not match lead booker"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                .attribute("success", "⛔ ENTRY DENIED: Juan Dela Cruz. Check-in voided and logged."));
        assertThat(v.getStatus()).isEqualTo("DENIED_ENTRY");
        assertThat(v.getQrCodeUrl()).isNull();
    }

    @Test
    void secondScanChecksOutAndSendsFeedbackLinkWithSecureToken() throws Exception {
        Visitor v = booking("CHECKED-IN", LocalDate.now());
        v.setCheckInTime(java.time.LocalDateTime.now().minusMinutes(45));

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("success"));

        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
        verify(emailService, timeout(2000)).sendCheckOutFeedback("juan@example.com", "Juan Dela Cruz", TOKEN);
    }

    // --- Check-out cooldown (accidental double-scan) ---

    @Test
    void rescanWithinTwoMinutesOfCheckInDoesNotCheckOut() throws Exception {
        Visitor v = booking("CHECKED-IN", LocalDate.now());
        // Just checked in, and 10 seconds short of the cooldown (margin so a slow test run cannot cross it)
        for (java.time.LocalDateTime checkIn : List.of(java.time.LocalDateTime.now(),
                java.time.LocalDateTime.now().minusMinutes(2).plusSeconds(10))) {
            v.setCheckInTime(checkIn);
            mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.message").value("⏳ Cooldown: Visitor checked in less than 2 minutes ago. Please wait before checking out."));
        }

        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(v.getCheckOutTime()).isNull();
        verify(repo, never()).save(any());
        verify(emailService, never()).sendCheckOutFeedback(anyString(), anyString(), anyString());
    }

    @Test
    void rescanExactlyTwoMinutesAfterCheckInChecksOut() throws Exception {
        Visitor v = booking("CHECKED-IN", LocalDate.now());
        v.setCheckInTime(java.time.LocalDateTime.now().minus(GuardOperationController.CHECKOUT_COOLDOWN));

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("success"))
            .andExpect(jsonPath("$.message").value("👋 CHECKED-OUT: Juan Dela Cruz"));

        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
        assertThat(v.getCheckOutTime()).isNotNull();
        verify(repo).save(v);
        verify(emailService, timeout(2000)).sendCheckOutFeedback("juan@example.com", "Juan Dela Cruz", TOKEN);
    }

    @Test
    void checkInWithoutARecordedTimeCanStillCheckOut() throws Exception {
        // Rows checked in before checkInTime was recorded must not be stuck inside forever
        Visitor v = booking("CHECKED-IN", LocalDate.now());
        v.setCheckInTime(null);

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("success"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
    }

    // --- Just-in-time approval capacity check ---

    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";

    private Visitor waitlisted(Long id, String status, LocalDate date, int groupSize) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setStatus(status);
        v.setVisitDate(date);
        v.setTimeWindow(WINDOW);
        v.setGroupSize(groupSize);
        return v;
    }

    private void slotWithCapacity(LocalDate date, int max) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(WINDOW);
        s.setMaxCapacity(max);
        when(bookingRepo.findBySlotDateAndTimeWindow(date, WINDOW)).thenReturn(List.of(s));
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void approvalIsBlockedWhenApprovedSeatsWouldExceedCapacity() throws Exception {
        LocalDate date = LocalDate.now().plusDays(3);
        Visitor pending = waitlisted(20L, "PENDING", date, 3);
        Visitor approved = waitlisted(21L, "APPROVED", date, 8);
        slotWithCapacity(date, 10);
        when(repo.findById(20L)).thenReturn(Optional.of(pending));
        when(repo.findByVisitDate(date)).thenReturn(List.of(pending, approved));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/approve/20").with(csrf()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                .attribute("error", "Approval failed: Not enough seats remaining in this time slot."));

        assertThat(pending.getStatus()).isEqualTo("PENDING");
        verify(repo, never()).save(any());
        verify(emailService, never()).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void otherPendingRequestsDoNotBlockApproval() throws Exception {
        LocalDate date = LocalDate.now().plusDays(3);
        // An ID no real visitor has: approval writes uploads/QR_<id>.png, which is deleted afterwards
        Long testId = 990001L;
        Visitor pending = waitlisted(testId, "PENDING", date, 3);
        Visitor approved = waitlisted(21L, "APPROVED", date, 7);
        Visitor otherPending = waitlisted(22L, "PENDING", date, 50);  // waitlisted: holds no seats
        Visitor cancelled = waitlisted(23L, "CANCELLED_BY_VISITOR", date, 50);
        slotWithCapacity(date, 10);
        when(repo.findById(testId)).thenReturn(Optional.of(pending));
        when(repo.findByVisitDate(date)).thenReturn(List.of(pending, approved, otherPending, cancelled));

        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/approve/" + testId).with(csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin"));
        } finally {
            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("uploads", "QR_" + testId + ".png"));
        }

        assertThat(pending.getStatus()).isEqualTo("APPROVED");
    }

    // --- Director capacity override (Force Approve) ---

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void forceApproveSkipsTheCapacityCheck() throws Exception {
        LocalDate date = LocalDate.now().plusDays(3);
        Long testId = 990002L; // an ID no real visitor has: the QR file is deleted afterwards
        Visitor pending = waitlisted(testId, "PENDING", date, 3);
        Visitor approved = waitlisted(21L, "APPROVED", date, 10);
        slotWithCapacity(date, 10); // already full
        when(repo.findById(testId)).thenReturn(Optional.of(pending));
        when(repo.findByVisitDate(date)).thenReturn(List.of(pending, approved));

        try {
            mvc.perform(post("/admin/approve-override/" + testId).with(csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/admin"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash().attributeExists("success"));
        } finally {
            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("uploads", "QR_" + testId + ".png"));
        }

        assertThat(pending.getStatus()).isEqualTo("APPROVED");
        assertThat(pending.getSecureToken()).isNotBlank();
        assertThat(pending.getQrCodeUrl()).isEqualTo("QR_" + testId + ".png");
        verify(repo).save(pending);
        verify(emailService).sendApprovalEmailWithQR("v" + testId + "@example.com", "Visitor " + testId, date.toString(), "QR_" + testId + ".png", null);
    }

    @Test
    void guardCannotForceApprove() throws Exception {
        mvc.perform(post("/admin/approve-override/20").with(csrf())).andExpect(status().isForbidden());
        verify(repo, never()).findById(any());
        verify(repo, never()).save(any());
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void forceApproveRefusesBookingsThatAreNotPending() throws Exception {
        for (String status : List.of("DENIED_ENTRY", "REJECTED", "CANCELLED", "APPROVED")) {
            Visitor v = waitlisted(20L, status, LocalDate.now().plusDays(3), 2);
            when(repo.findById(20L)).thenReturn(Optional.of(v));

            mvc.perform(post("/admin/approve-override/20").with(csrf()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                    .attribute("error", "Force Approve only applies to a pending request (status: " + status + ")."));
            assertThat(v.getStatus()).isEqualTo(status);
        }
        verify(repo, never()).save(any());
        verify(emailService, never()).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void forceApproveSendsMasterBookingsToTheStandardApproval() throws Exception {
        Visitor master = waitlisted(20L, "PENDING", LocalDate.now().plusDays(3), 80);
        master.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        when(repo.findById(20L)).thenReturn(Optional.of(master));

        mvc.perform(post("/admin/approve-override/20").with(csrf()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                .attribute("error", "Master Bookings are exempt from the capacity check. Use the standard Approve button."));
        assertThat(master.getStatus()).isEqualTo("PENDING");
        verify(repo, never()).save(any());
    }

    @Test
    void checkedOutVisitorCannotReenter() throws Exception {
        booking("CHECKED-OUT", LocalDate.now());

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("Visitor has already CHECKED-OUT today."));
        verify(repo, never()).save(any());
    }

    // --- Gate denial (void a check-in) ---

    private Visitor checkedInToday() {
        Visitor v = booking("CHECKED-IN", LocalDate.now());
        v.setCheckedInBy("Sgt. Ramos");
        v.setCheckInTime(java.time.LocalDateTime.now().minusMinutes(2));
        v.setQrCodeUrl("QR_5.png");
        when(repo.findById(5L)).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void denyEntryVoidsTodaysCheckInAndKeepsTheAuditTrail() throws Exception {
        Visitor v = checkedInToday();

        mvc.perform(post("/admin/api/deny-entry").with(csrf()).param("visitorId", "5").param("reason", "Group does not match manifest"))
            .andExpect(jsonPath("$.status").value("success"))
            .andExpect(jsonPath("$.message").value("⛔ ENTRY DENIED: Juan Dela Cruz. Denial logged."));

        assertThat(v.getStatus()).isEqualTo("DENIED_ENTRY");
        assertThat(v.getEntryDenialReason()).isEqualTo("Group does not match manifest");
        assertThat(v.getEntryDeniedAt()).isNotNull();
        assertThat(v.getEntryDeniedBy()).isNotBlank();
        assertThat(v.getQrCodeUrl()).isNull();
        assertThat(v.getCheckedInBy()).isEqualTo("Sgt. Ramos"); // the original check-in stays on record
        assertThat(v.getCheckInTime()).isNotNull();
        verify(repo).save(v);
    }

    @Test
    void dashboardDenyEntryRecordsReasonAndNotes() throws Exception {
        Visitor v = checkedInToday();

        mvc.perform(post("/admin/deny-entry").with(csrf()).param("visitorId", "5")
                .param("reason", "Unregistered person in group").param("notes", " 2 of 4 guests had no matching ID "))
            .andExpect(status().is3xxRedirection())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                .attribute("success", "⛔ ENTRY DENIED: Juan Dela Cruz. Check-in voided and logged."));

        assertThat(v.getStatus()).isEqualTo("DENIED_ENTRY");
        assertThat(v.getEntryDenialReason()).isEqualTo("Unregistered person in group | Notes: 2 of 4 guests had no matching ID");
    }

    @Test
    void denyEntryNeedsAReason() throws Exception {
        Visitor v = checkedInToday();
        mvc.perform(post("/admin/api/deny-entry").with(csrf()).param("visitorId", "5").param("reason", " "))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("Select a reason for denying entry."));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        verify(repo, never()).save(any());
    }

    @Test
    void scannedPassCanBeDeniedBeforeCheckIn() throws Exception {
        Visitor v = booking("APPROVED", LocalDate.now());
        when(repo.findById(5L)).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/api/deny-entry").with(csrf()).param("visitorId", "5").param("reason", "ID does not match lead booker"))
            .andExpect(jsonPath("$.status").value("success"))
            .andExpect(jsonPath("$.message").value("⛔ ENTRY DENIED: Juan Dela Cruz. Denial logged."));

        assertThat(v.getStatus()).isEqualTo("DENIED_ENTRY");
        assertThat(v.getEntryDenialReason()).isEqualTo("ID does not match lead booker");
        assertThat(v.getCheckInTime()).isNull(); // never admitted
        assertThat(v.getQrCodeUrl()).isNull();
    }

    @Test
    void onlyAnApprovedOrCheckedInVisitorCanBeDenied() throws Exception {
        Visitor v = booking("PENDING", LocalDate.now());
        when(repo.findById(5L)).thenReturn(Optional.of(v));
        mvc.perform(post("/admin/api/deny-entry").with(csrf()).param("visitorId", "5").param("reason", "Security concern"))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("Only an approved or checked-in visitor can be denied entry (status: PENDING)."));

        v.setStatus("CHECKED-OUT");
        mvc.perform(post("/admin/api/deny-entry").with(csrf()).param("visitorId", "5").param("reason", "Security concern"))
            .andExpect(jsonPath("$.status").value("error"));
        verify(repo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "DIRECTOR") // rejecting is Director-only
    void rejectCannotOverwriteAGateDenial() throws Exception {
        Visitor v = booking("DENIED_ENTRY", LocalDate.now());
        when(repo.findById(5L)).thenReturn(Optional.of(v));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/reject/5").with(csrf()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                .attribute("error", "This visitor was denied entry at the gate; that record cannot be changed."));
        assertThat(v.getStatus()).isEqualTo("DENIED_ENTRY");
        verify(repo, never()).save(any());
    }

    @Test
    void checkInFromAnotherDayCannotBeDenied() throws Exception {
        Visitor v = booking("CHECKED-IN", LocalDate.now().minusDays(1));
        when(repo.findById(5L)).thenReturn(Optional.of(v));
        mvc.perform(post("/admin/api/deny-entry").with(csrf()).param("visitorId", "5").param("reason", "Security concern"))
            .andExpect(jsonPath("$.message").value("Entry can only be denied on the day of the visit."));
        verify(repo, never()).save(any());
    }

    @Test
    void deniedVisitorQrCanNeverBeUsedAgain() throws Exception {
        Visitor v = booking("DENIED_ENTRY", LocalDate.now());
        v.setEntryDenialReason("Group does not match manifest");

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value("ACCESS DENIED: Entry was already denied at the gate (Group does not match manifest)."))
            .andExpect(jsonPath("$.visitorId").doesNotExist());
        assertThat(v.getStatus()).isEqualTo("DENIED_ENTRY");
        verify(repo, never()).save(any());
    }

    @Test
    void manualCheckInCannotReadmitADeniedVisitor() throws Exception {
        Visitor v = booking("DENIED_ENTRY", LocalDate.now());
        v.setEntryDenialReason("Group does not match manifest");
        when(repo.findByReferenceId("PAF-A7X92K")).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-A7X92K"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                .attribute("error", "ACCESS DENIED: Entry was already denied at the gate (Group does not match manifest)."));
        assertThat(v.getStatus()).isEqualTo("DENIED_ENTRY");
        verify(repo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "DIRECTOR") // approving is Director-only
    void deniedVisitorCannotBeReApproved() throws Exception {
        Visitor v = booking("DENIED_ENTRY", LocalDate.now());
        when(repo.findById(5L)).thenReturn(Optional.of(v));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/approve/5").with(csrf()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash()
                .attribute("error", "Approval refused: this visitor was denied entry at the gate. The denial is final for this booking."));
        assertThat(v.getStatus()).isEqualTo("DENIED_ENTRY");
        assertThat(v.getQrCodeUrl()).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    @org.springframework.security.test.context.support.WithAnonymousUser
    void anonymousCannotDenyEntry() throws Exception {
        mvc.perform(post("/admin/api/deny-entry").with(csrf()).param("visitorId", "5").param("reason", "Security concern"))
            .andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/deny-entry").with(csrf()).param("visitorId", "5").param("reason", "Security concern"))
            .andExpect(status().is3xxRedirection());
        verify(repo, never()).save(any());
    }

    // --- Scanner emails are sent in the background ---

    @Test
    void checkOutAnswersTheScannerWithoutWaitingForTheEmail() throws Exception {
        Visitor v = booking("CHECKED-IN", LocalDate.now());
        v.setCheckInTime(java.time.LocalDateTime.now().minusMinutes(45));
        java.util.concurrent.CountDownLatch smtp = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> { smtp.await(5, java.util.concurrent.TimeUnit.SECONDS); return null; })
            .when(emailService).sendCheckOutFeedback(anyString(), anyString(), anyString());

        try {
            // The email is still "sending" (blocked on the latch), yet the scan has already answered
            mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
                .andExpect(jsonPath("$.status").value("success"));
            assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
        } finally {
            smtp.countDown();
        }
        verify(emailService, timeout(2000)).sendCheckOutFeedback("juan@example.com", "Juan Dela Cruz", TOKEN);
    }

    @Test
    void failedEmailDoesNotBreakTheScanOrConfirm() throws Exception {
        org.mockito.Mockito.doThrow(new EmailService.MailDeliveryException("Brevo down"))
            .when(emailService).sendCheckInGuidelines(anyString(), anyString(), any());
        org.mockito.Mockito.doThrow(new EmailService.MailDeliveryException("Brevo down"))
            .when(emailService).sendCheckOutFeedback(anyString(), anyString(), anyString());
        Visitor v = booking("APPROVED", LocalDate.now());
        when(repo.findById(5L)).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.status").value("success"))
            .andExpect(jsonPath("$.message").value("🟢 CHECKED-IN: Juan Dela Cruz"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");

        v.setCheckInTime(java.time.LocalDateTime.now().minusMinutes(45));
        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("success"))
            .andExpect(jsonPath("$.message").value("👋 CHECKED-OUT: Juan Dela Cruz"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
    }
}
