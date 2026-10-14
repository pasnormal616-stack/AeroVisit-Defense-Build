package com.paf.aerovisit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import com.paf.aerovisit.controller.VisitorPortalController;
import com.paf.aerovisit.model.Announcement;
import com.paf.aerovisit.model.ExhibitPhoto;
import com.paf.aerovisit.model.Guideline;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.repository.AnnouncementRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.ExhibitPhotoRepository;
import com.paf.aerovisit.repository.GuidelineRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Website Content on the public portal: announcement board, guidelines tab, exhibits slideshow and photo serving. */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
@WithAnonymousUser
class PortalContentTest {

    private static final Path EXHIBITS_DIR;
    static {
        try {
            EXHIBITS_DIR = Files.createTempDirectory("exhibits-test");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void exhibitsDir(DynamicPropertyRegistry registry) {
        registry.add("aerovisit.exhibits.dir", EXHIBITS_DIR::toString);
    }

    @Autowired
    private MockMvc mvc;

    // Booking page dependencies (unchanged controller)
    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;

    // Website Content
    @MockitoBean private AnnouncementRepository announcementRepo;
    @MockitoBean private GuidelineRepository guidelineRepo;
    @MockitoBean private ExhibitPhotoRepository photoRepo;
    @MockitoBean private PortalSettingsRepository settingsRepo;

    private void savedVideo(String url) {
        PortalSettings settings = new PortalSettings();
        settings.setPromoVideoUrl(url);
        when(settingsRepo.findById(1L)).thenReturn(java.util.Optional.of(settings));
    }

    @Test
    void promoVideoShowsBelowTheAnnouncements() throws Exception {
        when(announcementRepo.findByActiveTrueOrderByAnnouncementDateDescIdDesc()).thenReturn(List.of(
            new Announcement("Closed on Dec 25", "Merry Christmas!", LocalDate.of(2026, 12, 20), true)));
        savedVideo("https://www.youtube.com/embed/dQw4w9WgXcQ");
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.stringContainsInOrder(
                "Museum Announcements", "Closed on Dec 25", "Promotional Video Tour",
                "class=\"ratio ratio-16x9 vp-video-frame\"", "src=\"https://www.youtube.com/embed/dQw4w9WgXcQ\"", "id=\"booking\"")));
    }

    @Test
    void promoVideoStillShowsWhenThereAreNoAnnouncements() throws Exception {
        savedVideo("https://www.youtube.com/embed/dQw4w9WgXcQ");
        mvc.perform(get("/"))
            .andExpect(content().string(Matchers.not(Matchers.containsString("Museum Announcements"))))
            .andExpect(content().string(Matchers.containsString("src=\"https://www.youtube.com/embed/dQw4w9WgXcQ\"")));
    }

    @Test
    void noVideoPlayerWhenTheLinkIsMissingOrBlank() throws Exception {
        mvc.perform(get("/"))
            .andExpect(content().string(Matchers.not(Matchers.containsString("<iframe"))))
            .andExpect(content().string(Matchers.not(Matchers.containsString("vp-hero-side"))));
        savedVideo("");
        mvc.perform(get("/"))
            .andExpect(content().string(Matchers.not(Matchers.containsString("<iframe"))))
            .andExpect(content().string(Matchers.not(Matchers.containsString("vp-hero-side"))));
    }

