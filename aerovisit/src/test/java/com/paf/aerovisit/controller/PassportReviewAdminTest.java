package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.FixedClock;
import com.paf.aerovisit.TestPdfs;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.PassportReview;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.PassportReviewRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PassportStorage;

/**
 * 🛂 Passport Review (Director only): the list of waiting passports, Approve (emails the one-time booking link, valid 7
 * days) and Reject (a reason is required; emailed with the re-upload link). Passport files are served only through these
 * controllers, never from /uploads. No database.
 */
@WebMvcTest(PassportAdminController.class)
@Import({FixedClock.class, SecurityConfig.class, PassportStorage.class})
class PassportReviewAdminTest {

    private static final RequestPostProcessor DIRECTOR = user("director").roles("DIRECTOR");
    private static final RequestPostProcessor GUARD = user("guard").roles("GUARD");
    private static final String EMAIL = "anna.schmidt@example.de";
    private static final String NAME = "Schmidt, Anna";
    private static final Path UPLOADS = Paths.get("uploads");

    @Autowired private MockMvc mvc;

    @MockitoBean private PassportReviewRepository reviews;
    @MockitoBean private VisitorRepository visitors;
    @MockitoBean private EmailService emailService;

    private PassportReview review;
    private String fileName;

    @BeforeEach
    void setUp() throws Exception {
        fileName = "passport-" + java.util.UUID.randomUUID() + ".pdf";
        Files.createDirectories(UPLOADS);
        Files.write(UPLOADS.resolve(fileName), TestPdfs.pdf("review"));
        review = new PassportReview();
        review.setId(5L);
        review.setFullName(NAME);
        review.setEmail(EMAIL);
        review.setContactNumber("+49 151 2345678");
        review.setFileName(fileName);
        review.setStatus(PassportReview.PENDING);
        review.setSubmittedAt(FixedClock.NOW.minusHours(3));
        when(reviews.findById(5L)).thenReturn(Optional.of(review));
        when(reviews.findByStatusOrderBySubmittedAtAsc(PassportReview.PENDING)).thenReturn(List.of(review));
        when(reviews.save(any(PassportReview.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void removeFile() throws Exception {
        Files.deleteIfExists(UPLOADS.resolve(fileName));
    }

    // --- The list ---

    @Test
    void theDirectorSeesTheWaitingPassportsWithTheirPdfLinkAndDecisions() throws Exception {
        mvc.perform(get("/admin/passports").with(DIRECTOR))
           .andExpect(status().isOk())
           .andExpect(content().string(Matchers.containsString(NAME)))
           .andExpect(content().string(Matchers.containsString("/admin/passports/5/file")))
           .andExpect(content().string(Matchers.containsString("/admin/passports/5/approve")))
           .andExpect(content().string(Matchers.containsString("/admin/passports/5/reject")))
           .andExpect(content().string(Matchers.containsString("href=\"/admin/passports\""))); // the sidebar link
    }

    @Test
    void passportReviewSitsDirectlyUnderVisitorDashboardInTheSidebar() throws Exception {
        String html = mvc.perform(get("/admin/passports").with(DIRECTOR)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String nav = html.substring(html.indexOf("<nav class=\"av-nav\">"), html.indexOf("</nav>", html.indexOf("<nav class=\"av-nav\">")));
        // the sidebar's links in order: Visitor Dashboard, then Passport Review, then Analytics
        java.util.List<String> links = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<a [^>]*href=\"([^\"]+)\"[^>]*class=\"av-nav-link").matcher(nav);
        while (m.find()) links.add(m.group(1));
        assertThat(links).containsSubsequence("/admin", "/admin/passports", "/admin/analytics");
        assertThat(links.indexOf("/admin/passports")).isEqualTo(links.indexOf("/admin") + 1);
        // no longer in the Security group
        String security = nav.substring(nav.indexOf(">Security<"));
        assertThat(security).doesNotContain("/admin/passports");
        assertThat(nav.indexOf("Visitor Dashboard")).isLessThan(nav.indexOf("Passport Review"));
    }

    @Test
    void aGuardCannotOpenOrDecide() throws Exception {
        mvc.perform(get("/admin/passports").with(GUARD)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/passports/5/approve").with(GUARD).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/passports/5/reject").param("reason", "x").with(GUARD).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/admin/passports/5/file").with(GUARD)).andExpect(status().isForbidden());
        verify(reviews, never()).save(any());
    }

    // --- Approve: the booking-portal email with the booking link (one visit) ---

    @Test
    void approveEmailsTheBookingLinkValidForFourteenDays() throws Exception {
        mvc.perform(post("/admin/passports/5/approve").with(DIRECTOR).with(csrf()))
           .andExpect(redirectedUrl("/admin/passports"))
           .andExpect(flash().attribute("success", Matchers.startsWith("Passport approved for " + NAME)));

        assertThat(review.getStatus()).isEqualTo(PassportReview.APPROVED);
        assertThat(review.getToken()).isNotBlank();
        assertThat(review.getTokenExpiresAt()).isEqualTo(FixedClock.NOW.plusDays(PassportReview.APPROVAL_LINK_DAYS));
        assertThat(PassportReview.APPROVAL_LINK_DAYS).isEqualTo(14);
        assertThat(review.getDecidedBy()).isEqualTo("director");
        assertThat(review.getDecidedAt()).isEqualTo(FixedClock.NOW);
        String expires = FixedClock.NOW.plusDays(14).format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' h:mm a", Locale.ENGLISH));
        verify(emailService).sendPassportApproved(EMAIL, NAME, review.getToken(), expires);
        verify(emailService, never()).sendPassportRejected(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aPassportNoLongerWaitingCannotBeDecidedAgain() throws Exception {
        review.setStatus(PassportReview.APPROVED);
        mvc.perform(post("/admin/passports/5/approve").with(DIRECTOR).with(csrf()))
           .andExpect(flash().attribute("error", "This passport is no longer waiting for review."));
        mvc.perform(post("/admin/passports/5/reject").param("reason", "x").with(DIRECTOR).with(csrf()))
           .andExpect(flash().attribute("error", "This passport is no longer waiting for review."));
        verify(reviews, never()).save(any());
        verify(emailService, never()).sendPassportApproved(anyString(), anyString(), anyString(), anyString());
    }

    // --- Reject: reason required, emailed with the re-upload link ---

    @Test
    void rejectWithoutAReasonIsRefusedAndNothingIsSent() throws Exception {
        mvc.perform(post("/admin/passports/5/reject").param("reason", "   ").with(DIRECTOR).with(csrf()))
           .andExpect(redirectedUrl("/admin/passports"))
           .andExpect(flash().attribute("error", Matchers.startsWith("Please give a reason")));
        mvc.perform(post("/admin/passports/5/reject").with(DIRECTOR).with(csrf()))
           .andExpect(flash().attribute("error", Matchers.startsWith("Please give a reason")));
        assertThat(review.getStatus()).isEqualTo(PassportReview.PENDING);
        verify(reviews, never()).save(any());
        verify(emailService, never()).sendPassportRejected(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectEmailsTheReasonAndTheReuploadLink() throws Exception {
        mvc.perform(post("/admin/passports/5/reject").param("reason", "  The photo page is cropped.  ").with(DIRECTOR).with(csrf()))
           .andExpect(redirectedUrl("/admin/passports"))
           .andExpect(flash().attribute("success", Matchers.startsWith("Passport rejected for " + NAME)));

        assertThat(review.getStatus()).isEqualTo(PassportReview.REJECTED);
        assertThat(review.getRejectionReason()).isEqualTo("The photo page is cropped.");
        assertThat(review.getToken()).isNotBlank();
        assertThat(review.getTokenExpiresAt()).isNull();
        assertThat(review.getFileName()).isEqualTo(fileName); // kept until a new copy replaces it
        verify(emailService).sendPassportRejected(EMAIL, NAME, "The photo page is cropped.", review.getToken());
        verify(emailService, never()).sendPassportApproved(anyString(), anyString(), anyString(), anyString());
    }

    // --- Files: only through the controllers ---

    @Test
    void theDirectorOpensTheReviewPdfInline() throws Exception {
        mvc.perform(get("/admin/passports/5/file").with(DIRECTOR))
           .andExpect(status().isOk())
           .andExpect(content().contentType("application/pdf"))
           .andExpect(header().string("Content-Disposition", "inline"))
           .andExpect(header().string("X-Content-Type-Options", "nosniff"))
           .andExpect(header().string("Cache-Control", Matchers.containsString("no-store")));
    }

    @Test
    void passportFilesAreNeverServedFromUploads() throws Exception {
        mvc.perform(get("/uploads/" + fileName).with(DIRECTOR)).andExpect(status().isForbidden());
        mvc.perform(get("/uploads/" + fileName).with(GUARD)).andExpect(status().isForbidden());
    }

    @Test
    void aGuardOpensABookingsPassportOnlyOnTheVisitDay() throws Exception {
        Visitor today = new Visitor();
        today.setId(40L);
        today.setVisitDate(FixedClock.TODAY);
        today.setPassportFileUrl(fileName);
        Visitor tomorrow = new Visitor();
        tomorrow.setId(41L);
        tomorrow.setVisitDate(FixedClock.TODAY.plusDays(1));
        tomorrow.setPassportFileUrl(fileName);
        when(visitors.findById(40L)).thenReturn(Optional.of(today));
        when(visitors.findById(41L)).thenReturn(Optional.of(tomorrow));

        mvc.perform(get("/admin/visitors/40/passport").with(GUARD))
           .andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        mvc.perform(get("/admin/visitors/41/passport").with(GUARD)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/visitors/41/passport").with(DIRECTOR)).andExpect(status().isOk());
    }

    @Test
    void theApprovedAndRejectedStatesAreSavedOnTheSameReview() throws Exception {
        mvc.perform(post("/admin/passports/5/approve").with(DIRECTOR).with(csrf()));
        ArgumentCaptor<PassportReview> saved = ArgumentCaptor.forClass(PassportReview.class);
        verify(reviews).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(5L);
    }
}
