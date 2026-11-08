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
import org.mockito.ArgumentCaptor;
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
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * 🪪 Ban integrity (bans by email and phone number; the name is only a flag; no face data): banning revokes every live
 * booking with that email or phone number, and approval / check-in / the scanner refuse them. An older face-only ban
 * row blocks nothing. No database.
 */
@WebMvcTest({ManagementController.class, GuardOperationController.class})
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class BanIntegrityTest {

    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate TODAY = LocalDate.now();
    private static final String APPROVED_MSG = "Visitor approved. The approval email with the QR code is on its way.";

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

    private static Visitor v(long id, String email, String status, LocalDate date) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail(email);
        v.setStatus(status);
        v.setVisitDate(date);
        v.setTimeWindow(WINDOW);
        v.setGroupSize(1);
        v.setReferenceId("PAF-REF" + id);
        if ("APPROVED".equals(status)) v.setQrCodeUrl("QR_" + id + ".png");
        return v;
    }

    private void juanIsBannedByEmailAndPhone() {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(5L);
        ban.setEmail("juan@example.com");
        ban.setPhone("09171234567");
        ban.setName("Juan Dela Cruz");
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));
    }

    private void openSlot(LocalDate date) {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(date);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(10);
        when(bookingRepo.findBySlotDateAndTimeWindow(date, WINDOW)).thenReturn(List.of(slot));
    }

    private void approves(long id) throws Exception {
        try {
            mvc.perform(post("/admin/approve/" + id).with(csrf())).andExpect(flash().attribute("success", APPROVED_MSG));
        } finally {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + id + ".png"));
        }
    }

    // --- Banning revokes every live booking with that email or phone number ---

    @Test
    void banningRevokesOpenBookingsWithTheSameEmailOrPhoneAndStoresNoPhoto() throws Exception {
        Visitor banned = v(1, "juan@example.com", "PENDING", TODAY.plusDays(4));
        banned.setContactNumber("0917-123-4567");
        banned.setIdImageUrl("photo-1.jpg");                                                     // never copied into the ban
        Visitor samePhone = v(2, "juan.alt@example.com", "VERIFIED", TODAY.plusDays(4));
        samePhone.setContactNumber("+63 917 123 4567");
        Visitor awaitingReceipt = v(3, "juan@example.com", Visitor.STATUS_AWAITING_RECEIPT, TODAY.plusDays(4));
        Visitor approvedLater = v(4, "JUAN@example.com", "APPROVED", TODAY.plusDays(2));
        Visitor approvedToday = v(5, "juan@example.com", "APPROVED", TODAY);
        Visitor pastNoShow = v(6, "juan@example.com", "APPROVED", TODAY.minusDays(10));          // history: kept
        Visitor inside = v(7, "juan@example.com", "CHECKED-IN", TODAY);                         // inside the base: kept
        Visitor someoneElse = v(8, "ana@example.com", "APPROVED", TODAY.plusDays(2));            // other email and phone
        when(repo.findById(1L)).thenReturn(Optional.of(banned));
        when(faceBanRepo.findAll()).thenReturn(List.of());
        when(repo.findAll()).thenReturn(new ArrayList<>(List.of(banned, samePhone, awaitingReceipt, approvedLater,
                approvedToday, pastNoShow, inside, someoneElse)));

        mvc.perform(post("/admin/biometric-ban/1").with(csrf()).param("reason", "Fake ID"))
            .andExpect(redirectedUrl("/admin"))
            .andExpect(flash().attribute("success", "🪪 Visitor banned permanently: Visitor 1. New bookings with this email or phone number will be refused, and bookings under a similar name will be flagged for review. "
                    + "5 open booking(s) of this person were revoked."));

        ArgumentCaptor<BlacklistedFace> saved = ArgumentCaptor.forClass(BlacklistedFace.class);
        verify(faceBanRepo).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("juan@example.com");
        assertThat(saved.getValue().getPhone()).isEqualTo("09171234567");
        assertThat(saved.getValue().getName()).isEqualTo("Visitor 1");
        assertThat(saved.getValue().getImageUrl()).isNull();
        assertThat(saved.getValue().getFaceEncoding()).isNull();
        for (Visitor revoked : List.of(banned, samePhone, awaitingReceipt, approvedLater, approvedToday)) {
            assertThat(revoked.getStatus()).as("visitor " + revoked.getId()).isEqualTo("REJECTED");
            assertThat(revoked.getQrCodeUrl()).as("visitor " + revoked.getId()).isNull();
            assertThat(revoked.getRejectionReason()).isEqualTo("Access revoked: visitor banned (Fake ID)");
        }
        assertThat(pastNoShow.getStatus()).isEqualTo("APPROVED");
        assertThat(inside.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(someoneElse.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void banningAnAlreadyBannedEmailStillRevokesABookingMadeBeforeTheBan() throws Exception {
        juanIsBannedByEmailAndPhone();
        Visitor v = v(10, "juan@example.com", "APPROVED", TODAY.plusDays(1));
        when(repo.findById(10L)).thenReturn(Optional.of(v));
        when(repo.findAll()).thenReturn(new ArrayList<>(List.of(v)));

        mvc.perform(post("/admin/biometric-ban/10").with(csrf()))
            .andExpect(flash().attribute("success", "🪪 Visitor 10 was already banned; the ban now runs permanently. 1 open booking(s) of this person were revoked."));

        assertThat(v.getStatus()).isEqualTo("REJECTED");
    }

    @Test
    void aBookingWithNoEmailOrPhoneCannotBeBanned() throws Exception {
        Visitor v = v(11, null, "PENDING", TODAY.plusDays(2));
        when(repo.findById(11L)).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/biometric-ban/11").with(csrf()))
            .andExpect(flash().attributeExists("error"));
        verify(faceBanRepo, never()).save(any());
    }

    // --- Approval, check-in and the scanner refuse a banned email or phone number ---

    @Test
    void aBookingWithTheBannedEmailOrPhoneCannotBeApprovedOrCheckedIn() throws Exception {
        juanIsBannedByEmailAndPhone();
        Visitor sameEmail = v(50, "JUAN@example.com ", "PENDING", TODAY);          // case and spaces ignored
        Visitor samePhone = v(51, "another@example.com", "PENDING", TODAY);
        samePhone.setContactNumber("+63 917 123 4567");                             // same number, other format
        when(repo.findById(50L)).thenReturn(Optional.of(sameEmail));
        when(repo.findById(51L)).thenReturn(Optional.of(samePhone));
        when(repo.findByReferenceId("PAF-REF50")).thenReturn(Optional.of(sameEmail));

        mvc.perform(post("/admin/approve/50").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.BANNED_APPROVAL));
        mvc.perform(post("/admin/approve/51").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.BANNED_APPROVAL));
        mvc.perform(post("/admin/approve-override/51").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.BANNED_APPROVAL));
        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-REF50"))
            .andExpect(flash().attribute("error", GuardOperationController.BANNED_ENTRY));

        assertThat(sameEmail.getStatus()).isEqualTo("PENDING");
        assertThat(samePhone.getStatus()).isEqualTo("PENDING");
        verify(repo, never()).save(any());
        verify(emailService, never()).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void aPassApprovedBeforeTheBanIsRefusedAtTheScanner() throws Exception {
        juanIsBannedByEmailAndPhone();
        Visitor v = v(40, "juan@example.com", "APPROVED", TODAY);
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

    @Test
    void onlyTheNameMatchingABanDoesNotBlockApproval() throws Exception {
        juanIsBannedByEmailAndPhone();
        long id = 990304L; // approval writes uploads/QR_<id>.png, deleted afterwards
        Visitor v = v(id, "different@example.com", "PENDING", TODAY.plusDays(3));
        v.setName("Juan Dela Cruz"); // staff saw the flag and decided it is someone else
        when(repo.findById(id)).thenReturn(Optional.of(v));
        openSlot(v.getVisitDate());

        approves(id);
        assertThat(v.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void anOlderFaceOnlyBanBlocksNothing() throws Exception {
        BlacklistedFace legacy = new BlacklistedFace();
        legacy.setId(9L);
        legacy.setFaceEncoding("0.1,0.2,0.3");   // a ban made under the old face check: no email, phone or name
        legacy.setImageUrl("ban-photo.jpg");
        when(faceBanRepo.findAll()).thenReturn(List.of(legacy));
        long id = 990301L;
        Visitor v = v(id, "ana@example.com", "PENDING", TODAY.plusDays(3));
        v.setFaceEncoding("0.1,0.2,0.3");         // even the same old vector on the booking
        when(repo.findById(id)).thenReturn(Optional.of(v));
        openSlot(v.getVisitDate());

        approves(id);
        assertThat(v.getStatus()).isEqualTo("APPROVED");
    }

    // --- An expired temporary ban is ignored everywhere ---

    private void juansBanEnded() {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(1L);
        ban.setEmail("juan@example.com");
        ban.setExpiresAt(TODAY.minusDays(1)); // a 30-day suspension that ended yesterday
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));
    }

    @Test
    void afterATemporaryBanEndsTheVisitorCanBeApprovedAgain() throws Exception {
        juansBanEnded();
        long id = 990302L;
        Visitor v = v(id, "juan@example.com", "PENDING", TODAY.plusDays(3));
        when(repo.findById(id)).thenReturn(Optional.of(v));
        openSlot(v.getVisitDate());

        approves(id);
        assertThat(v.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void afterATemporaryBanEndsThePassWorksAtTheScanner() throws Exception {
        juansBanEnded();
        Visitor v = v(41, "juan@example.com", "APPROVED", TODAY);
        v.setTimeWindow(null);
        v.setSecureToken("7a1c2d9e-7b1a-4c55-9f0e-2a6b8d4c1e88");
        when(repo.findBySecureToken(v.getSecureToken())).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", v.getSecureToken()))
            .andExpect(jsonPath("$.status").value("ready"));
    }

    @Test
    void aTemporaryBanStillRunningOnItsLastDayBlocksCheckIn() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setEmail("juan@example.com");
        ban.setExpiresAt(TODAY); // the last day of the suspension
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));
        Visitor v = v(32, "juan@example.com", "PENDING", TODAY);
        when(repo.findByReferenceId("PAF-REF32")).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-REF32"))
            .andExpect(flash().attribute("error", GuardOperationController.BANNED_ENTRY));
    }

    @Test
    void aBannedVisitorAlreadyInsideCanStillBeCheckedOut() throws Exception {
        juanIsBannedByEmailAndPhone();
        Visitor v = v(31, "juan@example.com", "CHECKED-IN", TODAY);
        v.setCheckInTime(LocalDateTime.now().minusHours(1));
        when(repo.findByReferenceId("PAF-REF31")).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-REF31")).andExpect(flash().attributeExists("success"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
    }
}
