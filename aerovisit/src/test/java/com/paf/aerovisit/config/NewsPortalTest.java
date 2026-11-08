package com.paf.aerovisit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.controller.VisitorPortalController;
import com.paf.aerovisit.repository.AnnouncementRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.ExhibitPhotoRepository;
import com.paf.aerovisit.repository.GuidelineRepository;
import com.paf.aerovisit.repository.HeroSlideRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.NewsService;
import com.paf.aerovisit.service.NewsService.PhotoView;
import com.paf.aerovisit.service.NewsService.PostView;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** The News tab on the public booking page: placed left of Book a Visit. */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
@WithAnonymousUser
class NewsPortalTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private AnnouncementRepository announcementRepo;
    @MockitoBean private GuidelineRepository guidelineRepo;
    @MockitoBean private ExhibitPhotoRepository photoRepo;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private HeroSlideRepository heroRepo;
    @MockitoBean private NewsService newsService;

    private static final Pattern TAB = Pattern.compile("<button class=\"([^\"]*)\" id=\"([a-z]+-tab)\" data-bs-toggle=\"tab\"[^>]*role=\"tab\"");

    @Test
    void theNewsTabIsLeftOfBookAVisitAndIsTheTabThePageOpensOn() throws Exception {
        String html = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String tabs = html.substring(html.indexOf("id=\"bookingTabs\""), html.indexOf("</ul>", html.indexOf("id=\"bookingTabs\"")));
        Matcher m = TAB.matcher(tabs);
        List<String> ids = new java.util.ArrayList<>();
        List<String> classes = new java.util.ArrayList<>();
        while (m.find()) { classes.add(m.group(1)); ids.add(m.group(2)); }

        assertThat(ids).containsExactly("news-tab", "book-tab", "manage-tab", "guidelines-tab", "exhibits-tab");
        assertThat(classes.get(0)).isEqualTo("nav-link active");   // News is what the page opens on
        assertThat(classes.get(1)).isEqualTo("nav-link");          // Book a Visit is one click away
        assertThat(tabs).contains("data-bs-target=\"#news\"", "aria-controls=\"news\"", "bi-newspaper", ">News");
        assertThat(html).contains("class=\"tab-pane fade show active\" id=\"news\" role=\"tabpanel\" aria-labelledby=\"news-tab\"",
                "class=\"tab-pane fade\" id=\"book\"",
                "id=\"newsFeed\" role=\"feed\"", "id=\"newsLoadMore\"", "/js/news.js", "/styles/news.css");
        // The feed itself is never in the page: news.js loads 5 posts at a time from /news/feed (at once, as News is open)
        assertThat(html).doesNotContain("class=\"vp-news-post\"");
        verify(newsService, never()).feedPage(ArgumentMatchers.anyInt());
        // No refused booking: nothing switches the page to Book a Visit
        assertThat(html).doesNotContain("after a refused booking it opens on Book a Visit");
    }

    @Test
    void afterARefusedBookingThePageOpensOnBookAVisit() throws Exception {
        String html = mvc.perform(get("/").flashAttr("error", "Please choose a time window.")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("after a refused booking it opens on Book a Visit",
                "if (location.hash === '#manage' || !window.bootstrap) return;");
    }

}
