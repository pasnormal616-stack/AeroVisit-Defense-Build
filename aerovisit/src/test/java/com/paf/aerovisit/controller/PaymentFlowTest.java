package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.TestImages;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.BillingNumberService;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.ReceiptStorage;

/**
 * 💳 Payment for advance bookings, end to end in a web slice: approval asks for payment (billing number, amount,
 * deadline, no QR), the visitor uploads a receipt through the emailed link or Manage Booking, and the Director marks it
 * paid (then the QR is emailed) or rejects it (the visitor may upload again). No database, no real email.
 */
@WebMvcTest({GuardOperationController.class, PaymentController.class, PaymentAdminController.class})
@Import({SecurityConfig.class, PaymentWorkflow.class, ReceiptStorage.class})
class PaymentFlowTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(3);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final long ID = 990_701L;
    private static final RequestPostProcessor DIRECTOR = user("director").roles("DIRECTOR");
    private static final RequestPostProcessor GUARD = user("guard").roles("GUARD");

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private BillingNumberService billingNumbers;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private PortalSettingsRepository settingsRepo;

    private final List<Visitor> bookings = new ArrayList<>();

    @BeforeEach
    void slotAndRepository() {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(DAY, WINDOW)).thenReturn(List.of(slot));
        when(billingNumbers.next()).thenReturn("000123", "000124");
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
            for (String earlier : v.earlierReceiptList()) Files.deleteIfExists(Paths.get("uploads", earlier));
        }
    }

    // A pending booking: 2 discounted + 3 regular + 1 free at PHP 100 / 150 = PHP 650.00 (or the given counts)
    private Visitor pending(long id, int discounted, int regular, int free, Long total) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan" + id + "@example.com");
        v.setReferenceId("PAF-" + id);
        v.setStatus("PENDING");
        v.setVisitDate(DAY);
        v.setTimeWindow(WINDOW);
        v.setGroupSize(Math.max(1, discounted + regular + free));
        v.setSecureToken("qr-token-" + id);
        if (total != null) {
            v.setDiscountedCount(discounted);
            v.setRegularCount(regular);
            v.setFreeCount(free);
            v.setDiscountedPriceCentavos(10_000L);
            v.setRegularPriceCentavos(15_000L);
            v.setAdmissionTotalCentavos(total);
        }
        when(repo.findById(id)).thenReturn(Optional.of(v));
        when(repo.findByVisitDate(DAY)).thenReturn(List.of(v));
        bookings.add(v);
        return v;
    }

    private Visitor approvedAwaitingPayment() throws Exception {
        Visitor v = pending(ID, 2, 3, 1, 65_000L);
        mvc.perform(post("/admin/approve/" + ID).with(csrf()).with(DIRECTOR)).andExpect(redirectedUrl("/admin"));
        return v;
    }

    private static MockMultipartFile receipt(String name, String type, byte[] bytes) {
        return new MockMultipartFile("receipt", name, type, bytes);
    }

    private org.springframework.test.web.servlet.ResultActions upload(Visitor v, MockMultipartFile file) throws Exception {
        return mvc.perform(multipart("/payment/proof").file(file).param("token", v.getPaymentToken()).with(csrf()));
    }

    // --- Approval ---

    @Test
    void aTotalOfZeroSkipsPaymentAndSendsTheQrAtApprovalAsBefore() throws Exception {
        Visitor allFree = pending(ID, 0, 0, 3, 0L);
        mvc.perform(post("/admin/approve/" + ID).with(csrf()).with(DIRECTOR))
           .andExpect(flash().attribute("success", GuardOperationController.APPROVED_MESSAGE));
        assertThat(allFree.getStatus()).isEqualTo("APPROVED");
        assertThat(allFree.getPaymentStatus()).isEqualTo(Visitor.PAY_NOT_REQUIRED);
        assertThat(allFree.getQrCodeUrl()).isEqualTo("QR_" + ID + ".png");
        assertThat(allFree.getBillingNumber()).isNull();
        verify(emailService).sendApprovalEmailWithQR(eq(allFree.getEmail()), any(), any(), eq("QR_" + ID + ".png"), any(), any());

        Visitor older = pending(ID + 1, 0, 0, 0, null); // made before admission counts existed: no total
        mvc.perform(post("/admin/approve/" + (ID + 1)).with(csrf()).with(DIRECTOR))
           .andExpect(flash().attribute("success", GuardOperationController.APPROVED_MESSAGE));
        assertThat(older.getQrCodeUrl()).isNotNull();
        assertThat(older.getPaymentStatus()).isNull();
        verify(emailService, never()).sendPaymentRequest(any());
        verify(billingNumbers, never()).next();
    }

    @Test
    void happyPathApprovePayEmailUploadMarkPaidThenQr() throws Exception {
        LocalDateTime before = LocalDateTime.now();
        Visitor v = approvedAwaitingPayment();

        // Approval holds the seats and asks for payment: billing number, amount, deadline, no QR yet
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.holdsSeat()).isTrue();
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_AWAITING);
        assertThat(v.getBillingNumber()).isEqualTo("000123");
        assertThat(v.getPaymentToken()).hasSize(36).isNotEqualTo(v.getSecureToken());
        assertThat(v.getQrCodeUrl()).isNull();
        assertThat(v.getPaymentDueAt()).isAfter(before.plusHours(47)).isBefore(LocalDateTime.now().plusHours(48).plusMinutes(1));
        ArgumentCaptor<EmailService.PaymentNotice> notice = ArgumentCaptor.forClass(EmailService.PaymentNotice.class);
        verify(emailService).sendPaymentRequest(notice.capture());
        assertThat(notice.getValue().billingNumber()).isEqualTo("000123");
        assertThat(notice.getValue().amount()).isEqualTo("PHP 650.00");
        assertThat(notice.getValue().breakdown()).isEqualTo("2 Discounted · 3 Regular · 1 Free");
        assertThat(notice.getValue().deadline()).isNotBlank();
        verify(emailService, never()).sendApprovalEmailWithQR(any(), any(), any(), any(), any(), any());

        // The emailed link opens the payment page (no referrer leaks)
        String page = mvc.perform(get("/payment").param("token", v.getPaymentToken()))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("000123", "PHP 650.00", "Keep your billing number for reference.", "Open LANDBANK Payment Page",
                "href=\"https://www.lbp-eservices.com/egps/portal/index.jsp\" target=\"_blank\" rel=\"noopener noreferrer\"",
                "Upload Proof of Payment", "Submit Proof of Payment");
        // 💳 The same five exact steps as every payment email (fragments/payment-steps), with a Copy button per exact value
        com.paf.aerovisit.service.PaymentStepsAssert.assertFiveSteps(page, "000123", "PHP 650.00");
        assertThat(page).contains(
                "<button type=\"button\" class=\"vp-copy\" data-copy=\"PHILIPPINE AIR FORCE\" aria-label=\"Copy PHILIPPINE AIR FORCE\">Copy</button>",
                "<button type=\"button\" class=\"vp-copy\" data-copy=\"PAF Aerospace Museum\" aria-label=\"Copy PAF Aerospace Museum\">Copy</button>",
                "<button type=\"button\" class=\"vp-copy\" data-copy=\"000123\" aria-label=\"Copy billing number 000123\">Copy</button>",
                "<button type=\"button\" class=\"vp-copy\" data-copy=\"650.00\" aria-label=\"Copy amount 650.00\">Copy</button>",
                "data-copy-status");
        assertThat(page.split("class=\"vp-copy\"", -1)).as("four Copy buttons").hasSize(5);
        assertThat(page.indexOf("Complete the payment (GCash")).as("steps above the LANDBANK button")
                .isLessThan(page.indexOf("Open LANDBANK Payment Page"));

        // Upload
        upload(v, receipt("receipt.jpg", "image/jpeg", TestImages.jpeg(7)))
           .andExpect(redirectedUrl("/payment?token=" + v.getPaymentToken()))
           .andExpect(header().string("Referrer-Policy", "no-referrer"))
           .andExpect(flash().attribute("success", Matchers.startsWith("Thank you! Your proof of payment was submitted.")));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        assertThat(v.getReceiptImageUrl()).matches("receipt-[0-9a-f-]{36}\\.jpg");
        assertThat(Paths.get("uploads", v.getReceiptImageUrl())).exists();
        verify(notificationService).paymentProofSubmitted(v);

        // The Director sees the receipt through the checked endpoint, and marks it paid: then the QR is emailed
        mvc.perform(get("/admin/payments/" + ID + "/receipt").with(DIRECTOR))
           .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/jpeg"));
        mvc.perform(post("/admin/payments/" + ID + "/verify").with(csrf()).with(DIRECTOR))
           .andExpect(flash().attribute("success", Matchers.containsString("The approval email with the QR code is on its way")));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        assertThat(v.getPaymentVerifiedBy()).isEqualTo("director");
        assertThat(v.getPaymentVerifiedAt()).isNotNull();
        assertThat(v.getQrCodeUrl()).isEqualTo("QR_" + ID + ".png");
        verify(emailService).sendApprovalEmailWithQR(eq(v.getEmail()), any(), any(), eq("QR_" + ID + ".png"), any(), any());
    }

    @Test
    void aRejectedReceiptCanBeUploadedAgainThenMarkPaidSendsTheQr() throws Exception {
        Visitor v = approvedAwaitingPayment();
        upload(v, receipt("first.png", "image/png", TestImages.png(3)));
        String first = v.getReceiptImageUrl();

        mvc.perform(post("/admin/payments/" + ID + "/reject").with(csrf()).with(DIRECTOR).param("reason", "The amount does not match."))
           .andExpect(flash().attribute("success", Matchers.containsString("may upload a new receipt until")));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_REJECTED);
        assertThat(v.getPaymentRejectionReason()).isEqualTo("The amount does not match.");
        assertThat(v.getPaymentDueAt()).isAfter(LocalDateTime.now().plusHours(23)); // at least 24 more hours (the visit is days away)
        ArgumentCaptor<EmailService.PaymentNotice> notice = ArgumentCaptor.forClass(EmailService.PaymentNotice.class);
        verify(emailService).sendPaymentRejected(notice.capture());
        assertThat(notice.getValue().reason()).isEqualTo("The amount does not match.");
        assertThat(notice.getValue().deadline()).isEqualTo(PaymentWorkflow.deadlineText(v.getPaymentDueAt()));

        // Same page, new receipt: the newest is the one reviewed, and the earlier one is KEPT (a receipt is proof of
        // payment, never deleted)
        upload(v, receipt("second.jpg", "image/jpeg", TestImages.jpeg(8))).andExpect(flash().attributeExists("success"));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        assertThat(v.getReceiptImageUrl()).isNotEqualTo(first);
        assertThat(Paths.get("uploads", first)).exists();
        assertThat(v.earlierReceiptList()).containsExactly(first);

        mvc.perform(post("/admin/payments/" + ID + "/verify").with(csrf()).with(DIRECTOR)).andExpect(flash().attributeExists("success"));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        verify(emailService).sendApprovalEmailWithQR(eq(v.getEmail()), any(), any(), eq("QR_" + ID + ".png"), any(), any());
    }

    @Test
    void markAsPaidWithoutAReceiptNeedsANote() throws Exception {
        Visitor v = approvedAwaitingPayment();
        mvc.perform(post("/admin/payments/" + ID + "/verify").with(csrf()).with(DIRECTOR))
           .andExpect(flash().attribute("error", Matchers.startsWith("No receipt was uploaded for this booking")));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_AWAITING);

        mvc.perform(post("/admin/payments/" + ID + "/verify").with(csrf()).with(DIRECTOR).param("note", "verified in Landbank report"))
           .andExpect(flash().attributeExists("success"));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        assertThat(v.getPaymentVerificationNote()).isEqualTo("verified in Landbank report");
        assertThat(v.getQrCodeUrl()).isNotNull();
    }

    @Test
    void aShortDeadlineIsCalledOutWhenApproving() throws Exception {
        PortalSettings oneHour = new PortalSettings();
        oneHour.setPaymentWindowHours(1);
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.of(oneHour));
        pending(ID, 0, 1, 0, 15_000L);
        mvc.perform(post("/admin/approve/" + ID).with(csrf()).with(DIRECTOR))
           .andExpect(flash().attribute("success", Matchers.matchesPattern(".*Note: the visitor has only (59|60) minutes to pay\\.")));
    }

    @Test
    void forceApproveStillAsksForPayment() throws Exception {
        Visitor v = pending(ID, 0, 2, 0, 30_000L);
        mvc.perform(post("/admin/approve-override/" + ID).with(csrf()).with(DIRECTOR)).andExpect(flash().attributeExists("success"));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_AWAITING);
        assertThat(v.getQrCodeUrl()).isNull();
        verify(emailService).sendPaymentRequest(any());
    }

    // --- Access: only the booking's owner ---

    @Test
    void aBillingNumberAloneOrAWrongTokenOpensNothing() throws Exception {
        Visitor v = approvedAwaitingPayment();
        for (String guess : new String[] {"000123", "00000000-0000-0000-0000-000000000000", ""}) {
            String page = mvc.perform(get("/payment").param("token", guess)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(page).contains("This payment link is invalid or has expired").doesNotContain("PHP 650.00", "Juan Dela Cruz");
        }
        mvc.perform(multipart("/payment/proof").file(receipt("r.jpg", "image/jpeg", TestImages.jpeg(1))).param("token", "000123").with(csrf()))
           .andExpect(redirectedUrl("/manage-booking"));
        mvc.perform(multipart("/payment/proof").file(receipt("r.jpg", "image/jpeg", TestImages.jpeg(1))).with(csrf())) // no token, no session
           .andExpect(redirectedUrl("/manage-booking"));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_AWAITING);
        assertThat(v.getReceiptImageUrl()).isNull();
    }

    @Test
    void anotherVisitorsSessionSeesOnlyItsOwnBooking() throws Exception {
        approvedAwaitingPayment();
        Visitor other = pending(ID + 5, 0, 1, 0, 15_000L);
        other.setStatus("APPROVED");
        other.setPaymentStatus(Visitor.PAY_AWAITING);
        other.setBillingNumber("000999");
        other.setPaymentDueAt(LocalDateTime.now().plusHours(5));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("manageVisitorId", ID + 5);
        session.setAttribute("manageFormToken", "tok-other");

        String page = mvc.perform(get("/manage-booking/payment").session(session)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("000999").doesNotContain("000123", "PHP 650.00");
        // Without a signed-in session the page sends the visitor to sign in
        mvc.perform(get("/manage-booking/payment")).andExpect(redirectedUrl("/manage-booking"));
        // A forged form token can't upload for the signed-in booking either
        mvc.perform(multipart("/payment/proof").file(receipt("r.jpg", "image/jpeg", TestImages.jpeg(1)))
                .param("formToken", "forged").session(session).with(csrf()))
           .andExpect(redirectedUrl("/manage-booking"));
        assertThat(other.getReceiptImageUrl()).isNull();
    }

    // --- The receipt file ---

    @Test
    void aWrongFileTypeOrA6MbFileIsRefused() throws Exception {
        Visitor v = approvedAwaitingPayment();
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 1, 0, 1, 0, 0, 0, 0, ';'};
        byte[] script = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        byte[] fakeJpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, '<', 'h', 't', 'm', 'l', '>'}; // right first bytes, not an image
        for (MockMultipartFile bad : List.of(receipt("receipt.jpg", "image/jpeg", script), receipt("receipt.gif", "image/gif", gif),
                receipt("receipt.jpg", "image/jpeg", fakeJpeg), receipt("receipt.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8)))) {
            upload(v, bad).andExpect(flash().attribute("error", "Please upload a JPG, PNG or WEBP screenshot of your receipt."));
        }
        byte[] big = new byte[6 * 1024 * 1024];
        System.arraycopy(TestImages.jpeg(5), 0, big, 0, TestImages.jpeg(5).length);
        upload(v, receipt("big.jpg", "image/jpeg", big))
           .andExpect(flash().attribute("error", "The receipt is too large. Please upload a screenshot of 5 MB or less."));
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_AWAITING);
        assertThat(v.getReceiptImageUrl()).isNull();
    }

    @Test
    void noUploadAfterTheDeadlineOrOncePaid() throws Exception {
        Visitor v = approvedAwaitingPayment();
        v.setPaymentDueAt(LocalDateTime.now().minusMinutes(1));
        upload(v, receipt("r.jpg", "image/jpeg", TestImages.jpeg(2)))
           .andExpect(flash().attribute("error", "The payment deadline has passed. Please contact the museum."));
        v.setPaymentDueAt(LocalDateTime.now().plusHours(1));
        v.setPaymentStatus(Visitor.PAY_PAID);
        upload(v, receipt("r.jpg", "image/jpeg", TestImages.jpeg(2)))
           .andExpect(flash().attribute("error", Matchers.startsWith("Your payment has already been verified.")));
        assertThat(v.getReceiptImageUrl()).isNull();
    }

    // --- Director only ---

    @Test
    void aGuardCannotVerifyRejectSeeReceiptsOrChangePaymentSettings() throws Exception {
        Visitor v = approvedAwaitingPayment();
        upload(v, receipt("r.jpg", "image/jpeg", TestImages.jpeg(4)));
        mvc.perform(post("/admin/payments/" + ID + "/verify").with(csrf()).with(GUARD).param("note", "x")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/payments/" + ID + "/reject").with(csrf()).with(GUARD).param("reason", "x")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/payments/" + ID + "/receipt").with(GUARD)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/settings/payment").with(csrf()).with(GUARD).param("paymentWindowHours", "1")).andExpect(status().isForbidden());
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        // Nobody, not even the Director, reads a receipt straight from /uploads
        mvc.perform(get("/uploads/" + v.getReceiptImageUrl()).with(DIRECTOR)).andExpect(status().isForbidden());
        // ...and the check itself refuses a Guard
        assertThat(PaymentAdminController.canViewReceipt(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("guard", null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_GUARD"))), v, java.time.LocalDate.now())).isFalse();
    }

    // --- Payment settings ---

    @Test
    void paymentSettingsNeedHttpsAndSensibleHours() throws Exception {
        for (String bad : new String[] {"http://www.lbp-eservices.com/", "javascript:alert(1)", "https://", "ftp://x.ph"}) {
            mvc.perform(post("/admin/settings/payment").with(csrf()).with(DIRECTOR).param("paymentWindowHours", "48").param("landbankUrl", bad))
               .andExpect(flash().attribute("error", Matchers.startsWith("The Landbank link must start with https://")));
        }
        for (String hours : new String[] {"0", "721", "abc", ""}) {
            mvc.perform(post("/admin/settings/payment").with(csrf()).with(DIRECTOR).param("paymentWindowHours", hours).param("landbankUrl", ""))
               .andExpect(flash().attribute("error", Matchers.startsWith("The payment deadline must be")));
        }
        verify(settingsRepo, never()).save(any());

        mvc.perform(post("/admin/settings/payment").with(csrf()).with(DIRECTOR).param("paymentWindowHours", "24").param("landbankUrl", ""))
           .andExpect(flash().attribute("success", Matchers.containsString("the LANDBANK button is hidden")));
        ArgumentCaptor<PortalSettings> saved = ArgumentCaptor.forClass(PortalSettings.class);
        verify(settingsRepo).save(saved.capture());
        assertThat(saved.getValue().getPaymentWindowHours()).isEqualTo(24);
        assertThat(saved.getValue().getLandbankUrl()).isEmpty();
    }

    @Test
    void aClearedLandbankLinkHidesTheButtonAndShowsTheContactEmail() throws Exception {
        PortalSettings cleared = new PortalSettings();
        cleared.setLandbankUrl("");
        cleared.setContactEmail("museum@paf.example");
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.of(cleared));
        Visitor v = approvedAwaitingPayment();
        String page = mvc.perform(get("/payment").param("token", v.getPaymentToken())).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).doesNotContain("Open LANDBANK Payment Page").contains("mailto:museum@paf.example", "Questions about payment? Email");
    }
}
