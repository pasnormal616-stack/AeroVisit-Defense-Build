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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.util.HtmlUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paf.aerovisit.FixedClock;
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
 * 📷 The walk-in Process panel over the scanner's camera: a walk-in scan carries the panel's data ("panel", the same
 * values as the Guard Dashboard's data-px-*), and the panel's Check In / Reject answer the scanner's fetch with
 * {status, message} on the same URLs and the same rules, so the camera page never reloads. Both pages use one panel
 * (fragments/process-panel.html + /js/process-panel.js). No database; the clock is a fixed noon (FixedClock).
 */
@WebMvcTest({GuardOperationController.class, PaymentAdminController.class, GuardDashboardController.class})
@Import({FixedClock.class, SecurityConfig.class, PaymentWorkflow.class, ReceiptStorage.class, WalkInService.class})
class WalkInPanelOverlayTest {

    private static final RequestPostProcessor GUARD = user("guard").roles("GUARD");
    private static final String OPEN = "Walk-in Window (11:00 AM - 01:00 PM)";
    private static final String ENDED = "Walk-in Window (08:00 AM - 09:00 AM)";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private BillingNumberService billingNumbers;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;

    private final LocalDate today = FixedClock.TODAY;
    private final List<Visitor> todays = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(repo.findByVisitDate(any())).thenReturn(todays);
    }

    // A walk-in (or advance booking) of 2 Regular (PHP 300.00) or 2 Free, today unless said otherwise
    private Visitor booking(long id, boolean walkIn, long totalCentavos, String paymentStatus, LocalDate date, String window) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Guest " + id);
        v.setEmail("guest" + id + "@example.com");
        v.setReferenceId("PAF-PANEL" + id);
        v.setSecureToken("secure-" + id);
        v.setStatus("APPROVED");
        if (walkIn) v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        v.setVisitDate(date);
        v.setTimeWindow(window);
        v.setRegularCount(totalCentavos > 0 ? 2 : 0);
        v.setDiscountedCount(0);
        v.setFreeCount(totalCentavos > 0 ? 0 : 2);
        v.setGroupSize(2);
        v.setAdmissionTotalCentavos(totalCentavos);
        if (totalCentavos > 0) {
            v.setBillingNumber(String.format("%06d", id));
            v.setPaymentToken("pay-" + id);
            v.setPaymentDueAt(FixedClock.NOW.plusMinutes(20));
        }
        v.setPaymentStatus(paymentStatus);
        when(repo.findBySecureToken(v.getSecureToken())).thenReturn(Optional.of(v));
        when(repo.findById(id)).thenReturn(Optional.of(v));
        if (date.equals(today)) todays.add(v);
        return v;
    }

    private Visitor walkIn(long id, long totalCentavos, String paymentStatus) {
        return booking(id, true, totalCentavos, paymentStatus, today, OPEN);
    }

    private ResultActions scan(Visitor v) throws Exception {
        return mvc.perform(post("/admin/api/scan").with(csrf()).with(GUARD).param("qrData", v.getSecureToken()));
    }

    // The scanner overlay's request: the X-Requested-With header picks the JSON answer
    private static MockHttpServletRequestBuilder overlay(String url) {
        return post(url).with(csrf()).with(GUARD).header("X-Requested-With", "fetch").accept("application/json");
    }

    // --- The scan carries the panel ---

    @Test
    void aWalkInScanCarriesThePanelInEachPaymentState() throws Exception {
        Visitor proof = walkIn(1, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        proof.setReceiptImageUrl("receipt-00000000-0000-0000-0000-000000000001.jpg");
        scan(proof)
           .andExpect(jsonPath("$.status").value("process"))
           .andExpect(jsonPath("$.panel.state").value("proof"))
           .andExpect(jsonPath("$.panel.name").value("Guest 1"))
           .andExpect(jsonPath("$.panel.ref").value("PAF-PANEL1"))
           .andExpect(jsonPath("$.panel.window").value(OPEN))
           .andExpect(jsonPath("$.panel.billing").value("000001"))
           .andExpect(jsonPath("$.panel.total").value("PHP 300.00"))
           .andExpect(jsonPath("$.panel.receipt").value("/admin/payments/1/receipt"))
           .andExpect(jsonPath("$.panel['needs-match']").value("true"))
           .andExpect(jsonPath("$.panel['process-url']").value("/admin/walk-ins/1/process"))
           .andExpect(jsonPath("$.panel['reject-url']").value("/admin/payments/1/reject"))
           .andExpect(jsonPath("$.panel['ban-url']").value("/admin/biometric-ban/1"))
           .andExpect(jsonPath("$.panel.id").value("1"));

        scan(walkIn(2, 30_000L, Visitor.PAY_AWAITING))
           .andExpect(jsonPath("$.panel.state").value("waiting"))
           .andExpect(jsonPath("$.panel.receipt").value(""))
           .andExpect(jsonPath("$.panel.due").value(FixedClock.NOW.plusMinutes(20).toString()))
           .andExpect(jsonPath("$.panel['due-text']").value("12:20 PM"));

        Visitor paid = walkIn(3, 30_000L, Visitor.PAY_PAID);
        paid.setPaymentVerifiedBy("director");
        paid.setPaymentVerifiedAt(FixedClock.NOW.minusMinutes(5));
        scan(paid)
           .andExpect(jsonPath("$.panel.state").value("cleared"))
           .andExpect(jsonPath("$.panel.verified").value(Matchers.startsWith("Verified by director on ")));

        scan(walkIn(4, 0L, Visitor.PAY_NOT_REQUIRED))
           .andExpect(jsonPath("$.panel.state").value("cleared"))
           .andExpect(jsonPath("$.panel.payment").value("Free: no payment needed"))
           .andExpect(jsonPath("$.panel.verified").value("Free, no payment"))
           .andExpect(jsonPath("$.panel['ids-text']").value(Matchers.containsString("2 Free")));

        verify(repo, never()).save(any());
    }

    @Test
    void aFlaggedWalkInsPanelShowsTheBanNextToTheId() throws Exception {
        Visitor v = walkIn(5, 0L, Visitor.PAY_NOT_REQUIRED);
        v.setPossibleBanMatch("Juan Dela Cruz");
        v.setPossibleBanId(77L);
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(77L);
        ban.setName("Juan Dela Cruz");
        ban.setImageUrl("ban-77.jpg");
        when(faceBanRepo.findById(77L)).thenReturn(Optional.of(ban));
        scan(v)
           .andExpect(jsonPath("$.panel.ban").value("Ban #77: Juan Dela Cruz"))
           .andExpect(jsonPath("$.panel['ban-photo']").value("/uploads/ban-77.jpg"))
           .andExpect(jsonPath("$.panel['needs-ban']").value("true"));
    }

    @Test
    void anAdvanceScanHasNoPanel() throws Exception {
        scan(booking(6, false, 30_000L, Visitor.PAY_PAID, today, OPEN))
           .andExpect(jsonPath("$.status").value("ready"))
           .andExpect(jsonPath("$.panel").doesNotExist());
    }

    // --- The same data on both pages ---

    @Test
    void theScanPanelSaysExactlyWhatTheDashboardsProcessButtonSays() throws Exception {
        Visitor v = walkIn(7, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        v.setDiscountedCount(1);
        v.setRegularCount(1);
        v.setReceiptImageUrl("receipt-00000000-0000-0000-0000-000000000007.jpg");
        v.setPossibleBanMatch("Ana Santos");
        v.setPossibleBanId(78L);
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(78L);
        ban.setName("Ana Santos");
        when(faceBanRepo.findById(78L)).thenReturn(Optional.of(ban));

        String json = scan(v).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, String> panel = (Map<String, String>) new ObjectMapper().readValue(json, Map.class).get("panel");

        String page = mvc.perform(get("/guard/dashboard").param("tab", "walkins").with(GUARD))
           .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Map<String, String> button = pxAttributes(page, "7");

        assertThat(panel).isNotEmpty();
        panel.forEach((key, value) -> assertThat(button.getOrDefault(key, "")).as("data-px-" + key).isEqualTo(value));
        button.forEach((key, value) -> assertThat(panel).as("panel has " + key).containsKey(key));
    }

    // data-px-* of the Process button with data-px-id = id (HTML-unescaped; a missing attribute = "")
    private static Map<String, String> pxAttributes(String page, String id) {
        Matcher tag = Pattern.compile("<button[^>]*class=\"btn btn-primary av-process-open\"[^>]*>").matcher(page);
        while (tag.find()) {
            String button = tag.group();
            if (!button.contains("data-px-id=\"" + id + "\"")) continue;
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher attr = Pattern.compile("data-px-([a-z-]+)=\"([^\"]*)\"").matcher(button);
            while (attr.find()) attrs.put(attr.group(1), HtmlUtils.htmlUnescape(attr.group(2)));
            return attrs;
        }
        throw new AssertionError("no Process button for visitor " + id);
    }

    // --- Check In from the overlay: JSON on the same URL, the same rules ---

    @Test
    void checkInFromTheOverlayAnswersJsonAndChecksTheGroupIn() throws Exception {
        Visitor v = walkIn(8, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        mvc.perform(overlay("/admin/walk-ins/8/process").param("verifiedChecked", "true"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("success"))
           .andExpect(jsonPath("$.message").value(Matchers.allOf(Matchers.startsWith("✅ Payment verified for billing 000008"),
                   Matchers.containsString("checked in: Guest 8"))));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
    }

    @Test
    void aMissingTickIsRefusedInTheOverlayAndNothingIsSaved() throws Exception {
        Visitor v = walkIn(9, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        mvc.perform(overlay("/admin/walk-ins/9/process"))
           .andExpect(jsonPath("$.status").value("error"))
           .andExpect(jsonPath("$.message").value("Nothing was saved. Please tick \"I've verified the payment\"."));
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        verify(repo, never()).save(any());
    }

    @Test
    void aClosedWindowKeepsThePaymentVerifiedAndSaysWhy() throws Exception {
        Visitor v = booking(10, true, 30_000L, Visitor.PAY_PROOF_SUBMITTED, today, ENDED);
        mvc.perform(overlay("/admin/walk-ins/10/process").param("verifiedChecked", "true"))
           .andExpect(jsonPath("$.status").value("error"))
           .andExpect(jsonPath("$.message").value(Matchers.allOf(Matchers.containsString("but check-in was refused"),
                   Matchers.containsString("Time window ended at 09:00 AM"))));
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
    }

    @Test
    void aGuardCannotProcessAnotherDaysWalkInFromTheOverlay() throws Exception {
        Visitor v = booking(11, true, 0L, Visitor.PAY_NOT_REQUIRED, today.plusDays(1), OPEN);
        mvc.perform(overlay("/admin/walk-ins/11/process").param("verifiedChecked", "true"))
           .andExpect(status().isForbidden());
        assertThat(v.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void theDashboardsNormalPostStillRedirects() throws Exception {
        Visitor v = walkIn(12, 0L, Visitor.PAY_NOT_REQUIRED);
        mvc.perform(post("/admin/walk-ins/12/process").with(csrf()).with(GUARD).param("verifiedChecked", "true"))
           .andExpect(redirectedUrl(PaymentAdminController.GUARD_RETURN));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
    }

    // --- Reject receipt from the overlay ---

    @Test
    void rejectFromTheOverlayAnswersJsonAndEmailsTheVisitor() throws Exception {
        Visitor v = walkIn(13, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        mvc.perform(overlay("/admin/payments/13/reject").param("reason", "Wrong amount"))
           .andExpect(jsonPath("$.status").value("success"))
           .andExpect(jsonPath("$.message").value(Matchers.startsWith("Receipt rejected for billing 000013")));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_REJECTED);
        assertThat(v.getPaymentRejectionReason()).isEqualTo("Wrong amount");
        verify(emailService).sendPaymentRejected(any());
    }

    @Test
    void rejectWithoutAReasonIsRefusedInTheOverlay() throws Exception {
        Visitor v = walkIn(14, 30_000L, Visitor.PAY_PROOF_SUBMITTED);
        mvc.perform(overlay("/admin/payments/14/reject").param("reason", "  "))
           .andExpect(jsonPath("$.status").value("error"))
           .andExpect(jsonPath("$.message").value("Please give a reason (up to 500 characters)."));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        verify(repo, never()).save(any());
    }

    @Test
    void aGuardCannotRejectAnAdvanceReceiptFromTheOverlay() throws Exception {
        Visitor v = booking(15, false, 30_000L, Visitor.PAY_PROOF_SUBMITTED, today, OPEN);
        mvc.perform(overlay("/admin/payments/15/reject").param("reason", "Wrong amount"))
           .andExpect(status().isForbidden());
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
    }

    // --- One panel on both pages; the scanner keeps its camera ---

    @Test
    void theScannerAndTheDashboardShareOnePanelAndTheScannerNeverNavigates() throws Exception {
        String scanner = mvc.perform(get("/admin/scanner").with(GUARD))
           .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String dashboard = mvc.perform(get("/guard/dashboard").param("tab", "walkins").with(GUARD))
           .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        for (String page : List.of(scanner, dashboard)) {
            assertThat(page).contains("id=\"processModal\"", "id=\"processForm\"", "id=\"pxRejectForm\"", "id=\"pxError\"",
                    "<script src=\"/js/process-panel.js\"></script>", "id=\"banModal\"");
        }
        assertThat(scanner).contains("AeroProcess.open(", "ajax: true", "onClose: resumeScanning",
                "document.body.classList.contains('modal-open')");
        assertThat(scanner).doesNotContain("location.assign").doesNotContain("html5QrcodeScanner.clear().catch");

        String script = Files.readString(Path.of("src/main/resources/static/js/process-panel.js"), StandardCharsets.UTF_8);
        assertThat(script).contains("'X-Requested-With': 'fetch'", "if (opts.ajax) { e.preventDefault(); send(form, 'checkin'");
        assertThat(GuardOperationController.AJAX_HEADER).isEqualTo("X-Requested-With=fetch");

        // 📱 The panel's buttons beat the generic ".av-app .btn:not(...)" height (32px; 44px on touch): at least 48px
        String css = Files.readString(Path.of("src/main/resources/static/styles/admin.css"), StandardCharsets.UTF_8);
        assertThat(css).contains(".av-app .av-process .btn.av-process-btn:not(.btn-sm):not(.p-0):not(.p-1) { min-height: 52px;",
                ".av-app .av-process .av-process-more .btn:not(.btn-sm):not(.p-0):not(.p-1) { flex: 1 1 auto; min-height: 48px; }");
    }
}
