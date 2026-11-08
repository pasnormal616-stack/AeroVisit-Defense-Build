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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * 🪪 POST /admin/biometric-ban/{visitorId} (Ban Visitor): the booking's email, phone number and name go to
 * blacklisted_faces. No face data and no photo, never a copy of the ID photo. Plus the Banned Visitors page. No database.
 */
@WebMvcTest(ManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "guard", roles = "GUARD")
class BanVisitorTest {

    private static final String NEW_BAN = "🪪 Visitor banned permanently: Juan Dela Cruz. New bookings with this email or phone"
            + " number will be refused, and bookings under a similar name will be flagged for review.";

    @Autowired private MockMvc mvc;
    @Autowired private ManagementController controller;
    @TempDir java.nio.file.Path uploads;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    @BeforeEach
    void mugshotsGoToATempFolder() {
        controller.mugshotDir = uploads;
    }

    @AfterEach
    void restoreTheUploadFolder() {
        controller.mugshotDir = java.nio.file.Paths.get("uploads"); // the controller bean is shared with other cached slices
    }

    private Visitor juan() {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setName("Juan Dela Cruz");
        v.setReferenceId("PAF-ABC234");
        v.setEmail("juan@example.com");
        v.setContactNumber("09171234567");
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        return v;
    }

    private BlacklistedFace savedBan() {
        ArgumentCaptor<BlacklistedFace> ban = ArgumentCaptor.forClass(BlacklistedFace.class);
        verify(faceBanRepo).save(ban.capture());
        return ban.getValue();
    }

    private static BlacklistedFace emailBan(String email) {
        BlacklistedFace b = new BlacklistedFace();
        b.setEmail(email);
        return b;
    }

    @Test
    void recordsTheEmailPhoneAndNameWithTheGivenReason() throws Exception {
        Visitor v = juan();
        v.setEmail("  Juan.DelaCruz@Example.com ");
        v.setContactNumber("+63 917-123-4567");
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("reason", "  Used a fake ID  "))
           .andExpect(redirectedUrl("/admin"))
           .andExpect(flash().attribute("success", NEW_BAN));