    @Test
    void portalShowsLiveAnnouncementsGuidelinesAndSlideshow() throws Exception {
        when(announcementRepo.findByActiveTrueOrderByAnnouncementDateDescIdDesc()).thenReturn(List.of(
            new Announcement("Closed on Dec 25", "Merry Christmas!\nWe reopen Dec 26.", LocalDate.of(2026, 12, 20), true)));
        when(guidelineRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(
            new Guideline("bi-camera", "Photography", "Photos are welcome.\nNo flash inside the hangars.", 1),
            new Guideline("bi-bag-x", "Bags", "No large backpacks.", 2)));
        when(photoRepo.findAllByOrderByUploadDateAscIdAsc()).thenReturn(List.of(
            new ExhibitPhoto("a1.jpg", LocalDateTime.now()), new ExhibitPhoto("b2.png", LocalDateTime.now())));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("register"))
            .andExpect(content().string(Matchers.containsString("Museum Announcements")))
            .andExpect(content().string(Matchers.containsString("Closed on Dec 25")))
            .andExpect(content().string(Matchers.containsString("December 20, 2026")))
            .andExpect(content().string(Matchers.containsString("<div class=\"ico\"><i class=\"bi bi-camera\"></i></div>")))
            .andExpect(content().string(Matchers.containsString("<h3>Photography</h3>")))
            .andExpect(content().string(Matchers.containsString("<p class=\"vp-rule-text\">Photos are welcome.\nNo flash inside the hangars.</p>")))
            .andExpect(content().string(Matchers.stringContainsInOrder("Photography", "Bags")))
            .andExpect(content().string(Matchers.containsString("id=\"exhibitCarousel\"")))
            .andExpect(content().string(Matchers.containsString("src=\"/exhibit-photos/a1.jpg\"")))
            .andExpect(content().string(Matchers.containsString("src=\"/exhibit-photos/b2.png\"")))
            .andExpect(content().string(Matchers.containsString("photo 2 of 2")))
            .andExpect(content().string(Matchers.not(Matchers.containsString("Exhibit gallery goes here"))))
            // the redundant top guidelines list is gone
            .andExpect(content().string(Matchers.not(Matchers.containsString("vp-facts vp-reveal"))));
    }

    @Test
    void portalWithNoContentHidesTheBoardAndShowsTheGuidelinesNote() throws Exception {
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(Matchers.not(Matchers.containsString("Museum Announcements"))))
            .andExpect(content().string(Matchers.containsString("Visitor guidelines will be posted here soon.")))
            .andExpect(content().string(Matchers.containsString("Exhibit gallery goes here")))
            .andExpect(content().string(Matchers.not(Matchers.containsString("id=\"exhibitCarousel\""))));
    }

    @Test
    void announcementTextIsEscaped() throws Exception {
        when(announcementRepo.findByActiveTrueOrderByAnnouncementDateDescIdDesc()).thenReturn(List.of(
            new Announcement("<script>alert(1)</script>", "<img src=x onerror=alert(1)>", LocalDate.now(), true)));
        mvc.perform(get("/"))
            .andExpect(content().string(Matchers.containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
            .andExpect(content().string(Matchers.not(Matchers.containsString("<img src=x onerror"))));
    }

    @Test
    void guidelineTextIsEscaped() throws Exception {
        when(guidelineRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(
            new Guideline("bi-shop", "<b>Bold</b>", "<script>alert(2)</script>", 1)));
        mvc.perform(get("/"))
            .andExpect(content().string(Matchers.containsString("<h3>&lt;b&gt;Bold&lt;/b&gt;</h3>")))
            .andExpect(content().string(Matchers.containsString("&lt;script&gt;alert(2)&lt;/script&gt;")))
            .andExpect(content().string(Matchers.not(Matchers.containsString("<script>alert(2)"))));
    }

    @Test
    void theDefaultGuidelinesAreTheOriginalTwoRules() {
        List<Guideline> defaults = Guideline.defaults();
        assertThat(defaults).extracting(Guideline::getTitle).containsExactly("Identification", "Dress Code");
        assertThat(defaults).extracting(Guideline::getIcon).containsExactly("bi-person-badge", "bi-shop");
        assertThat(defaults).allMatch(g -> Guideline.ICONS.containsKey(g.getIcon()));
        assertThat(defaults.get(1).getDescription()).startsWith("Smart casual attire is required.");
    }

    @Test
    void aContentDatabaseErrorNeverBreaksTheBookingPage() throws Exception {
        when(announcementRepo.findByActiveTrueOrderByAnnouncementDateDescIdDesc()).thenThrow(new RuntimeException("DB down"));
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(view().name("register"))
            .andExpect(content().string(Matchers.containsString("Smart casual attire is required.")));
    }

    @Test
    void exhibitPhotosArePublicButOtherUploadsStayStaffOnly() throws Exception {
        Files.write(EXHIBITS_DIR.resolve("public-test.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        mvc.perform(get("/exhibit-photos/public-test.png"))
            .andExpect(status().isOk());
        mvc.perform(get("/uploads/QR_1.png"))
            .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void oversizedCmsUploadShowsAFriendlyMessageButOtherPagesAreUnchanged() throws Exception {
        CmsUploadLimitAdvice advice = new CmsUploadLimitAdvice();
        MaxUploadSizeExceededException tooLarge = new MaxUploadSizeExceededException(5L * 1024 * 1024);

        MockHttpServletRequest cmsUpload = new MockHttpServletRequest("POST", "/admin/content/photos/upload");
        RedirectAttributesModelMap flash = new RedirectAttributesModelMap();
        assertThat(advice.handleTooLarge(tooLarge, cmsUpload, flash)).isEqualTo("redirect:/admin/content#photos");
        assertThat(flash.getFlashAttributes().get("error")).asString().startsWith("Upload too large.");

        // (the public booking form /save now has its own friendly redirect: DemoPolishTest)
        MockHttpServletRequest otherUpload = new MockHttpServletRequest("POST", "/api/booking/verify-id");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> advice.handleTooLarge(tooLarge, otherUpload, new RedirectAttributesModelMap()))
            .isSameAs(tooLarge);
    }

    // --- Public footer: social icons and legal modals ---

    private void savedSocial(String facebook, String instagram, String x) {
        PortalSettings settings = new PortalSettings();
        settings.setFacebookUrl(facebook);
        settings.setInstagramUrl(instagram);
        settings.setXUrl(x);
        when(settingsRepo.findById(1L)).thenReturn(java.util.Optional.of(settings));
    }

    @Test
    void footerShowsOnlyTheSavedSocialIcons() throws Exception {
        savedSocial("https://facebook.com/PAFAerospaceMuseum", "  ", "https://x.com/PAF_Museum");
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        String footer = html.substring(html.indexOf("<footer"), html.indexOf("</footer>"));
        assertThat(footer).contains("aria-label=\"Official social media\"",
            "href=\"https://facebook.com/PAFAerospaceMuseum\"", "class=\"bi bi-facebook\"",
            "href=\"https://x.com/PAF_Museum\"", "class=\"bi bi-twitter-x\"", "rel=\"noopener noreferrer\"",
            "aria-label=\"PAF Aerospace Museum on Facebook (opens in another tab)\"");
        assertThat(footer).doesNotContain("bi-instagram");
    }

    @Test
    void footerHasNoSocialRowWhenNothingIsSaved() throws Exception {
        savedSocial(null, "", null);
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("Official social media").doesNotContain("bi-facebook");
    }

    @Test
    void manageBookingFooterGetsTheSocialIconsToo() throws Exception {
        savedSocial(null, "https://www.instagram.com/paf_museum", null);
        mvc.perform(get("/manage-booking"))
            .andExpect(view().name("manage-booking"))
            .andExpect(content().string(Matchers.containsString("href=\"https://www.instagram.com/paf_museum\"")));
    }

    @Test
    void legalLinksOpenModalsWithThePolicyText() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains(
            "data-bs-toggle=\"modal\" data-bs-target=\"#vpPrivacyModal\">Privacy Policy</a>",
            "data-bs-toggle=\"modal\" data-bs-target=\"#vpTermsModal\">Terms of Entry &amp; Base Regulations</a>",
            "id=\"vpPrivacyModal\"", "R.A. 10173", "CCTV footage</b> is kept for up to 60 days",
            "We do not sell, rent or share your personal data with third parties",
            "id=\"vpTermsModal\"", "valid government-issued photo ID", "subject to inspection", "Restricted", "decent, appropriate attire");
        assertThat(html).doesNotContain("href=\"#\">Privacy Policy");
        // Two-step KYC: the policy must disclose the face matching and the 30-day deletion, and no longer deny it
        assertThat(html).contains("<b>Biometric data:</b> a face template", "one-to-one verification",
            "<b>ID photos and face templates</b> are processed temporarily and deleted automatically 30 days after the visit date",
            "once the visit is completed, missed, rejected or cancelled", "entry was denied at the gate");
        assertThat(html).doesNotContain("No face recognition or face matching is performed");
        // Human-in-the-loop: no booking is confirmed automatically, and the visitor pages must not say otherwise
        assertThat(html).contains("every booking is reviewed and approved by authorized museum personnel before an entry pass is issued",
            "Every booking is reviewed by museum personnel before your entry pass is sent.");
        assertThat(html).doesNotContain("confirmed automatically", "checked automatically");
        // modals sit after the footer, outside it
        assertThat(html.indexOf("id=\"vpPrivacyModal\"")).isGreaterThan(html.indexOf("</footer>"));
    }

    @Test
    void footerShowsTheContactEmailBelowTheAddressOnlyWhenSaved() throws Exception {
        PortalSettings settings = new PortalSettings();
        settings.setContactEmail("paf.museum@paf.mil.ph");
        when(settingsRepo.findById(1L)).thenReturn(java.util.Optional.of(settings));
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        String footer = html.substring(html.indexOf("<footer"), html.indexOf("</footer>"));
        assertThat(footer).containsSubsequence("Villamor Air Base, Pasay City, Metro Manila.",
            "<a href=\"mailto:paf.museum@paf.mil.ph\">paf.museum@paf.mil.ph</a>");
        // the Privacy Policy points the Data Protection Officer contact at the same address
        assertThat(html).containsPattern("Data Protection Officer at\\s+<a href=\"mailto:paf.museum@paf.mil.ph\">paf.museum@paf.mil.ph</a> or through the museum administration office.");

        settings.setContactEmail("  ");
        html = mvc.perform(get("/manage-booking")).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("mailto:").doesNotContain("bi-envelope")
            .contains("Data Protection Officer through the museum administration office.");
    }

    @Test
    void footerShowsThePhoneWithItsItalicNoteBeneathOnlyWhenSaved() throws Exception {
        PortalSettings settings = new PortalSettings();
        settings.setContactEmail("paf.museum@paf.mil.ph");
        settings.setContactPhone("+63 (2) 8854-6711");
        settings.setContactPhoneNote("Official inquiries only. <script>alert(1)</script>");
        when(settingsRepo.findById(1L)).thenReturn(java.util.Optional.of(settings));
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        String footer = html.substring(html.indexOf("<footer"), html.indexOf("</footer>"));
        // phone under the email, tel: link reduced to digits and the leading +, note escaped and right beneath the number
        assertThat(footer).containsSubsequence("mailto:paf.museum@paf.mil.ph", "class=\"vp-footer-phone\"",
            "<a href=\"tel:+63288546711\">+63 (2) 8854-6711</a>",
            "<small class=\"vp-footer-note\">Official inquiries only. &lt;script&gt;alert(1)&lt;/script&gt;</small>");
        assertThat(footer).doesNotContain("<script>alert(1)");

        // a note without a phone stays hidden
        settings.setContactPhone(null);
        html = mvc.perform(get("/manage-booking")).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("tel:").doesNotContain("vp-footer-phone").doesNotContain("Official inquiries only.");

        // a phone without a note shows just the number
        settings.setContactPhone("8854-6711");
        settings.setContactPhoneNote(null);
        html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<a href=\"tel:88546711\">8854-6711</a>").doesNotContain("vp-footer-note");
    }

    // --- Sgt. Aero chat window: close button and microphone error handling ---

    @Test
    void chatWindowHasACloseButtonAndExplainsABlockedMicrophone() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(html).containsSubsequence("<div class=\"chat-header\">", "id=\"chat-voice-btn\"",
            "<button type=\"button\" id=\"chat-close-btn\" class=\"chat-close-btn\" onclick=\"closeChat()\" aria-label=\"Close the AI Advisor\"",
            "<i class=\"bi bi-x-lg\"></i>", "<div id=\"chat-body\">");
        assertThat(html).contains("function closeChat()", "document.getElementById('chat-window').style.display = 'none';",
            "'Microphone access is blocked. Please ensure you have granted permission and are using a secure (HTTPS) connection.'",
            "window.isSecureContext === false");
        // every recognition error resets the mic button before anything else
        assertThat(html).containsPattern("recognition\\.onerror = \\(event\\) => \\{\\s*//[^\\n]*\\n\\s*setMicState\\(false\\);");
    }

    // --- PAF Museum logo branding (public pages) ---

    @Test
    void theOtherVisitorPagesKeepAFloatingBookAVisitLinkBesideTheToggle() throws Exception {
        String html = mvc.perform(get("/manage-booking")).andReturn().getResponse().getContentAsString();
        assertThat(html).containsSubsequence("<div class=\"vp-float-controls\">",
            "<a href=\"/\" class=\"vp-float-link\">Book a Visit</a>", "data-theme-toggle");
        assertThat(html).doesNotContain("class=\"vp-nav", "vp-brand-logo");
    }

    @Test
    void publicPagesCarryTheLogoInTheLoaderAndFooterPlusTheFavicon() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<link rel=\"icon\" type=\"image/png\" href=\"/images/paflogo.png\">");
        // no header bar and no header logo (user decision 2026-10-13): only the floating dark-mode toggle, before the
        // hero's one call to action; no Plan Visit / Guidelines / Exhibits links (the booking panel's tabs cover them)
        assertThat(html).containsSubsequence("<div class=\"vp-float-controls\">", "data-theme-toggle", "</div>",
            "data-open-tab=\"book-tab\"");
        assertThat(html).doesNotContain("class=\"vp-nav", "vp-brand-logo", "id=\"vpNav\"");
        assertThat(html).doesNotContain("class=\"vp-float-link\""); // the booking page itself needs no "Book a Visit" pill
        assertThat(html).doesNotContain(">Plan Visit<").doesNotContain("vp-btn--ghost-dark");
        // loading screen keeps its radar and progress bar; the one transparent logo file on its white badge (no JPEG)
        assertThat(html).containsSubsequence("id=\"vpPreloader\"", "vp-radar-sweep",
            "<span class=\"paf-logo-plate vp-pl-logo\"><img class=\"paf-logo\" src=\"/images/paflogo.png\" alt=\"Philippine Air Force Museum Logo\"", "vp-pl-bar");
        assertThat(html).doesNotContain("paflogowhite.jpg");
        // footer: logo above the designation and address
        assertThat(html).containsSubsequence("<div class=\"vp-footer-id\">", "vp-footer-logo", "src=\"/images/paflogo.png\"",
            "The Official Visitor Management Portal", "Villamor Air Base, Pasay City, Metro Manila.");
        assertThat(html).doesNotContain("vp-welcome-logo--placeholder");
        // the chatbot opener is untouched
        assertThat(html).contains("<button id=\"chat-toggle-btn\" type=\"button\" onclick=\"toggleChat()\"");
    }
}
