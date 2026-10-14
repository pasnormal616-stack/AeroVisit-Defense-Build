package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.Announcement;
import com.paf.aerovisit.model.Guideline;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.repository.AnnouncementRepository;
import com.paf.aerovisit.repository.ExhibitPhotoRepository;
import com.paf.aerovisit.repository.GuidelineRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.service.ExhibitPhotoStorage;

/** Director's Website Content page (announcements, guidelines, exhibit photos). No database: repositories are mocked. */
@WebMvcTest(ContentManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class ContentManagementControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private AnnouncementRepository announcementRepo;
    @MockitoBean private GuidelineRepository guidelineRepo;
    @MockitoBean private ExhibitPhotoRepository photoRepo;
    @MockitoBean private ExhibitPhotoStorage photoStorage;
    @MockitoBean private PortalSettingsRepository settingsRepo;

    // --- Promotional Video Tour ---

    @Test
    void anyYouTubeLinkIsSavedAsAnEmbedLink() throws Exception {
        String[] links = {
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42s",
            " https://youtu.be/dQw4w9WgXcQ?si=abc ",
            "youtube.com/shorts/dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ?rel=0"};
        for (String link : links) {
            mvc.perform(post("/admin/content/video/save").with(csrf()).param("promoVideoUrl", link))
                .andExpect(redirectedUrl("/admin/content#announcements"))
                .andExpect(flash().attributeExists("success"));
        }
        ArgumentCaptor<PortalSettings> saved = ArgumentCaptor.forClass(PortalSettings.class);
        verify(settingsRepo, times(links.length)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(s -> {
            assertThat(s.getId()).isEqualTo(1L);
            assertThat(s.getPromoVideoUrl()).isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ");
        });
    }

    @Test
    void linksThatAreNotYouTubeVideosAreRejected() throws Exception {
        String[] links = {
            "javascript:alert(1)",
            "https://evil.example/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com.evil.example/embed/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ\"><script>alert(1)</script>",
            "https://www.youtube.com/watch?v=short",
            "https://www.youtube.com/@pafmuseum",
            "just some text"};
        for (String link : links) {
            mvc.perform(post("/admin/content/video/save").with(csrf()).param("promoVideoUrl", link))
                .andExpect(redirectedUrl("/admin/content#announcements"))
                .andExpect(flash().attributeExists("error"));
        }
        verify(settingsRepo, never()).save(any());
    }

    @Test
    void aBlankLinkRemovesTheVideoAndKeepsTheSameRow() throws Exception {
        PortalSettings existing = new PortalSettings();
        existing.setPromoVideoUrl("https://www.youtube.com/embed/dQw4w9WgXcQ");
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(existing));
        mvc.perform(post("/admin/content/video/save").with(csrf()).param("promoVideoUrl", "   "))
            .andExpect(flash().attribute("success", "Promotional video removed from the visitor portal."));
        verify(settingsRepo).save(existing);
        assertThat(existing.getPromoVideoUrl()).isNull();
    }

    @Test
    void pageShowsTheSavedVideoLinkInTheForm() throws Exception {
        PortalSettings existing = new PortalSettings();
        existing.setPromoVideoUrl("https://www.youtube.com/embed/dQw4w9WgXcQ");
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(existing));
        mvc.perform(get("/admin/content"))
            .andExpect(content().string(Matchers.containsString("action=\"/admin/content/video/save\"")))
            .andExpect(content().string(Matchers.containsString("value=\"https://www.youtube.com/embed/dQw4w9WgXcQ\"")));
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardCannotChangeTheVideo() throws Exception {
        mvc.perform(post("/admin/content/video/save").with(csrf()).param("promoVideoUrl", "https://youtu.be/dQw4w9WgXcQ"))
            .andExpect(status().isForbidden());
        verify(settingsRepo, never()).save(any());
    }

    private static Announcement announcement(Long id, String title, boolean active) {
        Announcement a = new Announcement(title, "Details", LocalDate.of(2026, 12, 1), active);
        a.setId(id);
        return a;
    }

    // --- Page ---

    @Test
    void pageListsAnnouncementsAndGuidelines() throws Exception {
        when(announcementRepo.findAllByOrderByAnnouncementDateDescIdDesc()).thenReturn(List.of(announcement(1L, "Closed on Dec 25", true)));
        when(guidelineRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(guideline(1L, "Dress Code", 1)));
        mvc.perform(get("/admin/content"))
            .andExpect(status().isOk())
            .andExpect(view().name("content"))
            .andExpect(model().attribute("guidelineIcons", Guideline.ICONS))
            .andExpect(content().string(Matchers.containsString("Dress Code")))
            .andExpect(content().string(Matchers.containsString("<option value=\"bi-camera\">Photography</option>")))
            .andExpect(content().string(Matchers.containsString("Closed on Dec 25")))
            .andExpect(content().string(Matchers.containsString("Website Content")));
    }

    // --- Announcements ---

    @Test
    void directorCanPostAnAnnouncement() throws Exception {
        mvc.perform(post("/admin/content/announcements/add").with(csrf())
                .param("title", "  Museum closed on Dec 25 ")
                .param("message", " Merry Christmas!\nWe reopen Dec 26. ")
                .param("announcementDate", "2026-12-20")
                .param("active", "true"))
            .andExpect(redirectedUrl("/admin/content#announcements"))
            .andExpect(flash().attribute("success", "Announcement posted: Museum closed on Dec 25"));
        ArgumentCaptor<Announcement> saved = ArgumentCaptor.forClass(Announcement.class);
        verify(announcementRepo).save(saved.capture());
        assertThat(saved.getValue().getTitle()).isEqualTo("Museum closed on Dec 25");
        assertThat(saved.getValue().getMessage()).isEqualTo("Merry Christmas!\nWe reopen Dec 26.");
        assertThat(saved.getValue().getAnnouncementDate()).isEqualTo(LocalDate.of(2026, 12, 20));
        assertThat(saved.getValue().isActive()).isTrue();
    }

    @Test
    void uncheckedActiveBoxPostsAHiddenAnnouncement() throws Exception {
        mvc.perform(post("/admin/content/announcements/add").with(csrf()).param("title", "Draft").param("message", "Later"))
            .andExpect(flash().attribute("success", "Announcement posted: Draft (hidden until you switch it on)"));
        ArgumentCaptor<Announcement> saved = ArgumentCaptor.forClass(Announcement.class);
        verify(announcementRepo).save(saved.capture());
        assertThat(saved.getValue().isActive()).isFalse();
        assertThat(saved.getValue().getAnnouncementDate()).isEqualTo(LocalDate.now());
    }

    @Test
    void announcementNeedsATitleAndMessage() throws Exception {
        mvc.perform(post("/admin/content/announcements/add").with(csrf()).param("title", "   ").param("message", "Hello"))
            .andExpect(flash().attribute("error", "Announcement title is required (120 characters max)."));
        mvc.perform(post("/admin/content/announcements/add").with(csrf()).param("title", "Hello").param("message", "x".repeat(2001)))
            .andExpect(flash().attribute("error", "Announcement message is required (2,000 characters max)."));
        mvc.perform(post("/admin/content/announcements/add").with(csrf()).param("title", "Hello").param("message", "Hi").param("announcementDate", "not-a-date"))
            .andExpect(flash().attribute("error", "Please enter a valid announcement date."));
        verify(announcementRepo, never()).save(any());
    }

    @Test
    void directorCanEditAnAnnouncement() throws Exception {
        Announcement a = announcement(3L, "Old title", true);
        when(announcementRepo.findById(3L)).thenReturn(Optional.of(a));
        mvc.perform(post("/admin/content/announcements/3/update").with(csrf())
                .param("title", "New title").param("message", "New message").param("announcementDate", "2026-11-30"))
            .andExpect(redirectedUrl("/admin/content#announcements"));
        assertThat(a.getTitle()).isEqualTo("New title");
        assertThat(a.isActive()).isFalse(); // box left unchecked
        verify(announcementRepo).save(a);
    }

    @Test
    void editingAMissingAnnouncementShowsAnError() throws Exception {
        when(announcementRepo.findById(9L)).thenReturn(Optional.empty());
        mvc.perform(post("/admin/content/announcements/9/update").with(csrf()).param("title", "T").param("message", "M"))
            .andExpect(flash().attribute("error", "That announcement no longer exists."));
        verify(announcementRepo, never()).save(any());
    }

    @Test
    void directorCanToggleAnAnnouncement() throws Exception {
        Announcement a = announcement(4L, "Air show", true);
        when(announcementRepo.findById(4L)).thenReturn(Optional.of(a));
        mvc.perform(post("/admin/content/announcements/4/toggle").with(csrf()))
            .andExpect(flash().attribute("success", "Announcement hidden from the visitor portal: Air show"));
        assertThat(a.isActive()).isFalse();
        mvc.perform(post("/admin/content/announcements/4/toggle").with(csrf()))
            .andExpect(flash().attribute("success", "Announcement is now live on the visitor portal: Air show"));
        assertThat(a.isActive()).isTrue();
        verify(announcementRepo, times(2)).save(a);
    }

    @Test
    void directorCanDeleteAnAnnouncement() throws Exception {
        when(announcementRepo.existsById(5L)).thenReturn(true);
        mvc.perform(post("/admin/content/announcements/5/delete").with(csrf()))
            .andExpect(redirectedUrl("/admin/content#announcements"));
        verify(announcementRepo).deleteById(5L);
    }

    // --- Visitor Guidelines ---

    private static Guideline guideline(Long id, String title, int order) {
        Guideline g = new Guideline("bi-shop", title, "Details", order);
        g.setId(id);
        return g;
    }

    @Test
    void directorCanAddAGuidelineAtTheEnd() throws Exception {
        when(guidelineRepo.findAllByOrderByDisplayOrderAscIdAsc())
            .thenReturn(List.of(guideline(1L, "Identification", 1), guideline(2L, "Dress Code", 2)));
        mvc.perform(post("/admin/content/guidelines/add").with(csrf())
                .param("icon", "bi-camera")
                .param("title", "  Photography ")
                .param("description", " Photos are welcome.\nNo flash inside the hangars. "))
            .andExpect(redirectedUrl("/admin/content#guidelines"))
            .andExpect(flash().attribute("success", "Guideline added: Photography"));
        ArgumentCaptor<Guideline> saved = ArgumentCaptor.forClass(Guideline.class);
        verify(guidelineRepo).save(saved.capture());
        assertThat(saved.getValue().getIcon()).isEqualTo("bi-camera");
        assertThat(saved.getValue().getTitle()).isEqualTo("Photography");
        assertThat(saved.getValue().getDescription()).isEqualTo("Photos are welcome.\nNo flash inside the hangars.");
        assertThat(saved.getValue().getDisplayOrder()).isEqualTo(3);
    }

    @Test
    void firstGuidelineGetsOrderOne() throws Exception {
        mvc.perform(post("/admin/content/guidelines/add").with(csrf()).param("icon", "bi-clock").param("title", "Hours").param("description", "See the calendar."));
        ArgumentCaptor<Guideline> saved = ArgumentCaptor.forClass(Guideline.class);
        verify(guidelineRepo).save(saved.capture());
        assertThat(saved.getValue().getDisplayOrder()).isEqualTo(1);
    }

    @Test
    void guidelineIconMustComeFromTheList() throws Exception {
        mvc.perform(post("/admin/content/guidelines/add").with(csrf())
                .param("icon", "bi-x\" onmouseover=\"alert(1)").param("title", "T").param("description", "D"))
            .andExpect(flash().attribute("error", "Please choose an icon from the list."));
        verify(guidelineRepo, never()).save(any());
    }

    @Test
    void guidelineNeedsATitleAndDescription() throws Exception {
        mvc.perform(post("/admin/content/guidelines/add").with(csrf()).param("icon", "bi-shop").param("title", " ").param("description", "D"))
            .andExpect(flash().attribute("error", "Guideline title is required (80 characters max)."));
        mvc.perform(post("/admin/content/guidelines/add").with(csrf()).param("icon", "bi-shop").param("title", "T").param("description", "x".repeat(1001)))
            .andExpect(flash().attribute("error", "Guideline description is required (1,000 characters max)."));
        verify(guidelineRepo, never()).save(any());
    }

    @Test
    void directorCanEditAGuidelineWithoutChangingItsPlace() throws Exception {
        Guideline g = guideline(2L, "Dress Code", 2);
        when(guidelineRepo.findById(2L)).thenReturn(Optional.of(g));
        mvc.perform(post("/admin/content/guidelines/2/update").with(csrf())
                .param("icon", "bi-bag-x").param("title", "Bags").param("description", "No large backpacks."))
            .andExpect(redirectedUrl("/admin/content#guidelines"))
            .andExpect(flash().attribute("success", "Guideline updated: Bags"));
        assertThat(g.getIcon()).isEqualTo("bi-bag-x");
        assertThat(g.getTitle()).isEqualTo("Bags");
        assertThat(g.getDisplayOrder()).isEqualTo(2);
        verify(guidelineRepo).save(g);
    }

    @Test
    void editingAMissingGuidelineShowsAnError() throws Exception {
        when(guidelineRepo.findById(9L)).thenReturn(Optional.empty());
        mvc.perform(post("/admin/content/guidelines/9/update").with(csrf()).param("icon", "bi-shop").param("title", "T").param("description", "D"))
            .andExpect(flash().attribute("error", "That guideline no longer exists."));
        verify(guidelineRepo, never()).save(any());
    }

    @Test
    void directorCanDeleteAGuideline() throws Exception {
        when(guidelineRepo.existsById(2L)).thenReturn(true);
        mvc.perform(post("/admin/content/guidelines/2/delete").with(csrf()))
            .andExpect(redirectedUrl("/admin/content#guidelines"))
            .andExpect(flash().attribute("success", "Guideline deleted."));
        verify(guidelineRepo).deleteById(2L);
    }

    @Test
    void movingAGuidelineSwapsItWithItsNeighbourAndRenumbers() throws Exception {
        Guideline a = guideline(1L, "A", 1);
        Guideline b = guideline(2L, "B", 5);
        Guideline c = guideline(3L, "C", 9);
        when(guidelineRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(a, b, c));

        mvc.perform(post("/admin/content/guidelines/3/move").with(csrf()).param("direction", "up"))
            .andExpect(redirectedUrl("/admin/content#guidelines"));
        assertThat(List.of(a.getDisplayOrder(), c.getDisplayOrder(), b.getDisplayOrder())).containsExactly(1, 2, 3);
    }

    @Test
    void movingTheFirstGuidelineUpChangesNothing() throws Exception {
        Guideline a = guideline(1L, "A", 1);
        when(guidelineRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(a, guideline(2L, "B", 2)));
        mvc.perform(post("/admin/content/guidelines/1/move").with(csrf()).param("direction", "up"))
            .andExpect(redirectedUrl("/admin/content#guidelines"));
        verify(guidelineRepo, never()).saveAll(any());
    }

    // --- Exhibit photos ---

    @Test
    void directorCanUploadSeveralPhotos() throws Exception {
        MockMultipartFile one = new MockMultipartFile("photos", "hangar.jpg", "image/jpeg", new byte[] {1});
        MockMultipartFile two = new MockMultipartFile("photos", "f5.png", "image/png", new byte[] {2});
        mvc.perform(multipart("/admin/content/photos/upload").file(one).file(two).with(csrf()))
            .andExpect(redirectedUrl("/admin/content#photos"))
            .andExpect(flash().attribute("success", "2 photos added to the Exhibits slideshow."));
        verify(photoStorage, times(2)).store(any());
    }

    @Test
    void rejectedPhotosAreReportedAndTheRestStillUpload() throws Exception {
        MockMultipartFile good = new MockMultipartFile("photos", "hangar.jpg", "image/jpeg", new byte[] {1});
        MockMultipartFile bad = new MockMultipartFile("photos", "shell.php.jpg", "image/jpeg", new byte[] {2});
        when(photoStorage.store(any())).thenAnswer(inv -> {
            if ("shell.php.jpg".equals(((org.springframework.web.multipart.MultipartFile) inv.getArgument(0)).getOriginalFilename())) {
                throw new ExhibitPhotoStorage.InvalidImageException("\"shell.php.jpg\" is not a valid image file.");
            }
            return null;
        });
        mvc.perform(multipart("/admin/content/photos/upload").file(good).file(bad).with(csrf()))
            .andExpect(flash().attribute("success", "1 photo added to the Exhibits slideshow."))
            .andExpect(flash().attribute("error", "Not uploaded: \"shell.php.jpg\" is not a valid image file."));
    }

    @Test
    void uploadWithoutAFileShowsAnError() throws Exception {
        mvc.perform(multipart("/admin/content/photos/upload").with(csrf()))
            .andExpect(flash().attribute("error", "Please choose at least one photo to upload."));
        verify(photoStorage, never()).store(any());
    }

    @Test
    void directorCanDeleteAPhoto() throws Exception {
        when(photoStorage.delete(8L)).thenReturn(true);
        mvc.perform(post("/admin/content/photos/8/delete").with(csrf()))
            .andExpect(redirectedUrl("/admin/content#photos"))
            .andExpect(flash().attribute("success", "Photo removed from the Exhibits slideshow."));
    }

    // --- Access control ---

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardsCannotOpenOrChangeWebsiteContent() throws Exception {
        mvc.perform(get("/admin/content")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/content/announcements/add").with(csrf()).param("title", "T").param("message", "M")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/content/guidelines/add").with(csrf()).param("icon", "bi-shop").param("title", "T").param("description", "D")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/content/guidelines/1/delete").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/admin/content/photos/upload")
                .file(new MockMultipartFile("photos", "a.jpg", "image/jpeg", new byte[] {1})).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/content/photos/1/delete").with(csrf())).andExpect(status().isForbidden());
        verify(announcementRepo, never()).save(any());
        verify(guidelineRepo, never()).save(any());
        verify(guidelineRepo, never()).deleteById(any());
        verify(photoStorage, never()).store(any());
        verify(photoStorage, never()).delete(any());
    }

    @Test
    @WithAnonymousUser
    void visitorsAreSentToLogin() throws Exception {
        mvc.perform(get("/admin/content")).andExpect(redirectedUrlPattern("**/login"));
        mvc.perform(post("/admin/content/announcements/1/delete").with(csrf())).andExpect(redirectedUrlPattern("**/login"));
        verify(announcementRepo, never()).deleteById(any());
    }

    // --- Official Social Media (footer icons) ---

    @Test
    void socialLinksAreSavedAsCleanHttpsLinks() throws Exception {
        PortalSettings existing = new PortalSettings();
        existing.setPromoVideoUrl("https://www.youtube.com/embed/dQw4w9WgXcQ");
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(existing));
        mvc.perform(post("/admin/content/social/save").with(csrf())
                .param("facebookUrl", " facebook.com/PAFAerospaceMuseum ")
                .param("instagramUrl", "http://www.instagram.com/paf_museum/#top")
                .param("xUrl", "https://twitter.com/PAF_Museum?lang=en"))
            .andExpect(redirectedUrl("/admin/content#announcements"))
            .andExpect(flash().attribute("success", "Contact & social links saved. Only the filled-in ones appear in the visitor portal footer."));
        verify(settingsRepo).save(existing);
        assertThat(existing.getFacebookUrl()).isEqualTo("https://facebook.com/PAFAerospaceMuseum");
        assertThat(existing.getInstagramUrl()).isEqualTo("https://www.instagram.com/paf_museum/");
        assertThat(existing.getXUrl()).isEqualTo("https://twitter.com/PAF_Museum?lang=en");
        assertThat(existing.getPromoVideoUrl()).isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ"); // same row, video untouched
    }

    @Test
    void blankSocialFieldsHideThoseIcons() throws Exception {
        PortalSettings existing = new PortalSettings();
        existing.setFacebookUrl("https://facebook.com/old");
        existing.setXUrl("https://x.com/old");
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(existing));
        mvc.perform(post("/admin/content/social/save").with(csrf()).param("facebookUrl", "https://facebook.com/new").param("instagramUrl", "").param("xUrl", "   "))
            .andExpect(flash().attributeExists("success"));
        assertThat(existing.getFacebookUrl()).isEqualTo("https://facebook.com/new");
        assertThat(existing.getInstagramUrl()).isNull();
        assertThat(existing.getXUrl()).isNull();

        mvc.perform(post("/admin/content/social/save").with(csrf()))
            .andExpect(flash().attribute("success", "Contact email and social links cleared. The visitor portal footer shows neither."));
        assertThat(existing.socialLinks()).isEmpty();
    }

    @Test
    void aWrongSocialLinkSavesNothing() throws Exception {
        mvc.perform(post("/admin/content/social/save").with(csrf())
                .param("facebookUrl", "https://facebook.com/PAFAerospaceMuseum")
                .param("instagramUrl", "javascript:alert(1)"))
            .andExpect(redirectedUrl("/admin/content#announcements"))
            .andExpect(flash().attribute("error", "Contact & social links not saved. Check: Instagram (an instagram.com profile link)."));
        verify(settingsRepo, never()).save(any());
    }

    @Test
    void socialUrlOnlyAcceptsPagesOnTheRightSite() {
        assertThat(ContentManagementController.socialUrl("m.facebook.com/PAF", "facebook.com")).isEqualTo("https://m.facebook.com/PAF");
        assertThat(ContentManagementController.socialUrl(null, "facebook.com")).isEmpty();
        String[] rejected = {
            "javascript:alert(1)", "https://facebook.com.evil.ph/PAF", "https://evilfacebook.com/PAF", "https://instagram.com/PAF",
            "https://user@facebook.com/PAF", "https://facebook.com:8443/PAF", "https://facebook.com/", "facebook.com",
            "https://facebook.com/a b", "data:text/html,hi", "https://facebook.com/" + "x".repeat(300)};
        for (String link : rejected) {
            assertThat(ContentManagementController.socialUrl(link, "facebook.com")).as(link).isNull();
        }
    }

    @Test
    void pageShowsTheSavedSocialLinksInTheForm() throws Exception {
        PortalSettings existing = new PortalSettings();
        existing.setInstagramUrl("https://www.instagram.com/paf_museum");
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(existing));
        mvc.perform(get("/admin/content"))
            .andExpect(content().string(Matchers.containsString("action=\"/admin/content/social/save\"")))
            .andExpect(content().string(Matchers.containsString("value=\"https://www.instagram.com/paf_museum\"")));
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardCannotChangeSocialLinks() throws Exception {
        mvc.perform(post("/admin/content/social/save").with(csrf()).param("facebookUrl", "https://facebook.com/x"))
            .andExpect(status().isForbidden());
        verify(settingsRepo, never()).save(any());
    }

    // --- Contact email (footer) ---

    @Test
    void contactEmailIsSavedTrimmedWithTheSocialLinks() throws Exception {
        PortalSettings existing = new PortalSettings();
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(existing));
        mvc.perform(post("/admin/content/social/save").with(csrf()).param("contactEmail", "  paf.museum@paf.mil.ph ").param("facebookUrl", ""))
            .andExpect(flash().attribute("success", "Contact & social links saved. Only the filled-in ones appear in the visitor portal footer."));
        verify(settingsRepo).save(existing);
        assertThat(existing.getContactEmail()).isEqualTo("paf.museum@paf.mil.ph");
        assertThat(existing.socialLinks()).isEmpty();

        mvc.perform(post("/admin/content/social/save").with(csrf()).param("contactEmail", "   "));
        assertThat(existing.getContactEmail()).isNull();
    }

    @Test
    void aWrongContactEmailSavesNothing() throws Exception {
        mvc.perform(post("/admin/content/social/save").with(csrf()).param("contactEmail", "museum at paf dot mil").param("xUrl", "https://x.com/PAF_Museum"))
            .andExpect(redirectedUrl("/admin/content#announcements"))
            .andExpect(flash().attribute("error", "Contact & social links not saved. Check: Contact email (an address like museum@paf.mil.ph)."));
        verify(settingsRepo, never()).save(any());
    }

    @Test
    void contactEmailMustBeOnePlainAddress() {
        assertThat(ContentManagementController.contactEmail("info@paf-museum.gov.ph")).isEqualTo("info@paf-museum.gov.ph");
        assertThat(ContentManagementController.contactEmail(null)).isEmpty();
        String[] rejected = {"museum@paf", "museum.paf.mil.ph", "@paf.mil.ph", "a b@paf.mil.ph", "museum@paf.mil.ph?cc=x@evil.ph",
            "museum@paf.mil.ph,other@evil.ph", "mailto:museum@paf.mil.ph", "<b>@x.ph", "x@" + "a".repeat(150) + ".ph"};
        for (String email : rejected) {
            assertThat(ContentManagementController.contactEmail(email)).as(email).isNull();
        }
    }

    @Test
    void pageShowsTheSavedContactEmailFirstInTheRenamedPanel() throws Exception {
        PortalSettings existing = new PortalSettings();
        existing.setContactEmail("paf.museum@paf.mil.ph");
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(existing));
        mvc.perform(get("/admin/content"))
            .andExpect(content().string(Matchers.stringContainsInOrder("Contact &amp; Social Media",
                "type=\"email\" id=\"contactEmail\" name=\"contactEmail\"", "value=\"paf.museum@paf.mil.ph\"", "id=\"facebookUrl\"")));
    }

    // --- Contact phone + its note (footer) ---

    @Test
    void contactPhoneAndNoteAreSavedTrimmedWithTheSocialLinks() throws Exception {
        PortalSettings existing = new PortalSettings();
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(existing));
        mvc.perform(post("/admin/content/social/save").with(csrf())
                .param("contactPhone", "  (02)  8854-6711 ")
                .param("contactPhoneNote", "  For official inquiries only.\n Prank calls are logged. "))
            .andExpect(flash().attribute("success", "Contact & social links saved. Only the filled-in ones appear in the visitor portal footer."));
        verify(settingsRepo).save(existing);
        assertThat(existing.getContactPhone()).isEqualTo("(02) 8854-6711");
        assertThat(existing.getContactPhoneNote()).isEqualTo("For official inquiries only. Prank calls are logged.");

        mvc.perform(post("/admin/content/social/save").with(csrf()).param("contactPhone", " ").param("contactPhoneNote", ""))
            .andExpect(flash().attribute("success", "Contact email and social links cleared. The visitor portal footer shows neither."));
        assertThat(existing.getContactPhone()).isNull();
        assertThat(existing.getContactPhoneNote()).isNull();
    }

    @Test
    void aWrongContactPhoneOrALongNoteSavesNothing() throws Exception {
        mvc.perform(post("/admin/content/social/save").with(csrf()).param("contactPhone", "call 8854-6711"))
            .andExpect(redirectedUrl("/admin/content#announcements"))
            .andExpect(flash().attribute("error", "Contact & social links not saved. Check: Contact phone (digits with + ( ) - . or spaces, 7 to 15 digits)."));
        mvc.perform(post("/admin/content/social/save").with(csrf()).param("contactPhone", "8854-6711").param("contactPhoneNote", "x".repeat(161)))
            .andExpect(flash().attribute("error", "Contact & social links not saved. Check: Phone note (160 characters max)."));
        verify(settingsRepo, never()).save(any());
    }

    @Test
    void contactPhoneMustBeAPlainNumber() {
        assertThat(ContentManagementController.contactPhone("+63 917 123 4567")).isEqualTo("+63 917 123 4567");
        assertThat(ContentManagementController.contactPhone("(02) 8854.6711")).isEqualTo("(02) 8854.6711");
        assertThat(ContentManagementController.contactPhone(null)).isEmpty();
        String[] rejected = {"123456", "1234567890123456", "tel:88546711", "8854-6711 loc 2", "63+917 123 4567", "<b>8854-6711</b>",
            "javascript:alert(1)", "(0)-(2)-(8)-(8)-(5)-(4)-(6)-(7)"}; // last: 8 digits but 31 characters
        for (String phone : rejected) {
            assertThat(ContentManagementController.contactPhone(phone)).as(phone).isNull();
        }
    }

    @Test
    void pageShowsTheSavedPhoneAndNoteUnderTheContactEmail() throws Exception {
        PortalSettings existing = new PortalSettings();
        existing.setContactPhone("(02) 8854-6711");
        existing.setContactPhoneNote("For official inquiries only.");
        when(settingsRepo.findById(1L)).thenReturn(Optional.of(existing));
        mvc.perform(get("/admin/content"))
            .andExpect(content().string(Matchers.stringContainsInOrder("id=\"contactEmail\"",
                "type=\"tel\" id=\"contactPhone\" name=\"contactPhone\"", "value=\"(02) 8854-6711\"",
                "id=\"contactPhoneNote\" name=\"contactPhoneNote\"", "value=\"For official inquiries only.\"", "id=\"facebookUrl\"")));
    }
}
