package com.paf.aerovisit.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.repository.AnnouncementRepository;
import com.paf.aerovisit.repository.ExhibitPhotoRepository;
import com.paf.aerovisit.repository.GuidelineRepository;
import com.paf.aerovisit.repository.HeroSlideRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.service.ExhibitPhotoStorage;
import com.paf.aerovisit.service.NewsService;
import com.paf.aerovisit.service.NewsService.PhotoView;
import com.paf.aerovisit.service.NewsService.PostView;

/** Website Content > News tab: the list of posts with New post, Edit, Publish/Unpublish, Pin/Unpin and Delete. No database. */
@WebMvcTest(ContentManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class ContentNewsTabTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private AnnouncementRepository announcementRepo;
    @MockitoBean private GuidelineRepository guidelineRepo;
    @MockitoBean private ExhibitPhotoRepository photoRepo;
    @MockitoBean private ExhibitPhotoStorage photoStorage;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private HeroSlideRepository heroRepo;
    @MockitoBean private NewsService newsService;

    @Test
    void theNewsTabListsThePostsNewestFirstWithTheirActions() throws Exception {
        PostView draft = new PostView(9L, "Air show next month\nMore soon", LocalDate.of(2026, 11, 20), false, false, List.of());
        PostView live = new PostView(4L, "<b>Open</b> hangar day", LocalDate.of(2026, 11, 2), true, true,
                List.of(new PhotoView(1L, "a.jpg", "Hangar", "Hangar", 1600, 900), new PhotoView(2L, "b.jpg", null, "Photo 2 of 2", 1600, 900)));
        when(newsService.adminList()).thenReturn(List.of(draft, live));

        mvc.perform(get("/admin/content"))
            .andExpect(status().isOk())
            .andExpect(content().string(stringContainsInOrder(
                "id=\"news-tab\"", "data-bs-target=\"#news\"", ">News</button>",
                "2 news posts",
                "id=\"news\" role=\"tabpanel\"", "href=\"/admin/content/news/new\"",
                "data-news-row=\"9\"", "Draft", "November 20, 2026", "0 photos", "Air show next month",
                "href=\"/admin/content/news/9/edit\"",
                "action=\"/admin/content/news/9/publish\"", "name=\"published\" value=\"true\"",
                "action=\"/admin/content/news/9/pin\"", "name=\"pinned\" value=\"true\"",
                "action=\"/admin/content/news/9/delete\"", "confirm(",
                "data-news-row=\"4\"", "src=\"/exhibit-photos/a.jpg\"", "alt=\"Hangar\"", "Published", "Pinned", "2 photos",
                "&lt;b&gt;Open&lt;/b&gt; hangar day",
                "name=\"published\" value=\"false\"", "name=\"pinned\" value=\"false\"")))
            .andExpect(content().string(not(containsString("More soon")))) // the list shows the first line only
            .andExpect(content().string(not(containsString("<b>Open</b>"))));
    }

    @Test
    void withNoPostsTheTabSaysSo() throws Exception {
        when(newsService.adminList()).thenReturn(List.of());
        mvc.perform(get("/admin/content"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("No news posts yet.")))
            .andExpect(content().string(containsString("0 news posts")));
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void aGuardCannotOpenWebsiteContent() throws Exception {
        mvc.perform(get("/admin/content")).andExpect(status().isForbidden());
    }
}
