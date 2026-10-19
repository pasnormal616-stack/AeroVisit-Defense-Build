package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.HeroSlide;
import com.paf.aerovisit.repository.AnnouncementRepository;
import com.paf.aerovisit.repository.ExhibitPhotoRepository;
import com.paf.aerovisit.repository.GuidelineRepository;
import com.paf.aerovisit.repository.HeroSlideRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.service.ExhibitPhotoStorage;

/** Website Content > Hero Slideshow: add, label, reorder and delete the visitor portal's background photos. No database. */
@WebMvcTest(ContentManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class HeroSlideshowContentTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private AnnouncementRepository announcementRepo;
    @MockitoBean private GuidelineRepository guidelineRepo;
    @MockitoBean private ExhibitPhotoRepository photoRepo;
    @MockitoBean private ExhibitPhotoStorage photoStorage;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private HeroSlideRepository heroRepo;

    private static HeroSlide slide(long id, int order, String filename) {
        HeroSlide s = new HeroSlide(filename, order, LocalDateTime.of(2026, 10, 19, 9, 0));
        s.setId(id);
        return s;
    }

    private static MockMultipartFile jpg(String name) {
        return new MockMultipartFile("photos", name, "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 1});
    }

    @Test
    void theTabListsThePhotosInOrderWithTheirText() throws Exception {
        HeroSlide first = slide(1, 1, "a.jpg");
        first.setKicker("Aircraft Park");
        first.setCaption("F-5A Freedom Fighter");
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(first, slide(2, 2, "b.jpg")));

        mvc.perform(get("/admin/content"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"hero-tab\"")))
            .andExpect(content().string(containsString("2 hero photos")))
            .andExpect(content().string(containsString("src=\"/exhibit-photos/a.jpg\"")))
            .andExpect(content().string(containsString("value=\"Aircraft Park\"")))
            .andExpect(content().string(containsString("value=\"F-5A Freedom Fighter\"")))
            .andExpect(content().string(containsString("up to 8 photos")));
    }

    @Test
    void uploadedPhotosAreStoredAndAddedAfterTheExistingOnes() throws Exception {
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(slide(1, 1, "a.jpg")));
        when(photoStorage.storeFile(any())).thenReturn("new-1.jpg", "new-2.jpg");

        mvc.perform(multipart("/admin/content/hero/upload").file(jpg("one.jpg")).file(jpg("two.jpg")).with(csrf()))
            .andExpect(redirectedUrl("/admin/content#hero"))
            .andExpect(flash().attribute("success", "2 photos added to the hero slideshow."));

        ArgumentCaptor<HeroSlide> saved = ArgumentCaptor.forClass(HeroSlide.class);
        verify(heroRepo, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(HeroSlide::getFilename).containsExactly("new-1.jpg", "new-2.jpg");
        assertThat(saved.getAllValues()).extracting(HeroSlide::getDisplayOrder).containsExactly(2, 3);
        verify(photoRepo, never()).save(any()); // the Exhibits slideshow is untouched
    }

    @Test
    void theSlideshowHoldsAtMostEightPhotos() throws Exception {
        List<HeroSlide> seven = new ArrayList<>();
        for (int i = 1; i <= 7; i++) seven.add(slide(i, i, i + ".jpg"));
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(seven);
        when(photoStorage.storeFile(any())).thenReturn("eighth.jpg");

        mvc.perform(multipart("/admin/content/hero/upload").file(jpg("a.jpg")).file(jpg("b.jpg")).with(csrf()))
            .andExpect(flash().attribute("success", "1 photo added to the hero slideshow."))
            .andExpect(flash().attribute("error", containsString("1 photo was not added: the slideshow holds at most 8.")));
        verify(photoStorage, times(1)).storeFile(any());

        seven.add(slide(8, 8, "8.jpg"));
        mvc.perform(multipart("/admin/content/hero/upload").file(jpg("c.jpg")).with(csrf()))
            .andExpect(flash().attribute("error", containsString("already has the maximum of 8 photos")));
    }

    @Test
    void anInvalidFileIsReportedAndTheOthersAreStillAdded() throws Exception {
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of());
        when(photoStorage.storeFile(any()))
                .thenThrow(new ExhibitPhotoStorage.InvalidImageException("fake.jpg is not a valid image file."))
                .thenReturn("good.jpg");

        mvc.perform(multipart("/admin/content/hero/upload").file(jpg("fake.jpg")).file(jpg("good.jpg")).with(csrf()))
            .andExpect(flash().attribute("success", "1 photo added to the hero slideshow."))
            .andExpect(flash().attribute("error", "Not uploaded: fake.jpg is not a valid image file."));
    }

    @Test
    void theTextIsTrimmedBlankBecomesNothingAndOverlongTextIsRefused() throws Exception {
        HeroSlide s = slide(1, 1, "a.jpg");
        when(heroRepo.findById(1L)).thenReturn(Optional.of(s));

        mvc.perform(post("/admin/content/hero/1/update").with(csrf()).param("kicker", "  Main Hangar ").param("caption", "   "))
            .andExpect(flash().attribute("success", "Hero photo text saved."));
        assertThat(s.getKicker()).isEqualTo("Main Hangar");
        assertThat(s.getCaption()).isNull();

        mvc.perform(post("/admin/content/hero/1/update").with(csrf()).param("caption", "x".repeat(HeroSlide.MAX_CAPTION + 1)))
            .andExpect(flash().attribute("error", "The caption can be at most 90 characters."));
        assertThat(s.getCaption()).isNull();
    }

    @Test
    void movingAPhotoRenumbersTheWholeSlideshow() throws Exception {
        HeroSlide a = slide(1, 1, "a.jpg"), b = slide(2, 2, "b.jpg"), c = slide(3, 3, "c.jpg");
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(a, b, c));

        mvc.perform(post("/admin/content/hero/3/move").with(csrf()).param("direction", "up"))
            .andExpect(redirectedUrl("/admin/content#hero"));

        assertThat(List.of(a.getDisplayOrder(), c.getDisplayOrder(), b.getDisplayOrder())).containsExactly(1, 2, 3);
    }

    @Test
    void deletingRemovesTheFileAndTheSlide() throws Exception {
        HeroSlide s = slide(1, 1, "a.jpg");
        when(heroRepo.findById(1L)).thenReturn(Optional.of(s));

        mvc.perform(post("/admin/content/hero/1/delete").with(csrf()))
            .andExpect(flash().attribute("success", "Photo removed from the hero slideshow."));

        verify(photoStorage).deleteFile("a.jpg");
        verify(heroRepo).delete(s);
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardsCannotChangeTheSlideshow() throws Exception {
        mvc.perform(multipart("/admin/content/hero/upload").file(jpg("a.jpg")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/content/hero/1/delete").with(csrf())).andExpect(status().isForbidden());
        verify(photoStorage, never()).storeFile(any());
    }
}
