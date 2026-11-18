package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Booking form: ONE consent, the Terms and Conditions popup (it covers the guidelines, the Terms of Entry and the Privacy Policy, R.A. 10173); email OTP + selfie upload. No database. */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
class SingleConsentTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;

    private String form() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return html.substring(html.indexOf("id=\"mainRegForm\""), html.indexOf("</form>", html.indexOf("id=\"mainRegForm\"")));
    }

    private String page() throws Exception {
        return mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // The Terms and Conditions popup: from its opening tag to the end of its markup (its script follows it)
    private static String popup(String html) {
        int start = html.lastIndexOf("<div", html.indexOf("id=\"vpBookingTermsModal\""));
        return html.substring(start, html.indexOf("<script>", start));
    }

    @Test
    void thereIsExactlyOneConsentCheckboxOnThePageAndItIsInTheTermsPopup() throws Exception {
        String html = page();
        Matcher boxes = Pattern.compile("<input[^>]*type=\"checkbox\"[^>]*>").matcher(html);
        int count = 0;
        String box = null;
        while (boxes.find()) { count++; box = boxes.group(); }
        assertThat(count).isEqualTo(1);
        assertThat(box).contains("id=\"termsAgree\"");
        assertThat(popup(html)).contains(box, "<label class=\"form-check-label\" for=\"termsAgree\">I have read and agree to the Terms and Conditions.</label>");
        // The form carries the popup's answer to the server, which refuses a booking without it
        assertThat(form()).contains("<input type=\"hidden\" name=\"termsAccepted\" id=\"termsAcceptedInput\" value=\"\">")
                .doesNotContain("type=\"checkbox\"", "rulesAgree", "privacyConsent", "Admission is free");
    }

    @Test
    void theTermsPopupHasTheGivenTextAnAccessibleDialogAndADisabledAgreeButton() throws Exception {
        String html = page();
        String popup = popup(html);
        assertThat(popup).contains("class=\"modal fade vp-legal vp-terms\"", "data-bs-backdrop=\"static\"",
                "aria-labelledby=\"vpBookingTermsTitle\"", "aria-describedby=\"vpBookingTermsSubtitle\"",
                "<h2 class=\"modal-title\" id=\"vpBookingTermsTitle\">Terms and Conditions</h2>",
                "id=\"vpBookingTermsSubtitle\">PAF Museum Booking and Reservation System</span>",
                "<button type=\"button\" class=\"vp-btn vp-btn--primary\" id=\"termsAgreeBtn\" disabled>Agree &amp; Continue</button>");
        assertThat(popup).containsSubsequence("<h3>Confidentiality of Information</h3>", "subject to applicable data privacy laws and",
                "<h3>No-Refund Policy</h3>", "Reservation payments are non-refundable.",
                "<h3>Rescheduling Due to Museum Closure</h3>", "another available date and time.</p>",
                "<h3>Visitor Acknowledgment</h3>", "read, understood, and agreed to these Terms and Conditions.",
                "<h3>Advance Booking / Group Tour Payment Policy</h3>", "Payment at the museum counter is strictly prohibited.",
                "Same-day payment on the scheduled date", "of visit is also not allowed.",
                "<h3>Proper Dress Code</h3>", "Wearing slippers is not allowed.");
        assertThat(popup).doesNotContain("Payment Before Review");
        assertThat(popup.split("<li><h3>", -1)).hasSize(7);   // exactly six sections
        // The privacy notice and the Terms of Entry stay linked from the one consent (and from step 3)
        assertThat(popup).contains("data-terms-swap=\"#vpPrivacyModal\">Privacy Policy</a>",
                "data-terms-swap=\"#vpTermsModal\">Terms of Entry &amp; Base Regulations</a>");
        assertThat(form()).contains("data-bs-target=\"#vpPrivacyModal\">Privacy Policy</a>", "data-terms-open>Review</button>");
        assertThat(html).contains("id=\"vpTermsModal\"", "id=\"vpPrivacyModal\"");
    }

    @Test
    void theStepsAreLockedUntilAgreedAndThePopupOpensOnlyWhenTheVisitorStartsBooking() throws Exception {
        String html = page();
        // The popup is outside the form and the steps (a top-level dialog, moved to <body> by its script)
        assertThat(html.indexOf("id=\"vpBookingTermsModal\"")).isGreaterThan(html.indexOf("</form>"));
        assertThat(html).contains("document.body.appendChild(modalEl)");
        assertThat(html).containsSubsequence("id=\"termsGate\"", "id=\"termsGateBtn\" data-terms-open",
                "<div class=\"vp-terms-locked\" id=\"termsLockedRegion\" inert aria-disabled=\"true\">", "id=\"bookingSteps\"", "id=\"mainRegForm\"");
        // Opened by starting to book (Book tab, "Book a Visit" links, a tap on the locked steps), never on page load
        assertThat(html).contains("bookTab.addEventListener('click', startBooking)", "bookTab.addEventListener('shown.bs.tab', startBooking)",
                "document.querySelectorAll('[data-open-tab=\"book-tab\"]')", "gate.addEventListener('click'");
        String script = html.substring(html.indexOf("var modalEl = document.getElementById('vpBookingTermsModal')"));
        script = script.substring(0, script.indexOf("</script>"));
        assertThat(script).doesNotContain("DOMContentLoaded", "vp:welcome-closed", "window.addEventListener('load'");
    }

    @Test
    void emailOtpReplacedTheRecaptchaWidget() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("recaptcha", "g-recaptcha");
        // the code box sits right after the email field, in step 02, and is never posted with the booking (no name)
        assertThat(form()).containsSubsequence("id=\"emailInput\"", "id=\"otpField\"", "id=\"otpSendBtn\"",
            "id=\"otpCodeInput\"", "id=\"otpVerifyBtn\"", "id=\"otpStatus\"", "stepTitle2", "id=\"submitBtn\"");
        Matcher codeBox = Pattern.compile("<input[^>]*id=\"otpCodeInput\"[^>]*>").matcher(form());
        assertThat(codeBox.find()).isTrue();
        assertThat(codeBox.group()).contains("required", "autocomplete=\"one-time-code\"").doesNotContain("name=");
        assertThat(html).contains("fetch(url", "'/otp/send'", "'/otp/verify'", "setCustomValidity");
    }

    // 🪪 One ID photo (no selfie): "Take a photo" (camera on phones) or "Upload a photo", JPG / PNG / WEBP
    @Test
    void theIdStepAsksForOnePhotoTakenOrUploadedAndNoSelfie() throws Exception {
        String form = form();
        Matcher sent = Pattern.compile("<input[^>]*id=\"idCardImageInput\"[^>]*>").matcher(form);
        assertThat(sent.find()).isTrue();
        assertThat(sent.group()).contains("type=\"file\"", "name=\"highResIdImage\"", "accept=\"image/jpeg,image/png,image/webp\"",
                "required", "aria-hidden=\"true\"");
        Matcher camera = Pattern.compile("<input[^>]*id=\"idPhotoCamera\"[^>]*>").matcher(form);
        assertThat(camera.find()).isTrue();
        assertThat(camera.group()).contains("capture=\"environment\"", "accept=\"image/jpeg,image/png,image/webp\"").doesNotContain("name=");
        Matcher gallery = Pattern.compile("<input[^>]*id=\"idPhotoGallery\"[^>]*>").matcher(form);
        assertThat(gallery.find()).isTrue();
        assertThat(gallery.group()).doesNotContain("capture=", "name=");
        assertThat(form).contains("Photo of your valid ID", "Take a photo", "Upload a photo", "id=\"idPhotoPreview\"",
                "Show the whole card", "Avoid glare", "name on the ID is readable")
            .doesNotContain("livenessImage", "Selfie Holding It", "livenessBox", "vp-cam");

        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // Downscaled to 2000 px and sent as JPEG; HEIC is converted when the browser can read it, else refused clearly
        assertThat(html).contains("var MAX_SIDE = 2000", "'image/jpeg', QUALITY", "This photo is in HEIC format", "'Retake'", "'Choose another'")
            .doesNotContain("function hardStop(", "A live camera is required for identity verification", "face_landmarker");
    }

    @Test
    void theFormAsksForTheThreeAdmissionCountsWithNoMasterBookingOrGuestList() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(form()).contains("name=\"discountedCount\"", "name=\"regularCount\"", "name=\"freeCount\"",
                "Estimated Total", "The total excludes the free category.", "Only your own details and ID are needed for the whole group.")
                .doesNotContain("name=\"groupSize\"", "id=\"groupSizeInput\"");
        assertThat(html).doesNotContain("Master Booking", "toggleMasterBooking", "isMasterBooking", "name=\"companionNames\"",
                "companionManual", "companionCsv", "guest-list-template", "eventJustification");
    }
}
