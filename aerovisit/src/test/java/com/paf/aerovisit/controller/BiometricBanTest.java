package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.FaceEncodings;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** POST /admin/biometric-ban/{visitorId}: the booking's saved face vector goes to blacklisted_faces. No database. */
@WebMvcTest(ManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "guard", roles = "GUARD")
class BiometricBanTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    private static double[] axis(int i) {
        double[] v = new double[FaceEncodings.DIMENSIONS];
        v[i] = 1.0;
        return v;
    }

    private Visitor visitorWithFace(String encoding) {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setName("Juan Dela Cruz");
        v.setReferenceId("PAF-ABC234");
        v.setFaceEncoding(encoding);
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        return v;
    }

    private BlacklistedFace savedBan() {
        ArgumentCaptor<BlacklistedFace> ban = ArgumentCaptor.forClass(BlacklistedFace.class);
        verify(faceBanRepo).save(ban.capture());
        return ban.getValue();
    }

    @Test
    void copiesTheBookingsFaceVectorWithTheGivenReason() throws Exception {
        String face = FaceEncodings.format(axis(2));
        visitorWithFace(face);
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("reason", "  Used a fake ID  "))
           .andExpect(redirectedUrl("/admin"))
           .andExpect(flash().attribute("success", "🧬 Visitor banned permanently: Juan Dela Cruz. New bookings with this face will be refused."));

        BlacklistedFace ban = savedBan();
        assertThat(ban.getFaceEncoding()).isEqualTo(face);
        assertThat(ban.getReason()).isEqualTo("Used a fake ID");
    }

    @Test
    void withoutAReasonTheBookingReferenceIsRecorded() throws Exception {
        visitorWithFace(FaceEncodings.format(axis(2)));
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf())).andExpect(redirectedUrl("/admin"));

        assertThat(savedBan().getReason()).isEqualTo("Banned (booking PAF-ABC234)");
    }

    @Test
    void anOverlongReasonIsCutToTheColumnSize() throws Exception {
        visitorWithFace(FaceEncodings.format(axis(2)));
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("reason", "x".repeat(400))).andExpect(redirectedUrl("/admin"));

        assertThat(savedBan().getReason()).hasSize(255);
    }

    // --- Temporary bans: 30 days, 6 months or permanent ---

    @Test
    void aThirtyDayBanEndsThirtyDaysFromToday() throws Exception {
        visitorWithFace(FaceEncodings.format(axis(2)));
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("reason", "Repeated No-Shows").param("duration", "30_DAYS"))
           .andExpect(flash().attribute("success", org.hamcrest.Matchers.startsWith("🧬 Visitor banned until ")));

        assertThat(savedBan().getExpiresAt()).isEqualTo(java.time.LocalDate.now().plusDays(30));
    }

    @Test
    void aSixMonthBanEndsSixMonthsFromToday() throws Exception {
        visitorWithFace(FaceEncodings.format(axis(2)));
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("duration", "6_MONTHS")).andExpect(redirectedUrl("/admin"));

        assertThat(savedBan().getExpiresAt()).isEqualTo(java.time.LocalDate.now().plusMonths(6));
    }

    @Test
    void aPermanentBanNeverEnds() throws Exception {
        visitorWithFace(FaceEncodings.format(axis(2)));
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("duration", "PERMANENT")).andExpect(redirectedUrl("/admin"));

        assertThat(savedBan().getExpiresAt()).isNull();
    }

    @Test
    void anUnknownDurationIsRefused() throws Exception {
        visitorWithFace(FaceEncodings.format(axis(2)));

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("duration", "FOREVER_AND_EVER"))
           .andExpect(flash().attribute("error", "Choose a ban duration: 30 days, 6 months or permanent."));
        verify(faceBanRepo, never()).save(any());
    }

    @Test
    void banningTheSameFaceAgainExtendsTheBanInsteadOfAddingARow() throws Exception {
        visitorWithFace(FaceEncodings.format(axis(2)));
        BlacklistedFace existing = new BlacklistedFace();
        existing.setFaceEncoding(FaceEncodings.format(axis(2)));
        existing.setExpiresAt(java.time.LocalDate.now().plusDays(5)); // a short suspension still running
        when(faceBanRepo.findAll()).thenReturn(List.of(existing));

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("reason", "Security Threat").param("duration", "PERMANENT"))
           .andExpect(flash().attribute("success", "🧬 Juan Dela Cruz was already banned; the ban now runs permanently."));

        assertThat(savedBan()).isSameAs(existing);
        assertThat(existing.getExpiresAt()).isNull();
        assertThat(existing.getReason()).isEqualTo("Security Threat");
    }

    @Test
    void aShorterBanNeverShortensALongerOne() throws Exception {
        visitorWithFace(FaceEncodings.format(axis(2)));
        BlacklistedFace existing = new BlacklistedFace();
        existing.setFaceEncoding(FaceEncodings.format(axis(2))); // permanent
        when(faceBanRepo.findAll()).thenReturn(List.of(existing));

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("duration", "30_DAYS")).andExpect(redirectedUrl("/admin"));

        assertThat(existing.getExpiresAt()).isNull();
    }

    @Test
    void anExpiredBanDoesNotCountSoTheFaceIsBannedAfresh() throws Exception {
        visitorWithFace(FaceEncodings.format(axis(2)));
        BlacklistedFace expired = new BlacklistedFace();
        expired.setFaceEncoding(FaceEncodings.format(axis(2)));
        expired.setExpiresAt(java.time.LocalDate.now().minusDays(1));
        when(faceBanRepo.findAll()).thenReturn(List.of(expired));

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("duration", "30_DAYS")).andExpect(redirectedUrl("/admin"));

        BlacklistedFace saved = savedBan();
        assertThat(saved).isNotSameAs(expired); // a new ban; the expired one stays as a record
        assertThat(saved.getExpiresAt()).isEqualTo(java.time.LocalDate.now().plusDays(30));
    }

    @Test
    void aBookingWithoutFaceDataCannotBeBanned() throws Exception {
        visitorWithFace(null);

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()))
           .andExpect(redirectedUrl("/admin"))
           .andExpect(flash().attribute("error", "No face data on file for Juan Dela Cruz (no face was found in the selfie, the booking predates face matching, or it was removed by the 30-day purge). This booking can't be banned; reject it instead."));
        verify(faceBanRepo, never()).save(any());
    }

    // --- Banned Visitors page (sidebar): the face bans, newest first; the vectors themselves are never shown ---

    @Test
    void theBannedVisitorsPageListsBansNewestFirstWithoutFaceData() throws Exception {
        BlacklistedFace older = new BlacklistedFace();
        older.setId(3L);
        older.setReason("Fake ID");
        older.setFaceEncoding(FaceEncodings.format(axis(1)));
        BlacklistedFace newer = new BlacklistedFace();
        newer.setId(9L);
        newer.setReason("Trespassing");
        newer.setFaceEncoding(FaceEncodings.format(axis(2)));
        when(faceBanRepo.findAll()).thenReturn(List.of(older, newer));

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Banned Visitors", "Trespassing", "Fake ID").containsSubsequence("#9", "#3");
        assertThat(html).doesNotContain("[0.0,", "Email Address", "Block Type");
        // A Guard sees the list but can't lift a ban
        assertThat(html).contains("Director only").doesNotContain("/admin/bans/9/lift");
    }

    @Test
    void theBannedVisitorsPageShowsPermanentRunningAndExpiredBans() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.now();
        BlacklistedFace permanent = new BlacklistedFace();
        permanent.setId(1L);
        permanent.setReason("Security Threat");
        BlacklistedFace running = new BlacklistedFace();
        running.setId(2L);
        running.setReason("Repeated No-Shows");
        running.setExpiresAt(today.plusDays(10));
        BlacklistedFace endsToday = new BlacklistedFace();  // the last day still counts
        endsToday.setId(3L);
        endsToday.setReason("Late arrival");
        endsToday.setExpiresAt(today);
        BlacklistedFace expired = new BlacklistedFace();
        expired.setId(4L);
        expired.setReason("Old suspension");
        expired.setExpiresAt(today.minusDays(1));
        when(faceBanRepo.findAll()).thenReturn(List.of(permanent, running, endsToday, expired));

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("(3 active)", "Until ", "Ended ", ">Expired<");
        assertThat(card(html, 1)).contains("badge-perm\">Permanent", "<dd>Never</dd>").doesNotContain("is-expired");
        assertThat(card(html, 2)).contains("badge-temp\">Temporary", "Until ").doesNotContain("is-expired");
        assertThat(card(html, 3)).contains("badge-temp\">Temporary");                 // the last day still counts
        assertThat(card(html, 4)).contains("badge-expired\">Expired", "Ended ", "is-expired").doesNotContain("Temporary");
    }

    // One ban's card in the Security Gallery
    private static String card(String html, long id) {
        int at = html.indexOf("Ban #" + id);
        assertThat(at).as("card for ban #" + id).isPositive();
        return html.substring(html.lastIndexOf("<article", at), html.indexOf("</article>", at));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void theDirectorCanLiftABan() throws Exception {
        when(faceBanRepo.existsById(9L)).thenReturn(true);

        mvc.perform(post("/admin/bans/9/lift").with(csrf()))
           .andExpect(redirectedUrl("/admin/blacklist"))
           .andExpect(flash().attribute("success", "Ban #9 lifted. This person can book visits again; revoked bookings stay rejected."));
        verify(faceBanRepo).deleteById(9L);
    }

    @Test
    void aGuardCannotLiftABan() throws Exception {
        mvc.perform(post("/admin/bans/9/lift").with(csrf())).andExpect(status().isForbidden());
        verify(faceBanRepo, never()).deleteById(any());
    }

    // --- Name + mugshot for the guards: copied from the booking at ban time ---

    @Autowired private ManagementController controller;
    @TempDir java.nio.file.Path uploads;

    @org.junit.jupiter.api.BeforeEach
    void mugshotsGoToATempFolder() {
        controller.mugshotDir = uploads;
    }

    @org.junit.jupiter.api.AfterEach
    void restoreTheUploadFolder() {
        controller.mugshotDir = java.nio.file.Paths.get("uploads"); // the controller bean is shared with other cached slices
    }

    @Test
    void theBanKeepsTheNameAndItsOwnCopyOfTheLivenessSelfie() throws Exception {
        Visitor v = visitorWithFace(FaceEncodings.format(axis(2)));
        v.setIdImageUrl("3f6e-selfie.png");
        v.setIdCardImageUrl("3f6e-idcard.jpg");   // the step-1 ID close-up is NOT the mugshot
        Files.write(uploads.resolve("3f6e-selfie.png"), new byte[] {1, 2, 3});
        Files.write(uploads.resolve("3f6e-idcard.jpg"), new byte[] {9});
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("duration", "30_DAYS")).andExpect(redirectedUrl("/admin"));

        BlacklistedFace ban = savedBan();
        assertThat(ban.getName()).isEqualTo("Juan Dela Cruz");
        assertThat(ban.getImageUrl()).matches("ban-[0-9a-f-]{36}\\.png");
        assertThat(Files.readAllBytes(uploads.resolve(ban.getImageUrl()))).containsExactly(1, 2, 3);
        assertThat(ban.getFaceEncoding()).isEqualTo(FaceEncodings.format(axis(2)));      // vector, duration unchanged
        assertThat(ban.getExpiresAt()).isEqualTo(java.time.LocalDate.now().plusDays(30));
        // A copy, so the 30-day purge / Delete of the booking photo never removes the mugshot
        assertThat(Files.exists(uploads.resolve("3f6e-selfie.png"))).isTrue();
    }

    @Test
    void withoutASelfieFileTheBanIsStillMadeWithoutAPhoto() throws Exception {
        Visitor v = visitorWithFace(FaceEncodings.format(axis(2)));
        v.setIdImageUrl("already-purged.jpg");
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()))
           .andExpect(flash().attribute("success", "🧬 Visitor banned permanently: Juan Dela Cruz. New bookings with this face will be refused."));

        BlacklistedFace ban = savedBan();
        assertThat(ban.getName()).isEqualTo("Juan Dela Cruz");
        assertThat(ban.getImageUrl()).isNull();
    }

    @Test
    void aSelfiePathOutsideTheUploadFolderIsNeverCopied() throws Exception {
        Files.write(uploads.resolve("inside.jpg"), new byte[] {1});
        assertThat(ManagementController.copyMugshot(uploads.resolve("sub"), "../inside.jpg")).isNull();
        assertThat(ManagementController.copyMugshot(uploads, "exhibits/x.jpg")).isNull();
        assertThat(ManagementController.copyMugshot(uploads, null)).isNull();
        assertThat(ManagementController.copyMugshot(uploads, "inside.jpg")).startsWith("ban-").endsWith(".jpg");
    }

    @Test
    void reBanningFillsAMissingNameAndPhotoButKeepsExistingOnes() throws Exception {
        Visitor v = visitorWithFace(FaceEncodings.format(axis(2)));
        v.setIdImageUrl("selfie.jpg");
        Files.write(uploads.resolve("selfie.jpg"), new byte[] {5});
        BlacklistedFace legacy = new BlacklistedFace();               // made before the name/photo columns existed
        legacy.setFaceEncoding(FaceEncodings.format(axis(2)));
        when(faceBanRepo.findAll()).thenReturn(List.of(legacy));

        mvc.perform(post("/admin/biometric-ban/7").with(csrf())).andExpect(redirectedUrl("/admin"));
        assertThat(legacy.getName()).isEqualTo("Juan Dela Cruz");
        assertThat(legacy.getImageUrl()).startsWith("ban-");

        BlacklistedFace withPhoto = new BlacklistedFace();
        withPhoto.setFaceEncoding(FaceEncodings.format(axis(2)));
        withPhoto.setName("First Name On File");
        withPhoto.setImageUrl("ban-first.jpg");
        when(faceBanRepo.findAll()).thenReturn(List.of(withPhoto));

        mvc.perform(post("/admin/biometric-ban/7").with(csrf())).andExpect(redirectedUrl("/admin"));
        assertThat(withPhoto.getName()).isEqualTo("First Name On File");
        assertThat(withPhoto.getImageUrl()).isEqualTo("ban-first.jpg");
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void liftingABanDeletesItsMugshot() throws Exception {
        Files.write(uploads.resolve("ban-abc.jpg"), new byte[] {1});
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(9L);
        ban.setImageUrl("ban-abc.jpg");
        when(faceBanRepo.existsById(9L)).thenReturn(true);
        when(faceBanRepo.findById(9L)).thenReturn(Optional.of(ban));

        mvc.perform(post("/admin/bans/9/lift").with(csrf())).andExpect(redirectedUrl("/admin/blacklist"));

        verify(faceBanRepo).deleteById(9L);
        assertThat(Files.exists(uploads.resolve("ban-abc.jpg"))).isFalse();
    }

    @Test
    void theBannedVisitorsPageShowsNameAndThumbnailOrAPlaceholder() throws Exception {
        BlacklistedFace withPhoto = new BlacklistedFace();
        withPhoto.setId(9L);
        withPhoto.setReason("Trespassing");
        withPhoto.setName("Juan <b>Dela</b> Cruz");
        withPhoto.setImageUrl("ban-1234.jpg");
        BlacklistedFace legacy = new BlacklistedFace();
        legacy.setId(3L);
        legacy.setReason("Fake ID");
        when(faceBanRepo.findAll()).thenReturn(List.of(legacy, withPhoto));

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(card(html, 9))
            .contains("href=\"/uploads/ban-1234.jpg\"", "src=\"/uploads/ban-1234.jpg\"", "Juan &lt;b&gt;Dela&lt;/b&gt; Cruz", "Trespassing")
            .contains("data-ban-name=\"juan &lt;b&gt;dela&lt;/b&gt; cruz\"")   // lower-cased for the live search, still escaped
            .doesNotContain("<b>Dela</b>");
        assertThat(card(html, 3)).contains("av-ban-photo--none", "Name not recorded", "Fake ID").doesNotContain("<img");
        assertThat(html).containsSubsequence("Ban #9", "Ban #3");                     // newest first
    }

    @Test
    void theGalleryHasALiveNameSearch() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(5L);
        ban.setName("Maria Clara");
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"banSearch\"", "for=\"banSearch\"", "id=\"banGallery\"", "id=\"banNoMatch\" hidden",
                "1 ban(s) on file", "Lifting a ban is Director only.");
        // Purely client-side: no search request goes to the server
        assertThat(html).doesNotContain("action=\"/admin/blacklist");
    }

    @Test
    void anEmptyBanListShowsTheEmptyStateAndNoSearch() throws Exception {
        when(faceBanRepo.findAll()).thenReturn(List.of());

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("No visitors are banned.").doesNotContain("id=\"banSearch\"", "<article");
    }

    @Test
    void anUnknownVisitorIsAnError() throws Exception {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(post("/admin/biometric-ban/99").with(csrf()))
           .andExpect(flash().attribute("error", "Visitor ID not found in database."));
        verify(faceBanRepo, never()).save(any());
    }

    @Test
    void itIsPostOnly() throws Exception {
        visitorWithFace(FaceEncodings.format(axis(2)));
        mvc.perform(get("/admin/biometric-ban/7"));
        verify(faceBanRepo, never()).save(any());
    }

    @Test
    @WithAnonymousUser
    void visitorsCannotReachIt() throws Exception {
        visitorWithFace(FaceEncodings.format(axis(2)));
        mvc.perform(post("/admin/biometric-ban/7").with(csrf())).andExpect(redirectedUrl("http://localhost/login"));
        verify(faceBanRepo, never()).save(any());
    }
}
