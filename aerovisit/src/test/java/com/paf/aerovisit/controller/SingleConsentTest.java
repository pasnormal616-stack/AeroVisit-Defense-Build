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
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Booking form: one consent checkbox (guidelines + Terms + Privacy Policy, R.A. 10173) instead of two; email OTP + selfie upload. No database. */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
class SingleConsentTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;

    private String form() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return html.substring(html.indexOf("id=\"mainRegForm\""), html.indexOf("</form>", html.indexOf("id=\"mainRegForm\"")));
    }

    @Test
    void theFormHasExactlyOneRequiredConsentCheckbox() throws Exception {
        String form = form();
        Matcher boxes = Pattern.compile("<input[^>]*type=\"checkbox\"[^>]*>").matcher(form);
        int count = 0;
        String box = null;
        while (boxes.find()) { count++; box = boxes.group(); }
        assertThat(count).isEqualTo(1);
        assertThat(box).contains("id=\"rulesAgree\"", "required").doesNotContain("name=");   // browser-only, as before
        assertThat(form).doesNotContain("privacyConsent");
    }

    @Test
    void theConsentCoversGuidelinesTermsAndPrivacyWithWorkingPolicyLinks() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(form()).containsSubsequence("<label class=\"form-check-label\" for=\"rulesAgree\">",
            "I have read and agree to the museum guidelines above and the",
            "<a href=\"#vpTermsModal\" role=\"button\" data-bs-toggle=\"modal\" data-bs-target=\"#vpTermsModal\">Terms of Entry &amp; Base Regulations</a>",
            "I consent to the processing of my personal data",
            "<a href=\"#vpPrivacyModal\" role=\"button\" data-bs-toggle=\"modal\" data-bs-target=\"#vpPrivacyModal\">Privacy Policy</a>",
            "Data Privacy Act of 2012 (R.A. 10173)", "</label>");
        // the popups those links open are on the same page (footer fragment)
        assertThat(html).contains("id=\"vpTermsModal\"", "id=\"vpPrivacyModal\"");
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

    @Test
    void theIdUploadIsTwoStepAnIdCloseUpThenASelfieFromTheFrontCamera() throws Exception {
        Matcher selfie = Pattern.compile("<input[^>]*id=\"idImageInput\"[^>]*>").matcher(form());
        assertThat(selfie.find()).isTrue();
        assertThat(selfie.group()).contains("name=\"livenessImage\"", "capture=\"user\"", "accept=\"image/png, image/jpeg\"", "required");
        Matcher card = Pattern.compile("<input[^>]*id=\"idCardImageInput\"[^>]*>").matcher(form());
        assertThat(card.find()).isTrue();
        assertThat(card.group()).contains("name=\"highResIdImage\"", "capture=\"environment\"", "accept=\"image/png, image/jpeg\"", "required");
        assertThat(form()).contains("Your Valid ID + Selfie Holding It", "Close-up photo of your ID", "Selfie holding your ID");
    }

    @Test
    void theSelfieTipSitsWithTheCaptureControlsNotInsideTheFullScreenCamera() throws Exception {
        String form = form();
        assertThat(form).containsSubsequence("id=\"livenessBox\"", "id=\"livenessModal\"",
            "<div class=\"vp-note vp-cam-tip\" role=\"note\">", "💡",
            "<strong>Pro Tip:</strong> For the fastest verification, hold your device slightly above eye level in a well-lit area.",
            "id=\"idCardImageInput\"", "id=\"idImageInput\"");
        // once only, and outside the camera overlay (it must never cover the face / ID guides)
        assertThat(form.split("vp-cam-tip\"", -1)).hasSize(2);
        String overlay = form.substring(form.indexOf("id=\"livenessModal\""), form.indexOf("id=\"livenessPrompt\""));
        assertThat(overlay).doesNotContain("Pro Tip");
    }

    @Test
    void stepOneTellsPhoneUsersToLayTheIdFlatOnATable() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains(
            "'Step 1 of 2: Place your ID flat on a table and align it within the frame for a clear scan.'",
            // a laptop webcam can't see the table, so it gets its own wording
            "'Step 1 of 2: Hold your ID up to the camera and align it within the frame for a clear scan.'",
            "place your ID flat on a table and align it within the frame for a clear scan");
    }

    @Test
    void theMasterBookingToggleNeverTouchesTheTwoKycPhotos() throws Exception {
        // Zero-trust KYC: Master mode uses the same required two-step capture as every booking
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        int start = html.indexOf("function toggleMasterBooking");
        String toggle = html.substring(start, html.indexOf("function syncCompanionField", start));
        assertThat(toggle).doesNotContain("idImageInput", "idCardImageInput", "livenessBox", "vp-upload");
    }
}
