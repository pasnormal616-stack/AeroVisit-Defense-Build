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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.FaceEncodings;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * Ban integrity (the face ban replaced the old email "Block"): banning revokes every live booking with that face under
 * any email, and approval / check-in refuse a banned face. No database.
 */
@WebMvcTest({ManagementController.class, GuardOperationController.class})
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class BanIntegrityTest {

    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate TODAY = LocalDate.now();
    private static final String JUAN = face(2);
    private static final String ANA = face(7);

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    private static String face(int axis) {
        double[] v = new double[FaceEncodings.DIMENSIONS];
        v[axis] = 1.0;
        return FaceEncodings.format(v);
    }

    private static Visitor v(long id, String email, String face, String status, LocalDate date) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail(email);
        v.setFaceEncoding(face);
        v.setStatus(status);
        v.setVisitDate(date);
        v.setTimeWindow(WINDOW);
        v.setGroupSize(1);
        v.setReferenceId("PAF-REF" + id);
        if ("APPROVED".equals(status)) v.setQrCodeUrl("QR_" + id + ".png");
        return v;
    }

    private void juanIsBanned() {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(1L);
        ban.setFaceEncoding(JUAN);
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));
    }

    // --- Banning revokes every live booking with that face, under any email ---

    @Test
    void banningRevokesPendingAndUpcomingApprovedBookingsWithTheSameFace() throws Exception {
        Visitor banned = v(1, "juan@example.com", JUAN, "PENDING", TODAY.plusDays(4));
        Visitor otherEmail = v(2, "juan.alt@example.com", JUAN, "VERIFIED", TODAY.plusDays(4));   // new email: still caught
        Visitor manualReview = v(3, "j.dc@example.com", JUAN, "PENDING_MANUAL_REVIEW", TODAY.plusDays(4));
        Visitor approvedLater = v(4, "juan@example.com", JUAN, "APPROVED", TODAY.plusDays(2));
        Visitor approvedToday = v(5, "juan@example.com", JUAN, "APPROVED", TODAY);
        Visitor pastNoShow = v(6, "juan@example.com", JUAN, "APPROVED", TODAY.minusDays(10));     // history: kept
        Visitor inside = v(7, "juan@example.com", JUAN, "CHECKED-IN", TODAY);                    // inside the base: kept
        Visitor someoneElse = v(8, "ana@example.com", ANA, "APPROVED", TODAY.plusDays(2));       // different face
        Visitor noFace = v(9, "juan@example.com", null, "APPROVED", TODAY.plusDays(2));          // can't be matched
        when(repo.findById(1L)).thenReturn(Optional.of(banned));
        when(faceBanRepo.findAll()).thenReturn(List.of());
        when(repo.findAll()).thenReturn(new ArrayList<>(List.of(banned, otherEmail, manualReview, approvedLater,
                approvedToday, pastNoShow, inside, someoneElse, noFace)));

        mvc.perform(post("/admin/biometric-ban/1").with(csrf()).param("reason", "Fake ID"))
            .andExpect(redirectedUrl("/admin"))
            .andExpect(flash().attribute("success", "🧬 Visitor banned permanently: Visitor 1. New bookings with this face will be refused. "
                    + "5 open booking(s) with this face were revoked."));

        verify(faceBanRepo).save(any(BlacklistedFace.class));
        for (Visitor revoked : List.of(banned, otherEmail, manualReview, approvedLater, approvedToday)) {
            assertThat(revoked.getStatus()).as("visitor " + revoked.getId()).isEqualTo("REJECTED");
            assertThat(revoked.getQrCodeUrl()).as("visitor " + revoked.getId()).isNull();
            assertThat(revoked.getRejectionReason()).isEqualTo("Access revoked: visitor banned (Fake ID)");
        }
        assertThat(pastNoShow.getStatus()).isEqualTo("APPROVED");
        assertThat(inside.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(someoneElse.getStatus()).isEqualTo("APPROVED");
        assertThat(noFace.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void banningAnAlreadyBannedFaceStillRevokesABookingMadeBeforeTheBan() throws Exception {
        juanIsBanned();
        Visitor v = v(10, "juan@example.com", JUAN, "APPROVED", TODAY.plusDays(1));
        when(repo.findById(10L)).thenReturn(Optional.of(v));
        when(repo.findAll()).thenReturn(new ArrayList<>(List.of(v)));

        mvc.perform(post("/admin/biometric-ban/10").with(csrf()))
            .andExpect(flash().attribute("success", "🧬 Visitor 10 was already banned; the ban now runs permanently. 1 open booking(s) with this face were revoked."));

        assertThat(v.getStatus()).isEqualTo("REJECTED");
    }

    // --- An expired temporary ban is ignored everywhere ---

    private void juansBanEnded() {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(1L);
        ban.setFaceEncoding(JUAN);
        ban.setExpiresAt(TODAY.minusDays(1)); // a 30-day suspension that ended yesterday
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));
    }

    @Test
    void afterATemporaryBanEndsTheVisitorCanBeApprovedAgain() throws Exception {
        juansBanEnded();
        long id = 990302L; // approval writes uploads/QR_<id>.png, deleted afterwards
        Visitor v = v(id, "juan@example.com", JUAN, "PENDING", TODAY.plusDays(3));
        when(repo.findById(id)).thenReturn(Optional.of(v));
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(v.getVisitDate());
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(10);
        when(bookingRepo.findBySlotDateAndTimeWindow(v.getVisitDate(), WINDOW)).thenReturn(List.of(slot));

        try {
            mvc.perform(post("/admin/approve/" + id).with(csrf())).andExpect(flash().attribute("success", "Visitor approved and Email sent successfully!"));
        } finally {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + id + ".png"));
        }
        assertThat(v.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void afterATemporaryBanEndsThePassWorksAtTheScanner() throws Exception {
        juansBanEnded();
        Visitor v = v(41, "juan@example.com", JUAN, "APPROVED", TODAY);
        v.setTimeWindow(null);
        v.setSecureToken("7a1c2d9e-7b1a-4c55-9f0e-2a6b8d4c1e88");
        when(repo.findBySecureToken(v.getSecureToken())).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", v.getSecureToken()))
            .andExpect(jsonPath("$.status").value("ready"));
    }

    @Test
    void aTemporaryBanStillRunningOnItsLastDayBlocksCheckIn() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setFaceEncoding(JUAN);
        ban.setExpiresAt(TODAY); // the last day of the suspension
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));
        Visitor v = v(32, "juan@example.com", JUAN, "PENDING", TODAY);
        when(repo.findByReferenceId("PAF-REF32")).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-REF32"))
            .andExpect(flash().attribute("error", GuardOperationController.BANNED_ENTRY));
    }

    // --- Approval refuses a banned face ---

    @Test
    void aBannedFaceCannotBeApprovedOrForceApproved() throws Exception {
        juanIsBanned();
        Visitor v = v(20, "new.email@example.com", JUAN, "PENDING", TODAY.plusDays(3));
        when(repo.findById(20L)).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/approve/20").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.BANNED_APPROVAL));
        mvc.perform(post("/admin/approve-override/20").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.BANNED_APPROVAL));

        assertThat(v.getStatus()).isEqualTo("PENDING");
        verify(repo, never()).save(any());
        verify(emailService, never()).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void someoneElsesFaceIsApprovedNormally() throws Exception {
        juanIsBanned();
        long id = 990301L; // approval writes uploads/QR_<id>.png, deleted afterwards
        Visitor v = v(id, "ana@example.com", ANA, "PENDING", TODAY.plusDays(3));
        when(repo.findById(id)).thenReturn(Optional.of(v));
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(v.getVisitDate());
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(10);
        when(bookingRepo.findBySlotDateAndTimeWindow(v.getVisitDate(), WINDOW)).thenReturn(List.of(slot));

        try {
            mvc.perform(post("/admin/approve/" + id).with(csrf())).andExpect(flash().attribute("success", "Visitor approved and Email sent successfully!"));
        } finally {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + id + ".png"));
        }
        assertThat(v.getStatus()).isEqualTo("APPROVED");
    }

    // --- The ban's name and mugshot are for the guards only: enforcement is the face vector alone ---

    private void juanIsBannedWithNameAndPhoto() {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(1L);
        ban.setFaceEncoding(JUAN);
        ban.setName("Ana Reyes");            // even a name that matches another visitor exactly
        ban.setImageUrl("ban-photo.jpg");
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));
    }

    @Test
    void aVisitorWhoOnlySharesTheBannedNameIsApproved() throws Exception {
        juanIsBannedWithNameAndPhoto();
        long id = 990303L; // approval writes uploads/QR_<id>.png, deleted afterwards
        Visitor v = v(id, "ana@example.com", ANA, "PENDING", TODAY.plusDays(3));
        v.setName("Ana Reyes");
        when(repo.findById(id)).thenReturn(Optional.of(v));
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(v.getVisitDate());
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(10);
        when(bookingRepo.findBySlotDateAndTimeWindow(v.getVisitDate(), WINDOW)).thenReturn(List.of(slot));

        try {
            mvc.perform(post("/admin/approve/" + id).with(csrf())).andExpect(flash().attribute("success", "Visitor approved and Email sent successfully!"));
        } finally {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + id + ".png"));
        }
        assertThat(v.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void theBannedFaceUnderAnotherNameIsStillRefused() throws Exception {
        juanIsBannedWithNameAndPhoto();
        Visitor v = v(21, "someone.new@example.com", JUAN, "PENDING", TODAY.plusDays(3));
        v.setName("Totally Different Name");
        when(repo.findById(21L)).thenReturn(Optional.of(v));
        when(repo.findByReferenceId("PAF-REF21")).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/approve/21").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.BANNED_APPROVAL));
        v.setVisitDate(TODAY);
        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-REF21"))
            .andExpect(flash().attribute("error", GuardOperationController.BANNED_ENTRY));
        assertThat(v.getStatus()).isEqualTo("PENDING");
        verify(repo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void anExpiredBanWithNameAndPhotoIsStillIgnored() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setFaceEncoding(JUAN);
        ban.setName("Visitor 42");
        ban.setImageUrl("ban-photo.jpg");
        ban.setExpiresAt(TODAY.minusDays(1));
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));
        Visitor v = v(42, "juan@example.com", JUAN, "APPROVED", TODAY);
        v.setTimeWindow(null);
        v.setSecureToken("9b1c2d9e-7b1a-4c55-9f0e-2a6b8d4c1e99");
        when(repo.findBySecureToken(v.getSecureToken())).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", v.getSecureToken()))
            .andExpect(jsonPath("$.status").value("ready"));
    }

    // --- Check-in refuses a banned face (check-out still works) ---

    @Test
    void aBannedFaceCannotBeCheckedInManually() throws Exception {
        juanIsBanned();
        Visitor v = v(30, "juan@example.com", JUAN, "PENDING", TODAY);
        when(repo.findByReferenceId("PAF-REF30")).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-REF30"))
            .andExpect(flash().attribute("error", GuardOperationController.BANNED_ENTRY));
        assertThat(v.getStatus()).isEqualTo("PENDING");
        verify(repo, never()).save(any());
    }

    @Test
    void aBannedVisitorAlreadyInsideCanStillBeCheckedOut() throws Exception {
        juanIsBanned();
        Visitor v = v(31, "juan@example.com", JUAN, "CHECKED-IN", TODAY);
        v.setCheckInTime(LocalDateTime.now().minusHours(1));
        when(repo.findByReferenceId("PAF-REF31")).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-REF31")).andExpect(flash().attributeExists("success"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void aPassApprovedBeforeTheBanIsRefusedAtTheScanner() throws Exception {
        juanIsBanned();
        Visitor v = v(40, "juan@example.com", JUAN, "APPROVED", TODAY);
        v.setTimeWindow(null); // no window restriction, so only the ban can refuse it
        v.setSecureToken("3f1c2d9e-7b1a-4c55-9f0e-2a6b8d4c1e77");
        when(repo.findBySecureToken(v.getSecureToken())).thenReturn(Optional.of(v));
        when(repo.findById(40L)).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", v.getSecureToken()))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value(GuardOperationController.BANNED_ENTRY));
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "40"))
            .andExpect(jsonPath("$.message").value(GuardOperationController.BANNED_ENTRY));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        verify(repo, never()).save(any());
    }
}
