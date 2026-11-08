package com.paf.aerovisit.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.service.NewsService;
import com.paf.aerovisit.service.NewsService.FeedPage;
import com.paf.aerovisit.service.NewsService.PhotoView;
import com.paf.aerovisit.service.NewsService.PostView;

/** GET /news/feed: one public, read-only page of the News feed, rendered (and escaped) on the server. No database. */
@WebMvcTest(NewsController.class)
@Import(SecurityConfig.class)
@WithAnonymousUser
class NewsFeedTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private NewsService newsService;

    static PhotoView photo(long id, String alt) {
        return new PhotoView(id, "n" + id + ".jpg", alt, alt, 1600, 1067);
    }

    static PostView postView(long id, String caption, boolean pinned, List<PhotoView> photos) {
        return new PostView(id, caption, LocalDate.of(2026, 11, 6), true, pinned, photos);
    }

    @Test
    void aPostWithSeveralPhotosRendersTheCarouselDotsAndAltTextOnEveryPhoto() throws Exception {
        PostView p = postView(7, "Open hangar day", true, List.of(
                photo(1, "F-5A Freedom Fighter on the apron"), photo(2, "Visitors in Hangar 2"), photo(3, "The museum gate")));
        when(newsService.feedPage(0)).thenReturn(new FeedPage(List.of(p), 1));

        mvc.perform(get("/news/feed"))
            .andExpect(status().isOk())
            .andExpect(content().string(stringContainsInOrder(
                "class=\"vp-news-page\"", "data-next-page=\"1\"",
                "<article", "id=\"news-7\"",
                "datetime=\"2026-11-06\"", "November 6, 2026", "Pinned",
                "aria-roledescription=\"carousel\"",
                "src=\"/exhibit-photos/n1.jpg\"", "alt=\"F-5A Freedom Fighter on the apron\"", "loading=\"lazy\"", "width=\"1600\"", "height=\"1067\"",
                "alt=\"Visitors in Hangar 2\"",
                "alt=\"The museum gate\"",
                "data-news-prev", "data-news-next",
                "aria-label=\"Photo 1 of 3\"", "aria-current=\"true\"", "aria-label=\"Photo 2 of 3\"", "aria-label=\"Photo 3 of 3\"",
                "Open hangar day")));
    }

    @Test
    void aSinglePhotoHasNoArrowsOrDots() throws Exception {
        when(newsService.feedPage(0)).thenReturn(new FeedPage(List.of(postView(8, "One", false, List.of(photo(1, "Only photo")))), null));
        mvc.perform(get("/news/feed"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("alt=\"Only photo\"")))
            .andExpect(content().string(not(containsString("data-news-dot"))))
            .andExpect(content().string(not(containsString("data-news-next"))))
            .andExpect(content().string(not(containsString("Pinned"))));
    }

    @Test
    void aCaptionContainingHtmlIsShownAsTextAndKeepsItsLineBreaks() throws Exception {
        PostView p = postView(9, "<script>alert('x')</script>\n<b>Bold?</b> <img src=x onerror=alert(1)>", false, List.of(photo(1, "a")));
        when(newsService.feedPage(0)).thenReturn(new FeedPage(List.of(p), null));

        mvc.perform(get("/news/feed"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString(
                "&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;\n&lt;b&gt;Bold?&lt;/b&gt; &lt;img src=x onerror=alert(1)&gt;")))
            .andExpect(content().string(not(containsString("<script>alert"))))
            .andExpect(content().string(not(containsString("<b>Bold"))))
            .andExpect(content().string(not(containsString("<img src=x"))));
    }

    @Test
    void theLastPageHasNoNextPageMarker() throws Exception {
        when(newsService.feedPage(2)).thenReturn(new FeedPage(List.of(), null));
        mvc.perform(get("/news/feed").param("page", "2"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("class=\"vp-news-page\"")))
            .andExpect(content().string(not(containsString("data-next-page"))));
    }

    @Test
    void aMalformedPageNumberIsReadAsTheFirstPage() throws Exception {
        when(newsService.feedPage(0)).thenReturn(new FeedPage(List.of(), null));
        mvc.perform(get("/news/feed").param("page", "1;DROP TABLE")).andExpect(status().isOk());
        verify(newsService).feedPage(0);
    }

    @Test
    void theFeedIsReadOnlyAPostIsRefused() throws Exception {
        mvc.perform(post("/news/feed").with(csrf())).andExpect(status().is4xxClientError());
        mvc.perform(post("/news/feed")).andExpect(status().isForbidden());
        verify(newsService, never()).feedPage(anyInt());
    }
}
