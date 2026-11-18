package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.paf.aerovisit.FixedClock;
import com.paf.aerovisit.TestImages;
import com.paf.aerovisit.TestPdfs;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.PassportReview;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.PassportReviewRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.IdentityBans;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PassportStorage;

/**
 * 🛂 Foreign advance booking, step 1: the passport submission (POST /passport/submit) and the re-upload after a
 * rejection (POST /passport/resubmit). A real PDF is stored and reviewed; a renamed image, a forged "%PDF" header and an
 * oversized file are refused by their content; the email must be verified by its code; a banned email or phone is
 * refused before anything is stored; a new upload replaces the rejected one on the same review. No database.
 */
@WebMvcTest(PassportController.class)
@Import({FixedClock.class, SecurityConfig.class, PassportStorage.class})
class PassportSubmitTest {

    private static final String EMAIL = "anna.schmidt@example.de";
    private static final String NAME = "Schmidt, Anna";
    private static final String PHONE = "+49 151 2345678";
    private static final Path UPLOADS = Paths.get("uploads");

    @Autowired private MockMvc mvc;

    @MockitoBean private PassportReviewRepository reviews;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OtpService otpService;
    @MockitoBean private BlacklistedFaceRepository banRepo;
    @MockitoBean private NotificationService notificationService;

