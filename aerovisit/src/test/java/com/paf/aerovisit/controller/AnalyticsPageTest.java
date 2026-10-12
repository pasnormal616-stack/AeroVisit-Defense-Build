package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.MuseumPrediction;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Analytics & Insights page (strategic analytics moved off /admin) and the slimmer operational dashboard. No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class AnalyticsPageTest {

    private static final String[] ANALYTICS_MARKUP = {
        "id=\"visitorChart\"", "id=\"peakHourChart\"", "id=\"attritionFunnel\"", "id=\"xaiModal\"", "id=\"chartInspectModal\"",
        "Predicted Walk-ins", "Online Pre-Booked", "Total Expected", "Operational Protocol",
        "AI Traffic Forecast (37-Day)", "Peak Arrival Heatmap", "Attrition Funnel", "Live Visitor Feedback"
    };
    private static final String[] ANALYTICS_MODEL = {
        "chartDates", "chartHistorical", "chartPredictive", "peakHourLabels", "peakHourData", "walkIns", "preBooked",
        "totalPrediction", "facilityProtocol", "totalRegistered", "totalApproved", "totalCheckedIn", "averageRating", "reviewCount"
    };

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor rated(long id, Integer rating, String feedback) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setStatus("CHECKED-OUT");
        v.setVisitDate(LocalDate.now().minusDays(id));
        v.setRating(rating);
        v.setFeedback(feedback);
        return v;
    }

    @Test
    void directorSeesEveryAnalyticsPanelOnTheAnalyticsPage() throws Exception {
        when(aiService.fetchTurnoutPrediction(anyInt(), anyString(), anyInt()))
            .thenReturn(new MuseumPrediction(57, 34, 20, 48, 87, "Light Rain", "ELEVATED_ALERT", "4 Gate Guards", "OPEN_WITH_BAG_CHECK"));

        var result = mvc.perform(get("/admin/analytics"))
            .andExpect(status().isOk())
            .andExpect(view().name("analytics"))
            .andExpect(model().attribute("walkIns", 34))
            .andExpect(model().attribute("totalPrediction", 57))
            .andExpect(content().string(containsString("href=\"/admin/analytics\" class=\"av-nav-link is-active\"")));
        for (String markup : ANALYTICS_MARKUP) {
            result.andExpect(content().string(containsString(markup)));
        }
        for (String key : ANALYTICS_MODEL) {
            result.andExpect(model().attributeExists(key));
        }
    }

    @Test
    void analyticsPageStillRendersWhenTheAiEngineIsOfflineOrFails() throws Exception {
        when(aiService.fetchTurnoutPrediction(anyInt(), anyString(), anyInt())).thenReturn(null);
        mvc.perform(get("/admin/analytics"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("liveWeather", "Telemetry Offline"))
            .andExpect(content().string(containsString("AI Advisor is currently disconnected from the Command Center.")));

        when(aiService.fetchTurnoutPrediction(anyInt(), anyString(), anyInt())).thenThrow(new RuntimeException("AI down"));
        mvc.perform(get("/admin/analytics"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("SYSTEM OFFLINE")));
    }

    @Test
    void feedbackFeedListsEveryRatedVisitNewestFirst() throws Exception {
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(
            rated(3, 4, "Loved the guide"), rated(9, 5, "Old but gold"), rated(5, null, null), rated(7, 2, ""))));

        MvcResult result = mvc.perform(get("/admin/analytics"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("reviewCount", 3))
            .andExpect(model().attribute("averageRating", 3.7))
            .andReturn();

        @SuppressWarnings("unchecked")
        List<Visitor> feed = (List<Visitor>) result.getModelAndView().getModel().get("feedbackVisitors");
        assertThat(feed).extracting(Visitor::getId).containsExactly(9L, 7L, 3L);
        String html = result.getResponse().getContentAsString();
        assertThat(html.indexOf("Visitor 9")).isLessThan(html.indexOf("Visitor 7")).isLessThan(html.indexOf("Visitor 3"));
        assertThat(html).doesNotContain("Visitor 5").contains("No written comment provided.");
    }

    @Test
    void mainDashboardKeepsOperationsOnlyAndNoLongerRunsTheAiForecast() throws Exception {
        when(repo.findByVisitDate(LocalDate.now())).thenReturn(new ArrayList<>());

        var result = mvc.perform(get("/admin"))
            .andExpect(status().isOk())
            .andExpect(view().name("admin"))
            .andExpect(content().string(containsString("Daily Capacity Tracker")))
            .andExpect(content().string(containsString("Live Base Occupancy")))
            .andExpect(content().string(containsString("id=\"capacityGaugeMain\"")))
            .andExpect(content().string(containsString("id=\"visitorTable\"")))
            .andExpect(model().attributeExists("currentOccupancy", "todayMaxCapacity", "listVisitors", "dailyCapacity", "targetDate"))
            .andExpect(content().string(containsString("href=\"/admin/analytics\"")));
        for (String markup : ANALYTICS_MARKUP) {
            result.andExpect(content().string(not(containsString(markup))));
        }
        for (String key : ANALYTICS_MODEL) {
            result.andExpect(model().attributeDoesNotExist(key));
        }
        verify(aiService, never()).fetchTurnoutPrediction(anyInt(), anyString(), anyInt());

        // Occupancy sits right above the Civilian Manifest, next to the capacity tracker
        String html = mvc.perform(get("/admin")).andReturn().getResponse().getContentAsString();
        assertThat(html.indexOf("Daily Capacity Tracker")).isLessThan(html.indexOf("Live Base Occupancy"));
        assertThat(html.indexOf("Live Base Occupancy")).isLessThan(html.indexOf("Civilian Manifest"));
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardIsSentBackToTheDashboardAndHasNoAnalyticsLink() throws Exception {
        mvc.perform(get("/admin/analytics")).andExpect(redirectedUrl("/admin"));
        verify(aiService, never()).fetchTurnoutPrediction(anyInt(), anyString(), anyInt());

        // ...and /admin itself is the Director's full manifest: a guard is sent on to the Gate Dashboard
        mvc.perform(get("/admin")).andExpect(redirectedUrl("/guard/dashboard"));
        verify(repo, never()).findByVisitDate(org.mockito.ArgumentMatchers.any());
    }
}
