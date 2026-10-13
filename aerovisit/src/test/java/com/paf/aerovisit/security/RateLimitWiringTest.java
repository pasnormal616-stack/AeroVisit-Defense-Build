package com.paf.aerovisit.security;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.controller.GuardOperationController;
import com.paf.aerovisit.controller.VisitorPortalController;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * RateLimitFilter inside the real Spring Security chain: before CSRF and the login form, so a refused request never
 * reaches either, and staff limits see the signed-in user. Each test uses its own IP / username, because the filter
 * (and its counts) is shared by every test in this cached context.
 */
@WebMvcTest({VisitorPortalController.class, GuardOperationController.class})
@Import(SecurityConfig.class)
@TestPropertySource(properties = "aerovisit.ratelimit.enabled=true")
class RateLimitWiringTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static RequestPostProcessor from(String ip) {
        return request -> { request.setRemoteAddr(ip); return request; };
    }

    @Test
    void theEleventhSignInAttemptIsRefusedBeforeCsrfAndTheLoginForm() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/login").with(from("203.0.113.101")).with(csrf())
                            .param("username", "director").param("password", "wrong-" + i))
                .andExpect(redirectedUrl("/login?error"));
        }
        mvc.perform(post("/login").with(from("203.0.113.101")).with(csrf())
                        .param("username", "director").param("password", "wrong"))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")));
        // no CSRF token at all: still 429, not 403, so the limiter runs first
        mvc.perform(post("/login").with(from("203.0.113.101")).param("username", "director").param("password", "x"))
            .andExpect(status().isTooManyRequests());
        // another visitor is unaffected
        mvc.perform(post("/login").with(from("203.0.113.102")).with(csrf())
                        .param("username", "director").param("password", "wrong"))
            .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void aRefusedBookingUploadNeverReachesTheController() throws Exception {
        // An empty upload reaches the controller, which rejects the missing photo parts with 400...
        for (int i = 0; i < 20; i++) {
            mvc.perform(multipart("/save").with(from("203.0.113.103"))).andExpect(status().isBadRequest());
        }
        // ...the 21st is refused by the limiter before that
        mvc.perform(multipart("/save").with(from("203.0.113.103")))
            .andExpect(status().isTooManyRequests());
    }

    @Test
    @WithMockUser(username = "wiring-guard", roles = "GUARD")
    void theScannerGetsAJson429ItCanShow() throws Exception {
        for (int i = 0; i < 60; i++) {
            mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", "token-" + i)).andExpect(status().isOk());
        }
        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", "token-x"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value(RateLimitFilter.MESSAGE))
            .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")));
    }
}