    private final List<String> createdFiles = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(banRepo.findAll()).thenReturn(List.of());
        when(reviews.findByEmailIgnoreCaseAndStatusIn(anyString(), any())).thenReturn(List.of());
        when(reviews.save(any(PassportReview.class))).thenAnswer(inv -> {
            PassportReview r = inv.getArgument(0);
            if (r.getId() == null) r.setId(5L);
            if (r.getFileName() != null) createdFiles.add(r.getFileName());
            return r;
        });
    }

    @AfterEach
    void removeTestUploads() throws Exception {
        for (String f : createdFiles) Files.deleteIfExists(UPLOADS.resolve(f));
    }

    private static MockMultipartHttpServletRequestBuilder submit(byte[] file, String fileName) {
        MockMultipartHttpServletRequestBuilder req = multipart("/passport/submit");
        req.with(csrf());
        if (file != null) req.file(new MockMultipartFile("passportFile", fileName, "application/pdf", file));
        req.param("fullName", NAME).param("email", EMAIL).param("contactNumber", PHONE).param("termsAccepted", "true");
        return req;
    }

    private PassportReview saved() {
        ArgumentCaptor<PassportReview> captor = ArgumentCaptor.forClass(PassportReview.class);
        verify(reviews).save(captor.capture());
        return captor.getValue();
    }

    private void refusedWith(org.springframework.test.web.servlet.RequestBuilder req, String message) throws Exception {
        mvc.perform(req)
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", message))
           .andExpect(flash().attribute("passportRetry", true));
        verify(reviews, never()).save(any());
        verify(emailService, never()).sendPassportReceived(anyString(), anyString());
    }

    // --- A valid PDF ---

    @Test
    void aRealPdfIsStoredUnderARandomNameAndWaitsForReviewWithTheUnderReviewEmail() throws Exception {
        mvc.perform(submit(TestPdfs.pdf("A1"), "my passport.pdf"))
           .andExpect(redirectedUrl("/passport/submitted"))
           .andExpect(flash().attribute("passportEmail", EMAIL));

        PassportReview r = saved();
        assertThat(r.getStatus()).isEqualTo(PassportReview.PENDING);
        assertThat(r.getFullName()).isEqualTo(NAME);
        assertThat(r.getEmail()).isEqualTo(EMAIL);
        assertThat(r.getContactNumber()).isEqualTo(PHONE);
        assertThat(r.getToken()).isNull();                        // no link until the Director decides
        assertThat(r.getSubmittedAt()).isEqualTo(FixedClock.NOW);
        assertThat(r.getFileName()).matches("passport-[0-9a-f-]{36}\\.pdf").doesNotContain("my passport");
        assertThat(UPLOADS.resolve(r.getFileName())).exists();
        verify(emailService).sendPassportReceived(EMAIL, NAME);
        verify(notificationService).passportSubmitted(NAME, false);
        verify(otpService).consume(EMAIL);
    }

    // --- Content, not the name, decides ---

    @Test
    void anImageRenamedToPdfIsRefused() throws Exception {
        refusedWith(submit(TestImages.png(7), "passport.pdf"), PassportStorage.NOT_A_PDF);
    }

    @Test
    void aFileWithOnlyAForgedPdfHeaderIsRefused() throws Exception {
        refusedWith(submit(TestPdfs.fakeHeaderOnly(), "passport.pdf"), PassportStorage.NOT_A_PDF);
    }

    @Test
    void aFileOver15MbIsRefused() throws Exception {
        byte[] big = new byte[(int) PassportStorage.MAX_PASSPORT_BYTES + 1];
        System.arraycopy(TestPdfs.pdf("big"), 0, big, 0, 64);
        refusedWith(submit(big, "passport.pdf"), PassportStorage.TOO_LARGE);
    }

    @Test
    void aMissingFileIsRefused() throws Exception {
        refusedWith(submit(null, null), PassportStorage.REQUIRED);
    }

    // --- Email code, terms and the ban list first ---

    @Test
    void anUnverifiedEmailIsRefused() throws Exception {
        when(otpService.isVerified(EMAIL)).thenReturn(false);
        refusedWith(submit(TestPdfs.pdf("A2"), "p.pdf"), PassportController.EMAIL_NOT_VERIFIED);
    }

    @Test
    void withoutTheTermsConsentNothingIsStored() throws Exception {
        refusedWith(submit(TestPdfs.pdf("A3"), "p.pdf").param("termsAccepted", ""), PassportController.TERMS_REQUIRED);
    }

    @Test
    void aBannedEmailIsRefusedBeforeAnythingIsStoredAndTheBellIsTold() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(3L);
        ban.setName("Someone Else");
        ban.setEmail("ANNA.SCHMIDT@example.de");
        when(banRepo.findAll()).thenReturn(List.of(ban));
        long before = passportFilesOnDisk();

        refusedWith(submit(TestPdfs.pdf("A4"), "p.pdf"), IdentityBans.BANNED_MESSAGE);
        verify(notificationService).bannedContactAttempt(any(Visitor.class));
        verify(otpService, never()).consume(anyString());
        assertThat(passportFilesOnDisk()).isEqualTo(before); // nothing was stored
    }

    private static long passportFilesOnDisk() throws Exception {
        if (!Files.isDirectory(UPLOADS)) return 0;
        try (var files = Files.list(UPLOADS)) {
            return files.filter(p -> p.getFileName().toString().startsWith("passport-")).count();
        }
    }

    @Test
    void aBannedPhoneNumberIsRefusedToo() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(4L);
        ban.setName("Someone Else");
        ban.setPhone("+49 151 234 5678");
        when(banRepo.findAll()).thenReturn(List.of(ban));

        refusedWith(submit(TestPdfs.pdf("A5"), "p.pdf"), IdentityBans.BANNED_MESSAGE);
    }

    @Test
    void aNameResemblingABanIsOnlyFlaggedForTheDirector() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(9L);
        ban.setName("Anna Schmidt");
        when(banRepo.findAll()).thenReturn(List.of(ban));

        mvc.perform(submit(TestPdfs.pdf("A6"), "p.pdf")).andExpect(redirectedUrl("/passport/submitted"));
        assertThat(saved().getPossibleBanMatch()).isEqualTo("Ban #9: Anna Schmidt");
    }

    // --- A new upload replaces the rejected one: same row, old file gone ---

    private PassportReview rejectedReview() throws Exception {
        String oldFile = "passport-" + java.util.UUID.randomUUID() + ".pdf";
        Files.createDirectories(UPLOADS);
        Files.write(UPLOADS.resolve(oldFile), TestPdfs.pdf("old"));
        createdFiles.add(oldFile);
        PassportReview r = new PassportReview();
        r.setId(7L);
        r.setFullName(NAME);
        r.setEmail(EMAIL);
        r.setContactNumber(PHONE);
        r.setFileName(oldFile);
        r.setStatus(PassportReview.REJECTED);
        r.setRejectionReason("The photo page is cropped.");
        r.setToken("reupload-token-7");
        r.setSubmittedAt(FixedClock.NOW.minusDays(2));
        r.setDecidedAt(FixedClock.NOW.minusDays(1));
        r.setDecidedBy("director");
        when(reviews.findByToken("reupload-token-7")).thenReturn(Optional.of(r));
        return r;
    }

    @Test
    void theReuploadLinkReplacesTheRejectedFileOnTheSameReview() throws Exception {
        PassportReview r = rejectedReview();
        String oldFile = r.getFileName();

        mvc.perform(multipart("/passport/resubmit").file(new MockMultipartFile("passportFile", "new.pdf", "application/pdf", TestPdfs.pdf("new")))
                    .param("token", "reupload-token-7").with(csrf()))
           .andExpect(redirectedUrl("/passport/submitted"));

        PassportReview again = saved();
        assertThat(again).isSameAs(r);
        assertThat(again.getId()).isEqualTo(7L);                    // the same record, not a duplicate
        assertThat(again.getStatus()).isEqualTo(PassportReview.PENDING);
        assertThat(again.getFileName()).isNotEqualTo(oldFile);
        assertThat(again.getToken()).isNull();                      // the re-upload link works once
        assertThat(again.getRejectionReason()).isNull();
        assertThat(UPLOADS.resolve(again.getFileName())).exists();
        assertThat(UPLOADS.resolve(oldFile)).doesNotExist();         // the rejected copy is gone
        verify(emailService).sendPassportReceived(EMAIL, NAME);
        verify(notificationService).passportSubmitted(NAME, true);
    }

    @Test
    void submittingAgainFromTheBookingPageAlsoReplacesTheRejectedReview() throws Exception {
        PassportReview r = rejectedReview();
        String oldFile = r.getFileName();
        when(reviews.findByEmailIgnoreCaseAndStatusIn(eq(EMAIL), any())).thenReturn(List.of(r));

        mvc.perform(submit(TestPdfs.pdf("again"), "p.pdf")).andExpect(redirectedUrl("/passport/submitted"));

        assertThat(saved().getId()).isEqualTo(7L);
        assertThat(UPLOADS.resolve(oldFile)).doesNotExist();
        verify(notificationService).passportSubmitted(NAME, true);
    }

    @Test
    void theReuploadLinkRefusesANonPdfAndKeepsTheRejectedReviewAsItWas() throws Exception {
        PassportReview r = rejectedReview();
        mvc.perform(multipart("/passport/resubmit").file(new MockMultipartFile("passportFile", "x.pdf", "application/pdf", TestImages.jpeg(3)))
                    .param("token", "reupload-token-7").with(csrf()))
           .andExpect(redirectedUrl("/passport/resubmit?token=reupload-token-7"))
           .andExpect(flash().attribute("error", PassportStorage.NOT_A_PDF));
        verify(reviews, never()).save(any());
        assertThat(r.getStatus()).isEqualTo(PassportReview.REJECTED);
        assertThat(UPLOADS.resolve(r.getFileName())).exists();
    }

    @Test
    void aReuploadLinkForAReviewThatIsNotRejectedOpensNothing() throws Exception {
        PassportReview r = rejectedReview();
        r.setStatus(PassportReview.PENDING);
        mvc.perform(multipart("/passport/resubmit").file(new MockMultipartFile("passportFile", "x.pdf", "application/pdf", TestPdfs.pdf("x")))
                    .param("token", "reupload-token-7").with(csrf()))
           .andExpect(status().isOk());
        verify(reviews, never()).save(any());
        verify(notificationService, never()).passportSubmitted(anyString(), anyBoolean());
    }

    @Test
    void withoutTheCsrfTokenTheSubmissionIsForbidden() throws Exception {
        mvc.perform(multipart("/passport/submit").file(new MockMultipartFile("passportFile", "p.pdf", "application/pdf", TestPdfs.pdf("c")))
                    .param("fullName", NAME).param("email", EMAIL).param("contactNumber", PHONE).param("termsAccepted", "true"))
           .andExpect(status().isForbidden());
        verify(reviews, never()).save(any());
    }
}
