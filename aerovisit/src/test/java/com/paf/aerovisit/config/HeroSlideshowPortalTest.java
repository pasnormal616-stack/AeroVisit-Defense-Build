package com.paf.aerovisit.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.controller.VisitorPortalController;
import com.paf.aerovisit.model.HeroSlide;
import com.paf.aerovisit.repository.AnnouncementRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.ExhibitPhotoRepository;
import com.paf.aerovisit.repository.GuidelineRepository;
import com.paf.aerovisit.repository.HeroSlideRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** The hero slideshow on the public booking page: the photos from Website Content become the backdrop's slides. */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
@WithAnonymousUser
class HeroSlideshowPortalTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private AnnouncementRepository announcementRepo;
    @MockitoBean private GuidelineRepository guidelineRepo;
    @MockitoBean private ExhibitPhotoRepository photoRepo;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private HeroSlideRepository heroRepo;

    @Test
    void thePhotosBecomeBackdropSlidesInOrderWithTheirText() throws Exception {
        HeroSlide first = new HeroSlide("hangar.jpg", 1, LocalDateTime.now());
        first.setKicker("Main Hangar");
        first.setCaption("F-5A Freedom Fighter");
        HeroSlide second = new HeroSlide("park.webp", 2, LocalDateTime.now()); // no text: no empty attributes
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(first, second));

        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.stringContainsInOrder(
                "class=\"vp-slide vp-slide--fallback\"",
                "data-src=\"/exhibit-photos/hangar.jpg\"", "data-kicker=\"Main Hangar\"", "data-caption=\"F-5A Freedom Fighter\"",
                "data-src=\"/exhibit-photos/park.webp\"",
                "id=\"vpSlideCaption\"")))
            .andExpect(content().string(not(containsString("data-kicker=\"\""))));
    }

    @Test
    void withNoPhotosTheNavySkyStays() throws Exception {
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of());
        mvc.perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("class=\"vp-slide vp-slide--fallback\"")))
            .andExpect(content().string(not(containsString("data-src=\"/exhibit-photos/"))));
    }

    @Test
    void textIsEscapedSoItCannotInjectMarkup() throws Exception {
        HeroSlide s = new HeroSlide("a.jpg", 1, LocalDateTime.now());
        s.setCaption("\"><script>alert(1)</script>");
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(s));
        mvc.perform(get("/"))
            .andExpect(content().string(not(containsString("<script>alert(1)</script>"))));
    }
}