        BlacklistedFace ban = savedBan();
        assertThat(ban.getEmail()).isEqualTo("juan.delacruz@example.com");
        assertThat(ban.getPhone()).isEqualTo("09171234567");
        assertThat(ban.getName()).isEqualTo("Juan Dela Cruz");
        assertThat(ban.getReason()).isEqualTo("Used a fake ID");
        assertThat(ban.getFaceEncoding()).isNull();
        assertThat(ban.getImageUrl()).isNull();
    }

    @Test
    void theIdPhotoIsNeverCopiedIntoTheBan() throws Exception {
        Visitor v = juan();
        v.setIdCardImageUrl("card-7.jpg");
        v.setIdImageUrl("photo-7.jpg");
        v.setIdFaceCropUrl("faceid-7.png");          // an older booking's face crop: not copied either
        v.setFaceEncoding("0.1,0.2");                 // an older booking's vector: not copied
        Files.write(uploads.resolve("card-7.jpg"), new byte[] {9});
        Files.write(uploads.resolve("photo-7.jpg"), new byte[] {8});
        Files.write(uploads.resolve("faceid-7.png"), new byte[] {7});
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf())).andExpect(redirectedUrl("/admin"));

        BlacklistedFace ban = savedBan();
        assertThat(ban.getImageUrl()).isNull();
        assertThat(ban.getFaceEncoding()).isNull();
        try (var files = Files.list(uploads)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder("card-7.jpg", "photo-7.jpg", "faceid-7.png");
        }
    }

    @Test
    void withoutAReasonTheBookingReferenceIsRecorded() throws Exception {
        juan();
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf())).andExpect(redirectedUrl("/admin"));

        assertThat(savedBan().getReason()).isEqualTo("Banned (booking PAF-ABC234)");
    }

    @Test
    void anOverlongReasonIsCutToTheColumnSize() throws Exception {
        juan();
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("reason", "x".repeat(400))).andExpect(redirectedUrl("/admin"));

        assertThat(savedBan().getReason()).hasSize(255);
    }

    @Test
    void aBookingWithOnlyAPhoneNumberIsBannedByIt() throws Exception {
        Visitor v = juan();
        v.setEmail(null);
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf())).andExpect(flash().attribute("success", NEW_BAN));

        assertThat(savedBan().getPhone()).isEqualTo("09171234567");
    }

    @Test
    void aBookingWithNoEmailOrPhoneCannotBeBanned() throws Exception {
        Visitor v = juan(); // e.g. anonymized by the 30-day purge
        v.setEmail(null);
        v.setContactNumber(null);

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()))
           .andExpect(redirectedUrl("/admin"))
           .andExpect(flash().attribute("error", "Nothing to ban Juan Dela Cruz by: this booking has no email or phone number"
                   + " on file (it may have been anonymized by the 30-day purge). Reject it instead."));
        verify(faceBanRepo, never()).save(any());
    }

    // --- Temporary bans: 30 days, 6 months or permanent ---

    @Test
    void aThirtyDayBanEndsThirtyDaysFromToday() throws Exception {
        juan();
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("reason", "Repeated No-Shows").param("duration", "30_DAYS"))
           .andExpect(flash().attribute("success", org.hamcrest.Matchers.startsWith("🪪 Visitor banned until ")));

        assertThat(savedBan().getExpiresAt()).isEqualTo(LocalDate.now().plusDays(30));
    }

    @Test
    void aSixMonthBanEndsSixMonthsFromToday() throws Exception {
        juan();
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("duration", "6_MONTHS")).andExpect(redirectedUrl("/admin"));

        assertThat(savedBan().getExpiresAt()).isEqualTo(LocalDate.now().plusMonths(6));
    }

    @Test
    void aPermanentBanNeverEnds() throws Exception {
        juan();
        when(faceBanRepo.findAll()).thenReturn(List.of());

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("duration", "PERMANENT")).andExpect(redirectedUrl("/admin"));

        assertThat(savedBan().getExpiresAt()).isNull();
    }

    @Test
    void anUnknownDurationIsRefused() throws Exception {
        juan();

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("duration", "FOREVER_AND_EVER"))
           .andExpect(flash().attribute("error", "Choose a ban duration: 30 days, 6 months or permanent."));
        verify(faceBanRepo, never()).save(any());
    }

    @Test
    void banningTheSameEmailAgainExtendsTheBanInsteadOfAddingARow() throws Exception {
        juan();
        BlacklistedFace existing = emailBan("juan@example.com");
        existing.setExpiresAt(LocalDate.now().plusDays(5)); // a short suspension still running
        when(faceBanRepo.findAll()).thenReturn(List.of(existing));

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("reason", "Security Threat").param("duration", "PERMANENT"))
           .andExpect(flash().attribute("success", "🪪 Juan Dela Cruz was already banned; the ban now runs permanently."));

        assertThat(savedBan()).isSameAs(existing);
        assertThat(existing.getExpiresAt()).isNull();
        assertThat(existing.getReason()).isEqualTo("Security Threat");
        assertThat(existing.getPhone()).isEqualTo("09171234567"); // filled in from the booking
    }

    @Test
    void aShorterBanNeverShortensALongerOne() throws Exception {
        juan();
        BlacklistedFace existing = emailBan("juan@example.com"); // permanent
        when(faceBanRepo.findAll()).thenReturn(List.of(existing));

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("duration", "30_DAYS")).andExpect(redirectedUrl("/admin"));

        assertThat(existing.getExpiresAt()).isNull();
    }

    @Test
    void anExpiredBanDoesNotCountSoThePersonIsBannedAfresh() throws Exception {
        juan();
        BlacklistedFace expired = emailBan("juan@example.com");
        expired.setExpiresAt(LocalDate.now().minusDays(1));
        when(faceBanRepo.findAll()).thenReturn(List.of(expired));

        mvc.perform(post("/admin/biometric-ban/7").with(csrf()).param("duration", "30_DAYS")).andExpect(redirectedUrl("/admin"));

        BlacklistedFace saved = savedBan();
        assertThat(saved).isNotSameAs(expired); // a new ban; the expired one stays as a record
        assertThat(saved.getExpiresAt()).isEqualTo(LocalDate.now().plusDays(30));
    }

    @Test
    void anOlderFaceOnlyBanIsNotTheSamePersonSoANewBanIsMade() throws Exception {
        Visitor v = juan();
        v.setFaceEncoding("0.1,0.2");
        BlacklistedFace legacy = new BlacklistedFace();
        legacy.setFaceEncoding("0.1,0.2");
        when(faceBanRepo.findAll()).thenReturn(List.of(legacy));

        mvc.perform(post("/admin/biometric-ban/7").with(csrf())).andExpect(flash().attribute("success", NEW_BAN));

        assertThat(savedBan()).isNotSameAs(legacy);
    }

    @Test
    void reBanningFillsAMissingNameButKeepsAnExistingOneAndItsOldPhoto() throws Exception {
        juan();
        BlacklistedFace noName = emailBan("juan@example.com");
        when(faceBanRepo.findAll()).thenReturn(List.of(noName));
        mvc.perform(post("/admin/biometric-ban/7").with(csrf())).andExpect(redirectedUrl("/admin"));
        assertThat(noName.getName()).isEqualTo("Juan Dela Cruz");
        assertThat(noName.getImageUrl()).isNull();

        BlacklistedFace withPhoto = emailBan("juan@example.com");
        withPhoto.setName("First Name On File");
        withPhoto.setImageUrl("ban-first.jpg");
        when(faceBanRepo.findAll()).thenReturn(List.of(withPhoto));
        mvc.perform(post("/admin/biometric-ban/7").with(csrf())).andExpect(redirectedUrl("/admin"));
        assertThat(withPhoto.getName()).isEqualTo("First Name On File");
        assertThat(withPhoto.getImageUrl()).isEqualTo("ban-first.jpg");
    }

    // --- Banned Visitors page (sidebar): newest first, masked contact details ---

    @Test
    void theBannedVisitorsPageShowsMaskedContactDetailsOnly() throws Exception {
        BlacklistedFace ban = new BlacklistedFace();
        ban.setId(4L);
        ban.setReason("Fake ID");
        ban.setName("Juan Dela Cruz");
        ban.setEmail("juan.delacruz@example.com");
        ban.setPhone("09171234567");
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("ju***@example.com", "0917***4567")
            .doesNotContain("juan.delacruz@example.com", "09171234567", "Name flag only", "face data", "AI features");
    }

    @Test
    void anOlderBanWithNoEmailOrPhoneIsLabelledAsANameFlagOnly() throws Exception {
        BlacklistedFace legacy = new BlacklistedFace();
        legacy.setId(3L);
        legacy.setReason("Fake ID");
        legacy.setName("Pedro Penduko");
        legacy.setFaceEncoding("0.1,0.2");
        when(faceBanRepo.findAll()).thenReturn(List.of(legacy));

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(card(html, 3)).contains("Name flag only (no email or phone on file)");
        assertThat(html).doesNotContain("0.1,0.2");
    }

    @Test
    void theBannedVisitorsPageListsBansNewestFirst() throws Exception {
        BlacklistedFace older = emailBan("a@example.com");
        older.setId(3L);
        older.setReason("Fake ID");
        BlacklistedFace newer = emailBan("b@example.com");
        newer.setId(9L);
        newer.setReason("Trespassing");
        when(faceBanRepo.findAll()).thenReturn(List.of(older, newer));

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Banned Visitors", "Trespassing", "Fake ID").containsSubsequence("#9", "#3");
        assertThat(html).doesNotContain("Email Address", "Block Type");
        // A Guard sees the list but can't lift a ban
        assertThat(html).contains("Director only").doesNotContain("/admin/bans/9/lift");
    }

    @Test
    void theBannedVisitorsPageShowsPermanentRunningAndExpiredBans() throws Exception {
        LocalDate today = LocalDate.now();
        BlacklistedFace permanent = emailBan("p@example.com");
        permanent.setId(1L);
        permanent.setReason("Security Threat");
        BlacklistedFace running = emailBan("r@example.com");
        running.setId(2L);
        running.setReason("Repeated No-Shows");
        running.setExpiresAt(today.plusDays(10));
        BlacklistedFace endsToday = emailBan("t@example.com");  // the last day still counts
        endsToday.setId(3L);
        endsToday.setReason("Late arrival");
        endsToday.setExpiresAt(today);
        BlacklistedFace expired = emailBan("e@example.com");
        expired.setId(4L);
        expired.setReason("Old suspension");
        expired.setExpiresAt(today.minusDays(1));
        when(faceBanRepo.findAll()).thenReturn(List.of(permanent, running, endsToday, expired));

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("(3 active)", "Until ", "Ended ", ">Expired<");
        assertThat(card(html, 1)).contains("badge-perm\">Permanent", "<dd>Never</dd>").doesNotContain("is-expired");
        assertThat(card(html, 2)).contains("badge-temp\">Temporary", "Until ").doesNotContain("is-expired");
        assertThat(card(html, 3)).contains("badge-temp\">Temporary");
        assertThat(card(html, 4)).contains("badge-expired\">Expired", "Ended ", "is-expired").doesNotContain("Temporary");
    }

    // One ban's card in the Security Gallery
    private static String card(String html, long id) {
        int at = html.indexOf("Ban #" + id);
        assertThat(at).as("card for ban #" + id).isPositive();
        return html.substring(html.lastIndexOf("<article", at), html.indexOf("</article>", at));
    }

    @Test
    void theBannedVisitorsPageShowsAnOlderBansThumbnailOrAPlaceholder() throws Exception {
        BlacklistedFace withPhoto = emailBan("x@example.com");
        withPhoto.setId(9L);
        withPhoto.setReason("Trespassing");
        withPhoto.setName("Juan <b>Dela</b> Cruz");
        withPhoto.setImageUrl("ban-1234.jpg");   // an older ban's mugshot is still shown
        BlacklistedFace noPhoto = emailBan("y@example.com");
        noPhoto.setId(3L);
        noPhoto.setReason("Fake ID");
        when(faceBanRepo.findAll()).thenReturn(List.of(noPhoto, withPhoto));

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(card(html, 9))
            .contains("href=\"/uploads/ban-1234.jpg\"", "src=\"/uploads/ban-1234.jpg\"", "Juan &lt;b&gt;Dela&lt;/b&gt; Cruz", "Trespassing")
            .contains("data-ban-name=\"juan &lt;b&gt;dela&lt;/b&gt; cruz\"")
            .doesNotContain("<b>Dela</b>");
        assertThat(card(html, 3)).contains("av-ban-photo--none", "Name not recorded", "Fake ID").doesNotContain("<img");
        assertThat(html).containsSubsequence("Ban #9", "Ban #3");
    }

    @Test
    void theGalleryHasALiveNameSearch() throws Exception {
        BlacklistedFace ban = emailBan("m@example.com");
        ban.setId(5L);
        ban.setName("Maria Clara");
        when(faceBanRepo.findAll()).thenReturn(List.of(ban));

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("id=\"banSearch\"", "for=\"banSearch\"", "id=\"banGallery\"", "id=\"banNoMatch\" hidden",
                "1 ban(s) on file", "Lifting a ban is Director only.");
        assertThat(html).doesNotContain("action=\"/admin/blacklist");
    }

    @Test
    void anEmptyBanListShowsTheEmptyStateAndNoSearch() throws Exception {
        when(faceBanRepo.findAll()).thenReturn(List.of());

        String html = mvc.perform(get("/admin/blacklist")).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("No visitors are banned.").doesNotContain("id=\"banSearch\"", "<article");
    }

    // --- Lifting ---

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
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void liftingAnOlderBanDeletesItsMugshot() throws Exception {
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
    void aGuardCannotLiftABan() throws Exception {
        mvc.perform(post("/admin/bans/9/lift").with(csrf())).andExpect(status().isForbidden());
        verify(faceBanRepo, never()).deleteById(any());
    }

    // --- Access ---

    @Test
    void anUnknownVisitorIsAnError() throws Exception {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(post("/admin/biometric-ban/99").with(csrf()))
           .andExpect(flash().attribute("error", "Visitor ID not found in database."));
        verify(faceBanRepo, never()).save(any());
    }

    @Test
    void itIsPostOnly() throws Exception {
        juan();
        mvc.perform(get("/admin/biometric-ban/7"));
        verify(faceBanRepo, never()).save(any());
    }

    @Test
    @WithAnonymousUser
    void visitorsCannotReachIt() throws Exception {
        juan();
        mvc.perform(post("/admin/biometric-ban/7").with(csrf())).andExpect(redirectedUrl("http://localhost/login"));
        verify(faceBanRepo, never()).save(any());
    }
}
