package com.paf.aerovisit.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.AiSwitch;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Pages with the AI switched off (Settings): the forecast says so, and the Settings page has the AI Features card. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class AiOffPagesTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private AiSwitch aiSwitch;

    @Test
    void analyticsSaysTheAiIsOffInsteadOfShowingAnEmptyForecast() throws Exception {
        when(aiSwitch.enabled()).thenReturn(false);
        mvc.perform(get("/admin/analytics"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("aiEnabled", false))
            .andExpect(content().string(containsString("AI features are turned off in Settings")));
    }

    @Test
    void withTheAiOnThereIsNoOffNotice() throws Exception {
        when(aiSwitch.enabled()).thenReturn(true);
        mvc.perform(get("/admin/analytics"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("AI features are turned off in Settings"))));
    }

    @Test
    void theDirectorsSettingsPageHasTheAiFeaturesCard() throws Exception {
        when(aiSwitch.enabled()).thenReturn(true);
        mvc.perform(get("/admin/settings"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("id=\"aiFeatures\"")))
            .andExpect(content().string(containsString("face bans and duplicate-face checks are not enforced")))
            .andExpect(content().string(containsString("/admin/api/staff-accounts/ai")));
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardsDoNotSeeTheAiFeaturesCard() throws Exception {
        mvc.perform(get("/admin/settings"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("id=\"aiFeatures\""))));
    }
}
