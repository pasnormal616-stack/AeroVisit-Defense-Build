package com.paf.aerovisit.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.service.ExhibitPhotoStorage;
import com.paf.aerovisit.service.NewsService;
import com.paf.aerovisit.service.NewsService.NewsException;
import com.paf.aerovisit.service.NewsService.PhotoView;
import com.paf.aerovisit.service.NewsService.PostView;
import com.paf.aerovisit.service.NewsService.SaveAction;

/** Website Content > News, Director only: create, edit, reorder, publish, pin, delete, photo uploads. No database. */
@WebMvcTest(NewsAdminController.class)
@Import(SecurityConfig.class)
class NewsAdminControllerTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private NewsService newsService;

    private static final String XHR = "X-Requested-With";

    private static MockMultipartFile jpg() {
        return new MockMultipartFile("photo", "a.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF});
    }

    // ---------- Director ----------

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void theNewPostEditorOpens() throws Exception {
        when(newsService.today()).thenReturn(LocalDate.of(2026, 11, 6));
        mvc.perform(get("/admin/content/news/new"))
            .andExpect(status().isOk())
            .andExpect(content().string(stringContainsInOrder(
                "New News Post", "id=\"newsEditorForm\"", "maxlength=\"2200\"", "value=\"2026-11-06\"",
                "id=\"newsPhotoInput\"", "multiple", "value=\"draft\"", "value=\"publish\"", "id=\"newsPreview\"", "/js/news-editor.js")));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void theEditorShowsThePhotosInOrderWithTheirAltTextAndTheCaptionEscaped() throws Exception {
        PostView post = new PostView(5L, "</textarea><script>alert(1)</script>", LocalDate.of(2026, 11, 2), true, true, List.of(
                new PhotoView(31L, "c.jpg", "The C-130", "The C-130", 1600, 1000),
                new PhotoView(12L, "a.jpg", null, "Photo 2 of 2", 1600, 1000)));
        when(newsService.find(5L)).thenReturn(post);

        mvc.perform(get("/admin/content/news/5/edit"))
            .andExpect(status().isOk())
            .andExpect(content().string(stringContainsInOrder(
                "Edit News Post", "Published", "Pinned", "2 of 10 photos",
                "name=\"id\"", "value=\"5\"",
                "&lt;/textarea&gt;&lt;script&gt;alert(1)&lt;/script&gt;", "value=\"2026-11-02\"",
                "data-photo-id=\"31\"", "name=\"alt_31\"", "value=\"The C-130\"",
                "data-photo-id=\"12\"", "name=\"alt_12\"",
                "value=\"keep\"")))
            .andExpect(content().string(not(containsString("<script>alert(1)"))));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void aMissingPostGoesBackToTheList() throws Exception {
        mvc.perform(get("/admin/content/news/99/edit"))
            .andExpect(redirectedUrl("/admin/content#news"))
            .andExpect(flash().attribute("error", "That post no longer exists."));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void createSavesADraftAndOpensItsEditor() throws Exception {
        when(newsService.save(isNull(), eq("Open day"), eq("2026-11-06"), eq(SaveAction.DRAFT), eq(List.of()), eq(Map.of()), isNull()))
            .thenReturn(41L);
        mvc.perform(post("/admin/content/news/save").with(csrf())
                .param("id", "").param("caption", "Open day").param("postDate", "2026-11-06").param("action", "draft"))
            .andExpect(redirectedUrl("/admin/content/news/41/edit"))
            .andExpect(flash().attribute("success", containsString("draft")));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void editReorderAltTextRemoveAndPublishInOneSave() throws Exception {
        when(newsService.save(eq(5L), eq("Updated"), eq("2026-11-07"), eq(SaveAction.PUBLISH),
                eq(List.of(31L, 12L, 7L)), eq(Map.of(31L, "The C-130", 12L, "")), eq(List.of(9L))))
            .thenReturn(5L);
        mvc.perform(post("/admin/content/news/save").with(csrf()).header(XHR, "XMLHttpRequest")
                .param("id", "5").param("caption", "Updated").param("postDate", "2026-11-07").param("action", "publish")
                .param("photoOrder", "31,12,x,7").param("alt_31", "The C-130").param("alt_12", "").param("removePhoto", "9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.id").value(5))
            .andExpect(jsonPath("$.message").value("Post saved and published."));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void aRefusedSaveShowsTheReason() throws Exception {
        when(newsService.save(eq(5L), any(), any(), any(), any(), any(), any()))
            .thenThrow(new NewsException("The caption can be at most 2,200 characters (it has 2,201)."));
        mvc.perform(post("/admin/content/news/save").with(csrf()).header(XHR, "XMLHttpRequest").param("id", "5").param("caption", "x"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.message").value("The caption can be at most 2,200 characters (it has 2,201)."));
        mvc.perform(post("/admin/content/news/save").with(csrf()).param("id", "5").param("caption", "x"))
            .andExpect(redirectedUrl("/admin/content/news/5/edit"))
            .andExpect(flash().attribute("error", containsString("2,200")));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void publishUnpublishPinUnpinAndDelete() throws Exception {
        mvc.perform(post("/admin/content/news/5/publish").with(csrf()).param("published", "true"))
            .andExpect(redirectedUrl("/admin/content#news")).andExpect(flash().attribute("success", containsString("published")));
        verify(newsService).setPublished(5L, true);

        mvc.perform(post("/admin/content/news/5/publish").with(csrf()).param("published", "false"))
            .andExpect(redirectedUrl("/admin/content#news")).andExpect(flash().attribute("success", containsString("draft again")));
        verify(newsService).setPublished(5L, false);

        mvc.perform(post("/admin/content/news/5/pin").with(csrf()).param("pinned", "true"))
            .andExpect(redirectedUrl("/admin/content#news"));
        verify(newsService).setPinned(5L, true);
        mvc.perform(post("/admin/content/news/5/pin").with(csrf()).param("pinned", "false"))
            .andExpect(redirectedUrl("/admin/content#news"));
        verify(newsService).setPinned(5L, false);

        when(newsService.delete(5L)).thenReturn(true);
        mvc.perform(post("/admin/content/news/5/delete").with(csrf()))
            .andExpect(redirectedUrl("/admin/content#news")).andExpect(flash().attribute("success", "Post deleted, with its photos."));
        verify(newsService).delete(5L);
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void publishingWithoutPhotosIsRefused() throws Exception {
        when(newsService.setPublished(6L, true)).thenThrow(new NewsException("Add at least one photo before publishing this post."));
        mvc.perform(post("/admin/content/news/6/publish").with(csrf()).param("published", "true"))
            .andExpect(redirectedUrl("/admin/content#news"))
            .andExpect(flash().attribute("error", "Add at least one photo before publishing this post."));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void aPhotoUploadReturnsTheStoredPhoto() throws Exception {
        when(newsService.addPhoto(eq(5L), any(), eq("Hangar 2"))).thenReturn(new PhotoView(70L, "u.jpg", "Hangar 2", "Hangar 2", 1600, 900));
        mvc.perform(multipart("/admin/content/news/5/photos").file(jpg()).param("alt", "Hangar 2").with(csrf()).header(XHR, "XMLHttpRequest"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ok").value(true))
            .andExpect(jsonPath("$.photo.id").value(70))
            .andExpect(jsonPath("$.photo.url").value("/exhibit-photos/u.jpg"));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void aBadFileOrAnEleventhPhotoIsRefusedWithTheReason() throws Exception {
        when(newsService.addPhoto(eq(5L), any(), any()))
            .thenThrow(new ExhibitPhotoStorage.InvalidImageException("\"a.jpg\" is not a valid image file."));
        mvc.perform(multipart("/admin/content/news/5/photos").file(jpg()).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.ok").value(false))
            .andExpect(jsonPath("$.message").value("\"a.jpg\" is not a valid image file."));

        when(newsService.addPhoto(eq(6L), any(), any())).thenThrow(new NewsException("A post can have at most 10 photos."));
        mvc.perform(multipart("/admin/content/news/6/photos").file(jpg()).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("A post can have at most 10 photos."));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void everyChangeNeedsTheCsrfToken() throws Exception {
        mvc.perform(post("/admin/content/news/5/delete")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/content/news/save").param("caption", "x")).andExpect(status().isForbidden());
        verifyNoInteractions(newsService);
    }

    // ---------- Guard and signed-out visitors ----------

    private static List<MockHttpServletRequestBuilder> everyEndpoint() {
        return List.of(
            get("/admin/content/news/new"),
            get("/admin/content/news/5/edit"),
            post("/admin/content/news/save").with(csrf()).param("caption", "x"),
            post("/admin/content/news/save").with(csrf()).header(XHR, "XMLHttpRequest").param("caption", "x"),
            multipart("/admin/content/news/5/photos").file(jpg()).with(csrf()),
            post("/admin/content/news/5/publish").with(csrf()).param("published", "true"),
            post("/admin/content/news/5/pin").with(csrf()).param("pinned", "true"),
            post("/admin/content/news/5/delete").with(csrf()));
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void aGuardIsRefusedEverywhere() throws Exception {
        for (MockHttpServletRequestBuilder request : everyEndpoint()) {
            mvc.perform(request).andExpect(status().isForbidden());
        }
        verifyNoInteractions(newsService);
    }

    @Test
    @WithAnonymousUser
    void aSignedOutVisitorIsSentToTheLoginPage() throws Exception {
        for (MockHttpServletRequestBuilder request : everyEndpoint()) {
            mvc.perform(request).andExpect(redirectedUrlPattern("**/login"));
        }
        verifyNoInteractions(newsService);
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void aGuardCannotPublishEvenWithAForgedRequest() throws Exception {
        mvc.perform(post("/admin/content/news/5/publish").with(csrf()).header(XHR, "XMLHttpRequest").param("published", "true"))
            .andExpect(status().isForbidden());
        verify(newsService, org.mockito.Mockito.never()).setPublished(anyLong(), anyBoolean());
    }
}
