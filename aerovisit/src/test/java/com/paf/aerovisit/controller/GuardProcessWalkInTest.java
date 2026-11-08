package com.paf.aerovisit.controller;

import com.paf.aerovisit.FixedClock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.config.SecurityConfig;
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
 * 🛡️ Smoother gate: the Guard Dashboard's tab order and names, the walk-in Process panel and its one server endpoint
 * (Verify & Check In: every applicable tick required and recorded), and the Free/Discounted IDs tick at QR Accept and
 * manual check-in. No database. The booked window is built around a fixed noon (FixedClock), so these checks run the
 * same at any hour.
 */
@WebMvcTest({GuardOperationController.class, PaymentController.class, PaymentAdminController.class, GuardDashboardController.class})
@Import({FixedClock.class, SecurityConfig.class, PaymentWorkflow.class, ReceiptStorage.class, WalkInService.class})
class GuardProcessWalkInTest {

    private static final RequestPostProcessor DIRECTOR = user("director").roles("DIRECTOR");
    private static final RequestPostProcessor GUARD = user("guard").roles("GUARD");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);
    private static final String WALKINS = "/guard/dashboard?tab=walkins";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private BillingNumberService billingNumbers;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;

    private final List<Visitor> today_ = new ArrayList<>();
    private LocalDate today;
    private String openWindow;

    @BeforeEach
    void setUp() {
        LocalTime now = FixedClock.TIME;
        today = FixedClock.TODAY;
        LocalTime start = now.isAfter(LocalTime.of(1, 0)) ? now.minusHours(1) : LocalTime.of(0, 1);
        openWindow = "Walk-in Window (" + CLOCK.format(start) + " - " + CLOCK.format(now.plusHours(1)) + ")";
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByVisitDate(any())).thenReturn(today_);
    }

    // A booking for today: regular / discounted / free counts, a total, and a payment status
    private Visitor booking(long id, boolean walkIn, int regular, int discounted, int free, long totalCentavos, String paymentStatus) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Guest " + id);
        v.setEmail("guest" + id + "@example.com");
        v.setReferenceId("PAF-PROC" + id);
        v.setSecureToken("token-" + id);
        v.setStatus("APPROVED");
        if (walkIn) v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        v.setVisitDate(today);
        v.setTimeWindow(openWindow);
        v.setRegularCount(regular);
        v.setDiscountedCount(discounted);
        v.setFreeCount(free);
        v.setGroupSize(regular + discounted + free);
        v.setAdmissionTotalCentavos(totalCentavos);
        if (totalCentavos > 0) {
            v.setBillingNumber(String.format("%06d", id));
            v.setPaymentToken("pay-" + id);
            v.setPaymentDueAt(FixedClock.NOW.plusMinutes(20));
        }
        v.setPaymentStatus(paymentStatus);
        if (Visitor.PAY_PROOF_SUBMITTED.equals(paymentStatus)) v.setReceiptImageUrl("receipt-00000000-0000-0000-0000-00000000" + id + ".jpg");
        when(repo.findById(id)).thenReturn(Optional.of(v));
        when(repo.findByReferenceId(v.getReferenceId())).thenReturn(Optional.of(v));
        when(repo.findBySecureToken(v.getSecureToken())).thenReturn(Optional.of(v));
        today_.add(v);
        return v;
    }

    private ResultActions process(Visitor v, RequestPostProcessor who, String... ticks) throws Exception {
        MockHttpServletRequestBuilder req = post("/admin/walk-ins/" + v.getId() + "/process").with(csrf()).with(who)
                .param("returnTo", WALKINS);
        for (String tick : ticks) req.param(tick, "true");
        return mvc.perform(req);
    }

    private String dashboard(String... tabAndDate) throws Exception {
        MockHttpServletRequestBuilder req = get("/guard/dashboard").with(GUARD);
        if (tabAndDate.length > 0) req.param("tab", tabAndDate[0]);
        if (tabAndDate.length > 1) req.param("date", tabAndDate[1]);
        return mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // --- Tabs ---

    @Test
    void theTabsReadWalkInsAdvanceInsideLeftAndWalkInsOpensFirst() throws Exception {
        mvc.perform(get("/guard/dashboard").with(GUARD)).andExpect(model().attribute("tab", "walkins"));
        String html = dashboard();
        String tabs = html.substring(html.indexOf("id=\"gateTabs\""), html.indexOf("</nav>", html.indexOf("id=\"gateTabs\"")));
        assertThat(tabs).containsSubsequence("Walk-ins Today", "Advance Bookings", "Inside Now", "Left Today");
        assertThat(tabs).contains("href=\"/guard/dashboard?tab=walkins\" class=\"av-tab is-active\"");
    }

    @Test
    void theOldExpectedLinkAndTheNewAdvanceLinkBothOpenAdvanceBookings() throws Exception {
        for (String tab : new String[] {"expected", "advance"}) {
            mvc.perform(get("/guard/dashboard").param("tab", tab).with(GUARD))
               .andExpect(model().attribute("tab", "advance"))
               .andExpect(model().attribute("visitors", Matchers.empty()));
            assertThat(dashboard(tab)).as(tab).contains("Advance Bookings for Today",
                    "href=\"/guard/dashboard?tab=advance\" class=\"av-tab is-active\"");
        }
        // keep-my-place / bell links with the old name are still one of our own pages
        assertThat(AdminReturnTo.isOurs("/guard/dashboard?tab=expected&date=2026-11-20")).isTrue();
        assertThat(AdminReturnTo.isOurs("/guard/dashboard?tab=advance")).isTrue();
    }

    @Test
    void noGuardPageSaysExpectedArrivals() throws Exception {
        booking(1, true, 1, 0, 0, 15_000L, Visitor.PAY_AWAITING);
        booking(2, false, 1, 0, 0, 0L, Visitor.PAY_NOT_REQUIRED);
        for (String[] view : new String[][] {{}, {"walkins"}, {"advance"}, {"expected"}, {"inside"}, {"left"},
                {"advance", today.plusDays(3).toString()}}) {
            assertThat(dashboard(view)).as(String.join(" ", view)).doesNotContainIgnoringCase("Expected Arrivals")
                    .doesNotContain("Approved Arrivals", "approved arrivals");
        }
    }

    @Test
    void theProcessPanelSitsOutsideTheTableAndEachWalkInHasAProcessButton() throws Exception {
        booking(3, true, 2, 0, 0, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        String html = dashboard("walkins");
        int panel = html.indexOf("id=\"processModal\"");
        assertThat(panel).isGreaterThan(html.indexOf("</table>")).isGreaterThan(html.indexOf("</main>"));
        assertThat(html).contains("data-px-state=\"proof\"", "data-px-process-url=\"/admin/walk-ins/3/process\"",
                "data-px-reject-url=\"/admin/payments/3/reject\"", "Billing number and amount match the receipt",
                "ID checked against the ban", "Not eligible for the discounted/free rate", "modal-fullscreen-sm-down");
        // the old per-row Mark as Paid / Reject dialogs are gone
        assertThat(html).doesNotContain("walkInPaidModal", "walkInRejectModal");
    }

    // --- Verify & Check In ---

    @Test
    void verifyAndCheckInIsRefusedWithoutEachApplicableTickAndAcceptedWithThemAll() throws Exception {
        Visitor v = booking(10, true, 1, 1, 1, 25_000L, Visitor.PAY_PROOF_SUBMITTED);
        v.setPossibleBanMatch("Ban #4: Guest Ten");

        for (String[] ticks : new String[][] {{}, {"idsChecked", "banChecked"}, {"matchConfirmed", "banChecked"}, {"matchConfirmed", "idsChecked"}}) {
            process(v, GUARD, ticks)
               .andExpect(redirectedUrl(WALKINS))
               .andExpect(flash().attribute("error", Matchers.startsWith("Nothing was saved. Please tick")));
            assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
            assertThat(v.getStatus()).isEqualTo("APPROVED");
            assertThat(v.getEligibilityCheckedBy()).isNull();
            assertThat(v.getBanCheckedBy()).isNull();
        }

        process(v, GUARD, "matchConfirmed", "idsChecked", "banChecked")
           .andExpect(redirectedUrl(WALKINS))
           .andExpect(flash().attribute("success", "✅ Payment verified for billing 000010 (PHP 250.00) and checked in: Guest 10 (Group of 3)"));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        assertThat(v.getPaymentVerifiedBy()).isEqualTo("guard");
        assertThat(v.getPaymentVerifiedAt()).isNotNull();
        assertThat(v.getEligibilityCheckedBy()).isNotBlank();
        assertThat(v.getEligibilityCheckedAt()).isNotNull();
        assertThat(v.getBanCheckedBy()).isNotBlank();
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(v.getCheckInTime()).isNotNull();
        verify(emailService).sendCheckInGuidelines(any(), any());
    }

    @Test
    void aFreeWalkInNeedsOnlyTheIdsTickAndAPaidOneSkipsTheMatchTick() throws Exception {
        Visitor free = booking(11, true, 0, 0, 2, 0L, Visitor.PAY_NOT_REQUIRED);
        process(free, GUARD).andExpect(flash().attribute("error", Matchers.containsString("Checked the IDs of the Free and Discounted visitors")));
        assertThat(free.getStatus()).isEqualTo("APPROVED");
        process(free, GUARD, "idsChecked").andExpect(flash().attribute("success", "✅ Checked in: Guest 11 (Group of 2)"));
        assertThat(free.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(free.getEligibilityCheckedBy()).isNotBlank();

        Visitor paid = booking(12, true, 2, 0, 0, 30_000L, Visitor.PAY_PAID);
        process(paid, GUARD).andExpect(flash().attribute("success", "✅ Checked in: Guest 12 (Group of 2)"));
        assertThat(paid.getStatus()).isEqualTo("CHECKED-IN");
    }

    @Test
    void aCheckInRefusedByTheTimeWindowKeepsThePaymentVerifiedAndSaysWhy() throws Exception {
        Visitor v = booking(13, true, 2, 0, 0, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        LocalTime now = FixedClock.TIME;
        v.setTimeWindow("Later (" + CLOCK.format(now.plusHours(2)) + " - " + CLOCK.format(now.plusHours(3)) + ")");

        process(v, GUARD, "matchConfirmed")
           .andExpect(flash().attribute("error", Matchers.allOf(
               Matchers.startsWith("Payment verified for billing 000013 (PHP 300.00), but check-in was refused: "),
               Matchers.containsString("Check-in opens at"))));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        assertThat(v.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void waitingForTheReceiptHasNothingToVerify() throws Exception {
        Visitor v = booking(14, true, 2, 0, 0, 30_000L, Visitor.PAY_AWAITING);
        process(v, GUARD, "matchConfirmed").andExpect(flash().attribute("error", Matchers.startsWith("Waiting for the receipt")));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_AWAITING);
    }

    @Test
    void rejectReceiptEmailsTheQuickReasonAndTheNewDeadline() throws Exception {
        Visitor v = booking(15, true, 2, 0, 0, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        String reason = "The amount on the receipt does not match the billing amount.";
        mvc.perform(post("/admin/payments/15/reject").with(csrf()).with(GUARD).param("reason", reason).param("returnTo", WALKINS))
           .andExpect(redirectedUrl(WALKINS))
           .andExpect(flash().attribute("success", Matchers.containsString("may upload a new receipt until")));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_REJECTED);
        ArgumentCaptor<EmailService.PaymentNotice> notice = ArgumentCaptor.forClass(EmailService.PaymentNotice.class);
        verify(emailService).sendPaymentRejected(notice.capture());
        assertThat(notice.getValue().reason()).isEqualTo(reason);
        assertThat(notice.getValue().deadline()).isNotBlank();
    }

    @Test
    void aGuardGets403ForAnotherDaysWalkInAndForAnAdvanceBooking() throws Exception {
        Visitor yesterday = booking(16, true, 2, 0, 0, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        yesterday.setVisitDate(today.minusDays(1));
        Visitor advance = booking(17, false, 2, 0, 0, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        for (Visitor v : List.of(yesterday, advance)) {
            process(v, GUARD, "matchConfirmed", "idsChecked", "banChecked").andExpect(status().isForbidden());
            assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        }
        // The receipt: another day's is refused; today's advance one may be viewed (the scanner shows it), not verified
        mvc.perform(get("/admin/payments/16/receipt").with(GUARD)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/payments/17/receipt").with(GUARD)).andExpect(status().isNotFound()); // allowed; no file
        // the Director may open the advance booking, but Process is for walk-ins only
        process(advance, DIRECTOR, "matchConfirmed").andExpect(flash().attribute("error", GuardOperationController.PROCESS_WALK_IN_ONLY));
    }

    // --- The Free / Discounted IDs tick at QR Accept and manual check-in ---

    @Test
    void qrAcceptForAnAdvanceBookingWithFreeOrDiscountedVisitorsNeedsTheIdsTick() throws Exception {
        Visitor v = booking(20, false, 0, 1, 1, 10_000L, Visitor.PAY_PAID);

        mvc.perform(post("/admin/api/scan").with(csrf()).with(GUARD).param("qrData", "token-20"))
           .andExpect(jsonPath("$.status").value("ready"))
           .andExpect(jsonPath("$.idsCheckText").value(Matchers.startsWith("Checked the IDs of the 1 Free and 1 Discounted visitors")));

        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).with(GUARD).param("visitorId", "20"))
           .andExpect(jsonPath("$.status").value("error"))
           .andExpect(jsonPath("$.message").value(GuardOperationController.IDS_CHECK_REQUIRED));
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getEligibilityCheckedBy()).isNull();

        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).with(GUARD).param("visitorId", "20").param("idsChecked", "true"))
           .andExpect(jsonPath("$.status").value("success"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(v.getEligibilityCheckedBy()).isNotBlank();
        assertThat(v.getEligibilityCheckedAt()).isNotNull();
    }

    @Test
    void qrAcceptForARegularOnlyGroupNeedsNoIdsTick() throws Exception {
        booking(21, false, 2, 0, 0, 30_000L, Visitor.PAY_PAID);
        mvc.perform(post("/admin/api/scan").with(csrf()).with(GUARD).param("qrData", "token-21"))
           .andExpect(jsonPath("$.idsCheckText").value(""));
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).with(GUARD).param("visitorId", "21"))
           .andExpect(jsonPath("$.status").value("success"));
    }

    @Test
    void manualCheckInOfAFreeOrDiscountedGroupNeedsTheIdsTickToo() throws Exception {
        Visitor v = booking(22, false, 0, 2, 0, 20_000L, Visitor.PAY_PAID);
        mvc.perform(post("/admin/manual-checkin").with(csrf()).with(GUARD).param("referenceId", "PAF-PROC22"))
           .andExpect(flash().attribute("error", GuardOperationController.IDS_CHECK_MANUAL));
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        mvc.perform(post("/admin/manual-checkin").with(csrf()).with(GUARD).param("referenceId", "PAF-PROC22").param("idsChecked", "true"))
           .andExpect(flash().attribute("success", Matchers.startsWith("🟢 Checked-In")));
        assertThat(v.getEligibilityCheckedBy()).isNotBlank();
    }

    @Test
    void theAdminBookingDetailsTextIsBuiltFromTheCounts() {
        Visitor v = new Visitor();
        v.setRegularCount(1);
        v.setDiscountedCount(0);
        v.setFreeCount(1);
        assertThat(v.eligibilityCheckText()).isEqualTo("Checked the IDs of the 1 Free visitor (AFP ID, dependent's ID, student ID, reservist or retiree ID)");
        v.setEligibilityCheckedBy("Sgt. Cruz");
        assertThat(v.eligibilityCheckNeeded()).isFalse();
        assertThat(v.eligibilityCheckText()).isEmpty();
    }
}
