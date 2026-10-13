package com.paf.aerovisit.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.controller.GuardOperationController;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;

/**
 * src/test/resources/config/application.properties turns the limiter off for the test suite, so the many suites
 * that send dozens of requests from MockMvc's single 127.0.0.1 never share a bucket.
 */
@WebMvcTest(GuardOperationController.class)
@Import(SecurityConfig.class)
class RateLimitOffInTestsTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;

    @Test
    void theSuiteRunsWithTheLimiterOff() throws Exception {
        for (int i = 0; i < 30; i++) {
            mvc.perform(post("/login").with(csrf()).param("username", "director").param("password", "wrong"))
                .andExpect(redirectedUrl("/login?error"));
        }
    }
}
