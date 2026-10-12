package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Analytics page: Feedback Insights (Root Cause Matrix) figures and markup. No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class FeedbackInsightsTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor review(long id, int rating, String zone, String topic, String sentiment) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setStatus("CHECKED-OUT");
        v.setVisitDate(LocalDate.now().minusDays(1));
        v.setRating(rating);
        v.setFeedback("text " + id);
        v.setZoneName(zone);
        v.setTopicCategory(topic);
        v.setFeedbackSentiment(sentiment);
        return v;
    }

    @Test
    @SuppressWarnings("unchecked")
    void complaintsAreGroupedByTopicZoneAndMatrix() throws Exception {
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(
            review(1, 4, "Restrooms/Facilities", "Facilities", "NEGATIVE"), // negative text despite 4 stars -> complaint
            review(2, 2, "Restrooms/Facilities", "Facilities", "NEUTRAL"),  // 2 stars -> complaint
            review(3, 1, "Aircraft Park", "Exhibits", "NEGATIVE"),
            review(4, 5, "Aircraft Park", "Exhibits", "POSITIVE"),          // praise -> not a complaint, zone still listed
            review(5, 5, "Science Discovery Area", "General", "POSITIVE"),  // zone with zero complaints
            review(6, 1, null, null, null)                                  // legacy / AI offline row
        )));

        MvcResult result = mvc.perform(get("/admin/analytics")).andExpect(status().isOk()).andReturn();
        Map<String, Object> m = result.getModelAndView().getModel();

        assertThat(m.get("complaintCount")).isEqualTo(4);
        assertThat((List<String>) m.get("insightTopicLabels"))
            .containsExactly("Facilities", "Staff", "Exhibits", "Ticketing/Entry", "General", "Unclassified");
        assertThat((List<Integer>) m.get("insightTopicData")).containsExactly(2, 0, 1, 0, 0, 1);
        assertThat((List<String>) m.get("insightZoneLabels"))
            .containsExactly("Restrooms/Facilities", "Aircraft Park", "Unspecified", "Science Discovery Area");
        assertThat((List<Integer>) m.get("insightZoneData")).containsExactly(2, 1, 1, 0);

        List<Map<String, Object>> rows = (List<Map<String, Object>>) m.get("rootCauseRows");
        assertThat(rows).extracting(r -> r.get("zone")).containsExactly("Restrooms/Facilities", "Aircraft Park", "Unspecified");
        assertThat((List<Integer>) rows.get(0).get("counts")).containsExactly(2, 0, 0, 0, 0, 0);
        assertThat((List<Integer>) rows.get(2).get("counts")).containsExactly(0, 0, 0, 0, 0, 1);

        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("Feedback Insights", "id=\"complaintTopicChart\"", "id=\"complaintZoneChart\"",
            "id=\"zoneModal\"", "Manage Zones", "Zone &times; Topic", "/api/zones/all");
    }

    @Test
    void feedbackRowsCarryDataAttributesForSearchAndSort() throws Exception {
        Visitor checkedOut = review(4, 3, "Aircraft Park", "General", "NEUTRAL");
        checkedOut.setName("Dela Cruz, Juan");
        checkedOut.setCheckOutTime(java.time.LocalDateTime.of(2026, 10, 3, 15, 30));
        Visitor legacy = review(2, 5, null, null, null); // no check-out time: falls back to the visit date
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(checkedOut, legacy)));

        String html = mvc.perform(get("/admin/analytics")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()
            .replaceAll("\\s+", " ");

        assertThat(html).contains("data-id=\"4\" data-name=\"Dela Cruz, Juan\" data-rating=\"3\" data-date=\"2026-10-03T15:30\"",
            "data-id=\"2\" data-name=\"Visitor 2\" data-rating=\"5\" data-date=\"" + LocalDate.now().minusDays(1) + "\"",
            "id=\"feedSearch\"", "id=\"feedSortRating\"", "id=\"feedSortDate\"", "id=\"feedbackFeed\"", "id=\"feedNoMatch\"",
            "Highest First", "Lowest First", "Newest First", "Oldest First");
        // existing row markup is untouched
        assertThat(html).contains("<span class=\"av-feed-name\">Dela Cruz, Juan</span>", "aria-label=\"3 of 5 stars\"");
    }

    @Test
    void noReviewsHidesTheFeedControls() throws Exception {
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>());
        String html = mvc.perform(get("/admin/analytics")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("No feedback reports have been submitted yet.").doesNotContain("id=\"feedSearch\"", "id=\"feedNoMatch\"");
    }

    @Test
    void noComplaintsShowsTheEmptyStateAndNoMatrix() throws Exception {
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(review(1, 5, "Aircraft Park", "General", "POSITIVE"))));
        MvcResult result = mvc.perform(get("/admin/analytics")).andExpect(status().isOk()).andReturn();
        assertThat(result.getModelAndView().getModel().get("complaintCount")).isEqualTo(0);
        String html = result.getResponse().getContentAsString();
        assertThat(html).contains("No complaints recorded.").doesNotContain("Zone &times; Topic");
    }
}
