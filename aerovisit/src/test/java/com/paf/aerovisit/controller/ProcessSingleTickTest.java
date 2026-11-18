package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.FixedClock;
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
 * ☑️ One box at the gate: the walk-in Process panel asks "I've verified the ID(s) and payment" once, in place of the
 * separate receipt-match and Free/Discounted-IDs ticks. Check In stays disabled until every shown box is ticked, and the
 * server still requires the box whenever a receipt or an ID needs confirming, records who verified the payment and the
 * IDs, and keeps the separate ban and passport ticks. Nothing a browser sends can skip it. No database.
 */
@WebMvcTest({GuardOperationController.class, PaymentController.class, PaymentAdminController.class, GuardDashboardController.class})
@Import({FixedClock.class, SecurityConfig.class, PaymentWorkflow.class, ReceiptStorage.class, WalkInService.class})
class ProcessSingleTickTest {

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
    private String openWindow;

    @BeforeEach
    void setUp() {
        LocalTime now = FixedClock.TIME;
        openWindow = "Walk-in Window (" + CLOCK.format(now.minusHours(1)) + " - " + CLOCK.format(now.plusHours(1)) + ")";
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findByVisitDate(any())).thenReturn(today_);
    }

    // A walk-in for today: regular / discounted / free counts, a total and a payment status
    private Visitor walkIn(long id, int regular, int discounted, int free, long totalCentavos, String paymentStatus) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Guest " + id);
        v.setEmail("guest" + id + "@example.com");
        v.setReferenceId("PAF-ONE" + id);
        v.setSecureToken("token-" + id);
        v.setStatus("APPROVED");
        v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        v.setVisitDate(FixedClock.TODAY);
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
        today_.add(v);
        return v;
    }

    private ResultActions process(Visitor v, String... params) throws Exception {
        MockHttpServletRequestBuilder req = post("/admin/walk-ins/" + v.getId() + "/process").with(csrf()).with(GUARD).param("returnTo", WALKINS);
        for (String p : params) req.param(p, "true");
        return mvc.perform(req);
    }

    private String dashboard() throws Exception {
        return mvc.perform(get("/guard/dashboard").param("tab", "walkins").with(GUARD)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // --- The panel: one box, Check In disabled until it is ticked ---

    @Test
    void thePanelHasOneBoxForIdAndPaymentInsteadOfTwoAndCheckInStartsDisabled() throws Exception {
        walkIn(1, 1, 1, 0, 25_000L, Visitor.PAY_PROOF_SUBMITTED);
        String html = dashboard();
        String form = html.substring(html.indexOf("id=\"processForm\""), html.indexOf("</form>", html.indexOf("id=\"processForm\"")));
        assertThat(form).contains("id=\"pxTickVerify\"", "name=\"verifiedChecked\"", "name=\"banChecked\"")
                .doesNotContain("name=\"matchConfirmed\"", "name=\"idsChecked\"", "pxTickMatch", "pxTickIds");
        // 🛂 No Foreign walk-ins any more: no passport tick, passport link or Reject passport in the panel or the rows
        assertThat(html).doesNotContain("passportChecked", "pxTickPassport", "pxPassportLink", "pxPassportRejectForm",
                "pxPassportRejectToggle", "data-px-needs-passport", "data-px-reject-passport-url", "reject-passport");
        assertThat(form.split("name=\"verifiedChecked\"", -1)).hasSize(2); // exactly one
        // the guidance stays under the one box
        assertThat(form).contains("Billing number and amount match the receipt");
        // Check In / Verify & Check In is disabled when the panel renders
        assertThat(html).containsPattern("id=\"pxSubmit\"\\s+disabled");
        // the row's label for this group: a receipt to verify and a Discounted visitor's ID
        assertThat(html).contains("data-px-verify-text=\"I&#39;ve verified the ID(s) and payment\"");
    }

    @Test
    void theLabelNamesOnlyWhatAppliesToTheGroup() {
        assertThat(GuardOperationController.verifyTickLabel(walkIn(2, 1, 1, 0, 25_000L, Visitor.PAY_PROOF_SUBMITTED))).isEqualTo("I've verified the ID(s) and payment");
        assertThat(GuardOperationController.verifyTickLabel(walkIn(3, 2, 0, 0, 30_000L, Visitor.PAY_PROOF_SUBMITTED))).isEqualTo("I've verified the payment");
        assertThat(GuardOperationController.verifyTickLabel(walkIn(4, 0, 0, 2, 0L, Visitor.PAY_NOT_REQUIRED))).isEqualTo("I've verified the ID(s)");
        assertThat(GuardOperationController.verifyTickLabel(walkIn(5, 2, 0, 0, 30_000L, Visitor.PAY_PAID))).isEmpty(); // nothing to verify
        assertThat(GuardOperationController.verifyTickLabel(walkIn(6, 2, 0, 0, 30_000L, Visitor.PAY_AWAITING))).isEmpty(); // no receipt yet
    }

    @Test
    void theScriptKeepsCheckInDisabledUntilEveryShownBoxIsTicked() throws Exception {
        String js = new String(new ClassPathResource("static/js/process-panel.js").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // the one rule that enables the button: every visible tick checked
        assertThat(js).contains("submit.disabled = !visibleTicks().every(function (t) { return t.querySelector('input').checked; });")
                .contains("form.addEventListener('change', syncSubmit);")
                .contains("if (submit.disabled || !form.getAttribute('action')) { e.preventDefault(); return; }");
        // the one box is shown whenever its label is set (and the walk-in is not waiting or inside)
        assertThat(js).contains("show($('pxTickVerify'), !waiting && !inside && !!data('verify-text'));")
                .doesNotContain("pxTickMatch").doesNotContain("pxTickIds'");
    }

    // --- The server still requires and records each check ---

    @Test
    void withoutTheBoxNothingIsSavedAndTheOldTickNamesNoLongerCount() throws Exception {
        Visitor v = walkIn(10, 1, 1, 0, 25_000L, Visitor.PAY_PROOF_SUBMITTED);
        for (String[] params : new String[][] {{}, {"matchConfirmed", "idsChecked"}, {"matchConfirmed"}, {"idsChecked"}}) {
            process(v, params).andExpect(flash().attribute("error", "Nothing was saved. Please tick \"I've verified the ID(s) and payment\"."));
        }
        mvc.perform(post("/admin/walk-ins/10/process").with(csrf()).with(GUARD).param("verifiedChecked", "yes"))
           .andExpect(flash().attribute("error", "Nothing was saved. Please tick \"I've verified the ID(s) and payment\"."));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getEligibilityCheckedBy()).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void theBoxVerifiesThePaymentRecordsTheIdCheckAndChecksIn() throws Exception {
        Visitor v = walkIn(11, 1, 1, 0, 25_000L, Visitor.PAY_PROOF_SUBMITTED);
        process(v, "verifiedChecked")
           .andExpect(flash().attribute("success", "✅ Payment verified for billing 000011 (PHP 250.00) and checked in: Guest 11 (Group of 2)"));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        assertThat(v.getPaymentVerifiedBy()).isEqualTo("guard");
        assertThat(v.getPaymentVerifiedAt()).isEqualTo(FixedClock.NOW);
        assertThat(v.getEligibilityCheckedBy()).isNotBlank();
        assertThat(v.getEligibilityCheckedAt()).isEqualTo(FixedClock.NOW);
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
    }

    @Test
    void theBoxCannotStandInForAReceiptThatWasNotUploaded() throws Exception {
        Visitor v = walkIn(12, 2, 0, 0, 30_000L, Visitor.PAY_AWAITING);
        process(v, "verifiedChecked").andExpect(flash().attribute("error", org.hamcrest.Matchers.startsWith("Waiting for the receipt")));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_AWAITING);
        assertThat(v.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void theBanTickIsStillSeparateAndRequired() throws Exception {
        Visitor v = walkIn(13, 0, 0, 1, 0L, Visitor.PAY_NOT_REQUIRED);
        v.setPossibleBanMatch("Ban #4: Guest Thirteen");
        process(v, "verifiedChecked").andExpect(flash().attribute("error", "Nothing was saved. Please tick \"ID checked against the ban\"."));
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        process(v, "verifiedChecked", "banChecked").andExpect(flash().attribute("success", "✅ Checked in: Guest 13"));
        assertThat(v.getBanCheckedBy()).isNotBlank();
    }

    @Test
    void aGuardCannotUseTheBoxOnAnotherDaysWalkIn() throws Exception {
        Visitor v = walkIn(14, 0, 0, 2, 0L, Visitor.PAY_NOT_REQUIRED);
        v.setVisitDate(FixedClock.TODAY.plusDays(1));
        process(v, "verifiedChecked").andExpect(status().isForbidden());
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getEligibilityCheckedBy()).isNull();
    }

    @Test
    void theScannerOverlayFollowsTheSameRules() throws Exception {
        Visitor v = walkIn(15, 2, 0, 0, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        mvc.perform(post("/admin/walk-ins/15/process").with(csrf()).with(GUARD).header("X-Requested-With", "fetch").param("matchConfirmed", "true"))
           .andExpect(jsonPath("$.status").value("error"))
           .andExpect(jsonPath("$.message").value("Nothing was saved. Please tick \"I've verified the payment\"."));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        mvc.perform(post("/admin/walk-ins/15/process").with(csrf()).with(GUARD).header("X-Requested-With", "fetch").param("verifiedChecked", "true"))
           .andExpect(jsonPath("$.status").value("success"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
    }

    @Test
    void aPaidRegularOnlyGroupNeedsNoBoxAtAll() throws Exception {
        Visitor v = walkIn(16, 2, 0, 0, 30_000L, Visitor.PAY_PAID);
        process(v).andExpect(flash().attribute("success", "✅ Checked in: Guest 16 (Group of 2)"));
    }

    @Test
    void theGuardSidePassportReviewIsGone() throws Exception {
        // 🛂 No Foreign walk-ins: the Reject passport action no longer exists (form post or scanner JSON)
        Visitor v = walkIn(17, 2, 0, 0, 30_000L, Visitor.PAY_PAID);
        mvc.perform(post("/admin/walk-ins/17/reject-passport").with(csrf()).with(GUARD).param("reason", "x"))
           .andExpect(status().isNotFound());
        mvc.perform(post("/admin/walk-ins/17/reject-passport").with(csrf()).with(GUARD).header("X-Requested-With", "fetch").param("reason", "x"))
           .andExpect(status().isNotFound());
        assertThat(v.getStatus()).isEqualTo("APPROVED");
    }
}
