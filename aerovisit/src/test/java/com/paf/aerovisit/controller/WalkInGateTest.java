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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.TestImages;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.BillingNumberService;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.ReceiptStorage;
import com.paf.aerovisit.service.WalkInService;

/**
 * 🚶 Walk-ins at the gate: the Guard's Walk-ins tab, viewing and verifying a walk-in's receipt (today's walk-ins only),
 * the "match" and "ban" ticks, and check-in only once paid. Advance-booking receipts stay the Director's. No database.
 * The booked window is built around a fixed noon (FixedClock), so these checks run the same at any hour.
 */
@WebMvcTest({GuardOperationController.class, PaymentController.class, PaymentAdminController.class, GuardDashboardController.class})
@Import({FixedClock.class, SecurityConfig.class, PaymentWorkflow.class, ReceiptStorage.class, WalkInService.class})
class WalkInGateTest {

    private static final RequestPostProcessor DIRECTOR = user("director").roles("DIRECTOR");
    private static final RequestPostProcessor GUARD = user("guard").roles("GUARD");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private BillingNumberService billingNumbers;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;

    private final List<Visitor> bookings = new ArrayList<>();
    private LocalDate today;
    private String window;

    @BeforeEach
    void openWindowToday() {
        LocalTime now = FixedClock.TIME;
        today = FixedClock.TODAY;
        LocalTime start = now.isAfter(LocalTime.of(1, 0)) ? now.minusHours(1) : LocalTime.of(0, 1);
        window = "Walk-in Window (" + CLOCK.format(start) + " - " + CLOCK.format(now.plusHours(1)) + ")";
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByPaymentToken(anyString())).thenAnswer(inv -> bookings.stream()
                .filter(b -> inv.getArgument(0).equals(b.getPaymentToken())).findFirst());
    }

    @AfterEach
    void removeFiles() throws Exception {
        for (Visitor v : bookings) {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + v.getId() + ".png"));
            if (v.getReceiptImageUrl() != null) Files.deleteIfExists(Paths.get("uploads", v.getReceiptImageUrl()));
        }
    }

    // A walk-in for today: 2 regular at PHP 150 = PHP 300.00 to pay (or free)
    private Visitor walkIn(long id, LocalDate date, long total) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Walk In " + id);
        v.setEmail("walkin" + id + "@example.com");
        v.setReferenceId("PAF-WALK" + id);
        v.setStatus("APPROVED");
        v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        v.setVisitDate(date);
        v.setTimeWindow(window);
        v.setGroupSize(2);
        v.setDiscountedCount(0);
        v.setRegularCount(total > 0 ? 2 : 0);
        v.setFreeCount(total > 0 ? 0 : 2);
        v.setAdmissionTotalCentavos(total);
        if (total > 0) {
            v.setBillingNumber(String.format("%06d", id));
            v.setPaymentToken("walk-token-" + id);
            v.setPaymentStatus(Visitor.PAY_AWAITING);
            v.setPaymentDueAt(FixedClock.NOW.plusMinutes(30));
        } else {
            v.setPaymentStatus(Visitor.PAY_NOT_REQUIRED);
        }
        when(repo.findById(id)).thenReturn(Optional.of(v));
        when(repo.findByReferenceId(v.getReferenceId())).thenReturn(Optional.of(v));
        bookings.add(v);
        return v;
    }

    private void uploadReceipt(Visitor v) throws Exception {
        mvc.perform(multipart("/payment/proof").file(new MockMultipartFile("receipt", "landbank.jpg", "image/jpeg", TestImages.jpeg(5)))
                .param("token", v.getPaymentToken()).with(csrf()))
           .andExpect(flash().attribute("success", Matchers.containsString("Please go to the gate")));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
    }

    private org.springframework.test.web.servlet.ResultActions checkIn(Visitor v, RequestPostProcessor who, String... banChecked) throws Exception {
        // 🪪 The guard has checked the Free/Discounted IDs (a free walk-in's group is Free); refused without it (GuardProcessWalkInTest)
        var req = post("/admin/manual-checkin").with(csrf()).with(who).param("referenceId", v.getReferenceId())
                .param("returnTo", "/guard/dashboard?tab=walkins").param("idsChecked", "true");
        if (banChecked.length > 0) req.param("banChecked", banChecked[0]);
        return mvc.perform(req);
    }

    // --- A paid walk-in: receipt → guard verifies with the tick → check-in ---

    @Test
    void theGuardSeesAndVerifiesTodaysWalkInReceiptThenChecksTheGroupIn() throws Exception {
        Visitor v = walkIn(501, today, 30_000L);

        // Not paid yet: the gate refuses (the existing payment rule)
        checkIn(v, GUARD).andExpect(flash().attribute("error", GuardOperationController.PAYMENT_NOT_VERIFIED));
        assertThat(v.getStatus()).isEqualTo("APPROVED");

        uploadReceipt(v);
        verify(notificationService).paymentProofSubmitted(v); // the guards' bell (walk-in type)

        mvc.perform(get("/admin/payments/501/receipt").with(GUARD))
           .andExpect(status().isOk())
           .andExpect(content().contentType("image/jpeg"));

        // Mark as Paid without the "Billing number and amount match the receipt" tick: refused by the server
        mvc.perform(post("/admin/payments/501/verify").with(csrf()).with(GUARD)).andExpect(status().isForbidden());
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);

        mvc.perform(post("/admin/payments/501/verify").with(csrf()).with(GUARD).param("matchConfirmed", "true")
                .param("returnTo", "/guard/dashboard?tab=walkins"))
           .andExpect(redirectedUrl("/guard/dashboard?tab=walkins"))
           .andExpect(flash().attribute("success", Matchers.startsWith("Payment verified for walk-in billing 000501")));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        assertThat(v.getPaymentVerifiedBy()).isEqualTo("guard");
        assertThat(v.getPaymentVerifiedAt()).isNotNull();
        // a walk-in is already at the gate: no QR, no approval email
        assertThat(v.getQrCodeUrl()).isNull();
        assertThat(Paths.get("uploads", "QR_501.png")).doesNotExist();
        org.mockito.Mockito.verifyNoMoreInteractions(emailService); // nothing emailed since the receipt upload

        checkIn(v, GUARD).andExpect(flash().attribute("success", Matchers.startsWith("🟢 Checked-In via Manual Override: Walk In 501")));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
    }

    @Test
    void theGuardCanRejectAWalkInReceiptAndTheVisitorGetsTheWalkInMinutesAgain() throws Exception {
        Visitor v = walkIn(502, today, 30_000L);
        uploadReceipt(v);
        LocalDateTime before = FixedClock.NOW;
        mvc.perform(post("/admin/payments/502/reject").with(csrf()).with(GUARD).param("reason", "The amount does not match."))
           .andExpect(redirectedUrl("/guard/dashboard?tab=walkins"));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_REJECTED);
        // 30 more minutes (default), never past the window's end: not the advance bookings' 24 hours
        assertThat(v.getPaymentDueAt()).isBetween(before.plusMinutes(29), FixedClock.NOW.plusMinutes(31));
        verify(emailService).sendPaymentRejected(any());
    }

    @Test
    void aFreeWalkInChecksInWithoutAnyPayment() throws Exception {
        Visitor v = walkIn(503, today, 0L);
        checkIn(v, GUARD).andExpect(flash().attribute("success", Matchers.startsWith("🟢 Checked-In")));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
    }

    // --- What a Guard may not do ---

    // A Guard may VIEW today's advance receipt (the gate scanner shows it next to the ID photo) but never verify or reject it
    @Test
    void aGuardGets403ForVerifyingAnAdvanceBookingAnotherDaysWalkInAndTheSettings() throws Exception {
        Visitor yesterday = walkIn(504, today.minusDays(1), 30_000L);
        yesterday.setPaymentStatus(Visitor.PAY_PROOF_SUBMITTED);
        yesterday.setReceiptImageUrl("receipt-00000000-0000-0000-0000-000000000504.jpg");
        Visitor advance = walkIn(505, today, 30_000L);
        advance.setBookingCategory(null); // an advance booking for today
        advance.setPaymentStatus(Visitor.PAY_PROOF_SUBMITTED);
        advance.setReceiptImageUrl("receipt-00000000-0000-0000-0000-000000000505.jpg");

        mvc.perform(get("/admin/payments/504/receipt").with(GUARD)).andExpect(status().isForbidden()); // another day
        mvc.perform(get("/admin/payments/505/receipt").with(GUARD)).andExpect(status().isNotFound());  // today: allowed; file absent
        for (long id : new long[] {504, 505}) {
            mvc.perform(post("/admin/payments/" + id + "/verify").with(csrf()).with(GUARD).param("matchConfirmed", "true"))
               .andExpect(status().isForbidden());
            mvc.perform(post("/admin/payments/" + id + "/reject").with(csrf()).with(GUARD).param("reason", "x"))
               .andExpect(status().isForbidden());
        }
        assertThat(yesterday.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        assertThat(advance.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        // ...and an unpaid walk-in can't be "verified" from a note by a Guard (only the Director may, as before)
        Visitor unpaid = walkIn(506, today, 30_000L);
        mvc.perform(post("/admin/payments/506/verify").with(csrf()).with(GUARD).param("matchConfirmed", "true").param("note", "seen in app"))
           .andExpect(status().isForbidden());
        assertThat(unpaid.getPaymentStatus()).isEqualTo(Visitor.PAY_AWAITING);

        mvc.perform(post("/admin/settings/walk-in").with(csrf()).with(GUARD).param("walkInMaxPax", "50").param("walkInPaymentMinutes", "60"))
           .andExpect(status().isForbidden());
        mvc.perform(get("/admin/payments/504/receipt").with(DIRECTOR)).andExpect(status().isNotFound()); // allowed; file absent
    }

    @Test
    void theDirectorSetsTheWalkInMaximumAndMinutesWithinTheirRanges() throws Exception {
        mvc.perform(post("/admin/settings/walk-in").with(csrf()).with(DIRECTOR).param("walkInMaxPax", "101").param("walkInPaymentMinutes", "30"))
           .andExpect(flash().attribute("error", Matchers.startsWith("The walk-in maximum must be")));
        mvc.perform(post("/admin/settings/walk-in").with(csrf()).with(DIRECTOR).param("walkInMaxPax", "20").param("walkInPaymentMinutes", "241"))
           .andExpect(flash().attribute("error", Matchers.startsWith("The walk-in payment time must be")));
        verify(settingsRepo, never()).save(any());

        mvc.perform(post("/admin/settings/walk-in").with(csrf()).with(DIRECTOR).param("walkInMaxPax", "12").param("walkInPaymentMinutes", "45"))
           .andExpect(redirectedUrl("/admin/settings#walkInSettings"))
           .andExpect(flash().attribute("success", "Walk-in settings saved: at most 12 persons per walk-in, 45 minutes to pay."));
        org.mockito.ArgumentCaptor<PortalSettings> saved = org.mockito.ArgumentCaptor.forClass(PortalSettings.class);
        verify(settingsRepo).save(saved.capture());
        assertThat(saved.getValue().walkInMax()).isEqualTo(12);
        assertThat(saved.getValue().walkInMinutes()).isEqualTo(45);
    }

    // --- Name-ban flag: the guard compares the ID with the ban's photo and ticks it ---

    @Test
    void aFlaggedWalkInShowsTheBanAndNeedsTheTickToCheckIn() throws Exception {
        Visitor v = walkIn(507, today, 0L);
        v.setPossibleBanMatch("Ban #12: Juan Dela Cruz");
        v.setPossibleBanId(12L);
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(12L);
        ban.setName("Juan Dela Cruz");
        ban.setImageUrl("mugshot-12.jpg");
        when(faceBanRepo.findById(12L)).thenReturn(Optional.of(ban));
        when(repo.findByVisitDate(today)).thenReturn(new ArrayList<>(List.of(v)));

        mvc.perform(get("/guard/dashboard").param("tab", "walkins").with(GUARD))
           .andExpect(content().string(Matchers.allOf(
               Matchers.containsString("Name matches a ban"),
               Matchers.containsString("Ban #12: Juan Dela Cruz"),
               Matchers.containsString("src=\"/uploads/mugshot-12.jpg\""),
               Matchers.containsString("ID checked against the ban"))));

        checkIn(v, GUARD).andExpect(flash().attribute("error", GuardOperationController.BAN_CHECK_REQUIRED));
        assertThat(v.getStatus()).isEqualTo("APPROVED");

        checkIn(v, GUARD, "true").andExpect(flash().attribute("success", Matchers.startsWith("🟢 Checked-In")));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(v.getBanCheckedBy()).isNotBlank();
        assertThat(v.getBanCheckedAt()).isNotNull();
    }

    // 🪪 The row shows the ID photo and what the name check found; a face-ban flag shows the ban and needs the tick
    @Test
    void theWalkInRowShowsTheIdPhotoAndANameBanFlagWithNoIdCheckBadge() throws Exception {
        Visitor verified = walkIn(511, today, 0L);
        verified.setIdCardImageUrl("id-511.jpg");
        verified.setOcrNameMatch(true);
        Visitor flagged = walkIn(512, today, 0L);
        flagged.setIdCardImageUrl("id-512.jpg");
        flagged.setPossibleBanMatch("Ban #14: Pedro Reyes");
        flagged.setPossibleBanId(14L);
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(14L);
        ban.setName("Pedro Reyes");
        ban.setImageUrl("ban-14.png");
        when(faceBanRepo.findById(14L)).thenReturn(Optional.of(ban));
        when(repo.findByVisitDate(today)).thenReturn(new ArrayList<>(List.of(verified, flagged)));

        String html = mvc.perform(get("/guard/dashboard").param("tab", "walkins").with(GUARD))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("src=\"/uploads/id-511.jpg\"", "src=\"/uploads/id-512.jpg\"",
                "Ban #14: Pedro Reyes", "src=\"/uploads/ban-14.png\"", "ID checked against the ban");
        // 🪪 No automated ID check, so no verified / unverified badge (even on an older row with the old flag set)
        assertThat(html).doesNotContain("ID verified (name matches)", "ID unverified, check manually");

        checkIn(flagged, GUARD).andExpect(flash().attribute("error", GuardOperationController.BAN_CHECK_REQUIRED));
        checkIn(flagged, GUARD, "true").andExpect(flash().attribute("success", Matchers.startsWith("🟢 Checked-In")));
        assertThat(flagged.getBanCheckedBy()).isNotBlank();
        // an "unverified" walk-in can still be checked in: the guard checks the physical ID as always
        checkIn(walkIn(513, today, 0L), GUARD).andExpect(flash().attribute("success", Matchers.startsWith("🟢 Checked-In")));
    }

    // --- Guard Dashboard tabs ---

    @Test
    void walkInsHaveTheirOwnTabAndStayOutOfAdvanceBookingsButShowInsideNow() throws Exception {
        Visitor waiting = walkIn(508, today, 0L);
        Visitor inside = walkIn(509, today, 0L);
        inside.setStatus("CHECKED-IN");
        inside.setCheckInTime(FixedClock.NOW.minusMinutes(10));
        Visitor advance = walkIn(510, today, 0L);
        advance.setBookingCategory(null);
        advance.setName("Advance Guest");
        when(repo.findByVisitDate(today)).thenReturn(new ArrayList<>(List.of(waiting, inside, advance)));
        when(repo.findByStatus("CHECKED-IN")).thenReturn(new ArrayList<>(List.of(inside)));

        mvc.perform(get("/guard/dashboard").param("tab", "advance").with(GUARD))
           .andExpect(content().string(Matchers.allOf(
               Matchers.containsString("Advance Guest"),
               Matchers.not(Matchers.containsString("Walk In 508")),
               Matchers.containsString("Walk-ins Today <span class=\"count\">(2)</span>"))));

        mvc.perform(get("/guard/dashboard").param("tab", "inside").with(GUARD))
           .andExpect(content().string(Matchers.allOf(Matchers.containsString("Walk In 509"), Matchers.containsString("> Walk-in</span>"))));

        mvc.perform(get("/guard/dashboard").param("tab", "walkins").with(GUARD))
           .andExpect(content().string(Matchers.allOf(
               Matchers.containsString("Walk In 508"), Matchers.containsString("Walk In 509"),
               Matchers.not(Matchers.containsString("Advance Guest")))));
    }
}
