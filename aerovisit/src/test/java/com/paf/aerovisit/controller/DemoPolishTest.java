package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ErrorProperties.IncludeAttribute;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.unit.DataSize;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumZoneRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

import jakarta.servlet.RequestDispatcher;

/**
 * Capstone demo polish: 15 MB uploads, friendly error + 404 pages with no Java internals, bad public URLs get 404
 * instead of the staff login, alerts go to the configured inbox, and no missing slideshow photos. No database.
 */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "aerovisit.alerts.email=alerts@museum.test")
class DemoPolishTest {

    private static final String SECRET_DETAIL = "SQLGrammarException at com.paf.aerovisit.SecretInternals";

    @Autowired private MockMvc mvc;
    @Autowired private ServerProperties serverProperties;
    @Autowired private org.thymeleaf.ITemplateEngine templateEngine;
    @Autowired private org.springframework.core.env.Environment env;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private MuseumZoneRepository zoneRepo;

    // --- 1. Upload limit + no Java internals ---

    @Test
    void uploadsUpTo15MbAreAccepted() {
        assertThat(DataSize.parse(env.getProperty("spring.servlet.multipart.max-file-size"))).isEqualTo(DataSize.ofMegabytes(15));
        assertThat(DataSize.parse(env.getProperty("spring.servlet.multipart.max-request-size"))).isEqualTo(DataSize.ofMegabytes(15));
    }

    @Test
    void errorDetailsAreNeverIncluded() {
        assertThat(String.valueOf(serverProperties.getError().getIncludeStacktrace())).isEqualTo("NEVER");
        assertThat(serverProperties.getError().getIncludeMessage()).isEqualTo(IncludeAttribute.NEVER);
        assertThat(serverProperties.getError().getIncludeBindingErrors()).isEqualTo(IncludeAttribute.NEVER);
        assertThat(serverProperties.getError().isIncludeException()).isFalse();
    }

    private String errorPage(int status, MediaType accept) throws Exception {
        return mvc.perform(get("/error").accept(accept)
                .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, status)
                .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/save")
                .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(SECRET_DETAIL)))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void theGenericErrorPageIsFriendlyAndHidesTheException() throws Exception {
        String html = errorPage(500, MediaType.TEXT_HTML);
        assertThat(html).contains("Something Went Wrong", "Back to Booking Page", "Error 500")
                        .doesNotContain(SECRET_DETAIL).doesNotContain("IllegalStateException").doesNotContain("Whitelabel");
    }

    @Test
    void anOversizedUploadExplainsTheLimit() throws Exception {
        assertThat(errorPage(413, MediaType.TEXT_HTML)).contains("File Too Large", "15 MB").doesNotContain(SECRET_DETAIL);
    }

    @Test
    void jsonErrorsCarryNoTraceOrMessageEither() throws Exception {
        String json = errorPage(500, MediaType.APPLICATION_JSON);
        assertThat(json).doesNotContain("trace").doesNotContain("exception").doesNotContain(SECRET_DETAIL);
    }

    @Test
    void theNotFoundTemplateRendersItsOwnPage() {
        // Rendered directly: this test slice's error view resolver doesn't pick status templates (the running app does,
        // checked live: /no-such-page shows this page)
        String html = templateEngine.process("error/404", new org.thymeleaf.context.Context());
        assertThat(html).contains("Page Not Found", "Error 404", "href=\"/\"", "Back to Booking Page")
                        .doesNotContain("SecurityConfig").doesNotContain("Spring Boot");   // parser comments stay server-side
    }

    @Test
    void theGenericErrorTemplateKeepsItsDeveloperCommentServerSide() throws Exception {
        assertThat(errorPage(500, MediaType.TEXT_HTML)).doesNotContain("server.error").doesNotContain("stack");
    }

    @Test
    void anOversizedBookingPhotoRedirectsBackWithAMessageInsteadOfABlank413() throws Exception {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/save");
        org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap flash = new org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap();

        String view = new com.paf.aerovisit.config.CmsUploadLimitAdvice().handleTooLarge(
                new org.springframework.web.multipart.MaxUploadSizeExceededException(15L * 1024 * 1024), request, flash);

        assertThat(view).isEqualTo("redirect:/");
        assertThat(String.valueOf(flash.getFlashAttributes().get("error"))).contains("larger than 15 MB");
        // Tomcat must read past its 2MB default, or the browser sees "connection reset" before the redirect (checked live in Chrome)
        assertThat(DataSize.parse(env.getProperty("server.tomcat.max-swallow-size"))).isEqualTo(DataSize.ofMegabytes(50));
    }

    // --- 4. Bad public URLs: 404, not the staff login ---

    @Test
    void anAnonymousVisitorOnABadUrlGetsA404NotTheStaffLogin() throws Exception {
        for (String path : new String[] {"/does-not-exist", "/bookings", "/favicon.ico", "/register.php"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void staffAreasStillSendLoggedOutUsersToTheLogin() throws Exception {
        for (String path : new String[] {"/admin", "/admin/staff", "/admin/no-such-page", "/uploads/QR_5.png", "/api/notifications", "/api/booking/verify-id"}) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
        }
        mvc.perform(post("/admin/approve/1").with(csrf())).andExpect(redirectedUrlPattern("**/login"));
    }

    // --- 2. Alerts go to the configured inbox ---

    @Test
    void criticalFeedbackAlertsGoToTheConfiguredInbox() throws Exception {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setName("Juan Dela Cruz");
        v.setStatus("CHECKED-OUT");
        v.setVisitDate(LocalDate.now());
        v.setSecureToken("tok");
        when(repo.findBySecureToken("tok")).thenReturn(Optional.of(v));
        when(aiService.analyzeFeedbackSentiment(anyString())).thenReturn(new JSONObject(
            "{\"sentiment\":\"NEGATIVE\",\"critical_alert\":true,\"score\":0.91,\"topic_category\":\"Staff\"}"));

        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "1").param("feedback", "Rude staff"));

        verify(emailService).sendCriticalFeedbackAlert(eq("alerts@museum.test"), eq("Juan Dela Cruz"), eq("Rude staff"), eq(0.91));
    }

    // --- 3. Slideshow: no missing photos, no external placeholder ---

    @Test
    void theBackdropHasNoMissingSlidePhotosOrUnsplashImage() throws Exception {
        String html = mvc.perform(get("/manage-booking")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("vp-slide vp-slide--fallback").doesNotContain("data-src=\"/images/slide-").doesNotContain("unsplash");

        String css;
        try (InputStream in = getClass().getResourceAsStream("/static/styles/visitor.css")) {
            css = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(css).doesNotContain("unsplash");
        String fallbackRule = css.substring(css.indexOf(".vp-slide--fallback {"), css.indexOf("}", css.indexOf(".vp-slide--fallback {")));
        assertThat(fallbackRule).contains("linear-gradient").doesNotContain("url(");
    }
}
