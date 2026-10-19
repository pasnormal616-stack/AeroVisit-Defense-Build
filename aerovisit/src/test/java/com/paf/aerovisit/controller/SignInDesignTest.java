package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.HeroSlide;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.HeroSlideRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** The redesigned staff sign-in: the museum side with the Website Content hero photos, and the sign-in card's helpers. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
class SignInDesignTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private HeroSlideRepository heroRepo;

    private String loginPage() throws Exception {
        return mvc.perform(get("/login").with(anonymous())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theMuseumSideComesBeforeTheSignInCard() throws Exception {
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of());
        assertThat(loginPage()).containsSubsequence(
                "class=\"av-auth av-auth--split\"", "<aside class=\"av-auth-hero\">", "AeroVisit", "Command",
                "Approvals", "Gate check-in", "Analytics", "data-auth-clock",
                "<div class=\"av-auth-side\">", "<section class=\"av-auth-panel\">", "action=\"/login\"");
    }

    @Test
    void theHeroPhotosFromWebsiteContentAppearOneAtATime() throws Exception {
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of(
                new HeroSlide("hangar.jpg", 1, LocalDateTime.now()), new HeroSlide("park.jpg", 2, LocalDateTime.now())));
        String html = loginPage();
        assertThat(html).containsSubsequence(
                "class=\"av-auth-photo is-active\" src=\"/exhibit-photos/hangar.jpg\"", "loading=\"eager\"",
                "class=\"av-auth-photo\" src=\"/exhibit-photos/park.jpg\"", "loading=\"lazy\"");
    }

    @Test
    void withNoPhotosTheNavyPanelStaysAndNoImageIsRequested() throws Exception {
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of());
        assertThat(loginPage()).doesNotContain("class=\"av-auth-photo").doesNotContain("/exhibit-photos/");
    }

    @Test
    void thePasswordCanBeShownAndCapsLockIsFlagged() throws Exception {
        when(heroRepo.findAllByOrderByDisplayOrderAscIdAsc()).thenReturn(List.of());
        String html = loginPage();
        assertThat(html).contains("data-pw-toggle=\"password\" aria-label=\"Show password\" aria-pressed=\"false\"");
        assertThat(html).contains("data-caps-warning hidden");
        assertThat(html).contains("getModifierState('CapsLock')", "timeZone: 'Asia/Manila'");
    }
}
