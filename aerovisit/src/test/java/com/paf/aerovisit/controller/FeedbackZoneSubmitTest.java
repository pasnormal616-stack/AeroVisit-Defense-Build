package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.hamcrest.Matchers.containsString;

import java.time.LocalDate;
import java.util.Optional;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.MuseumZone;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumZoneRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Feedback form: zone dropdown, zone + topic saved on submit, critical alert email unchanged. No database. */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
class FeedbackZoneSubmitTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private MuseumZoneRepository zoneRepo;

    private Visitor checkedOut() {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setStatus("CHECKED-OUT");
        v.setVisitDate(LocalDate.now());
        v.setSecureToken("tok");
        when(repo.findBySecureToken("tok")).thenReturn(Optional.of(v));
        return v;
    }

    private static MuseumZone zone(String name, boolean active) {
        MuseumZone z = new MuseumZone(name);
        z.setActive(active);
        return z;
    }

    @Test
    void feedbackPageHasTheZoneDropdownAndFetchesActiveZones() throws Exception {
        checkedOut();
        mvc.perform(get("/leave-feedback").param("token", "tok"))
            .andExpect(view().name("feedback"))
            .andExpect(content().string(containsString("Which area are you reviewing?")))
            .andExpect(content().string(containsString("<select id=\"feedbackZone\" name=\"zone\"")))
            .andExpect(content().string(containsString("fetch('/api/zones'")));
    }

    @Test
    void activeZoneTopicAndSentimentAreSavedAndCriticalAlertStillFires() throws Exception {
        Visitor v = checkedOut();
        when(zoneRepo.findByNameIgnoreCase("Aircraft Park")).thenReturn(Optional.of(zone("Aircraft Park", true)));
        when(aiService.analyzeFeedbackSentiment(anyString())).thenReturn(new JSONObject(
            "{\"sentiment\":\"NEGATIVE\",\"critical_alert\":true,\"score\":0.91,\"topic_category\":\"Exhibits\"}"));

        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "2")
                .param("feedback", "Sira yung display").param("zone", "Aircraft Park"))
            .andExpect(view().name("feedback-success"));

        assertThat(v.getZoneName()).isEqualTo("Aircraft Park");
        assertThat(v.getTopicCategory()).isEqualTo("Exhibits");
        assertThat(v.getFeedbackSentiment()).isEqualTo("NEGATIVE");
        verify(repo, atLeastOnce()).save(v);
        verify(emailService).sendCriticalFeedbackAlert(anyString(), eq("Juan Dela Cruz"), eq("Sira yung display"), eq(0.91));
    }

    @Test
    void disabledUnknownOrMissingZoneIsStoredAsNull() throws Exception {
        when(zoneRepo.findByNameIgnoreCase("Old Hangar")).thenReturn(Optional.of(zone("Old Hangar", false)));
        when(zoneRepo.findByNameIgnoreCase("<script>")).thenReturn(Optional.empty());
        for (String zone : new String[] {"Old Hangar", "<script>", ""}) {
            Visitor v = checkedOut();
            mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "4").param("feedback", "ok").param("zone", zone))
                .andExpect(view().name("feedback-success"));
            assertThat(v.getZoneName()).as(zone).isNull();
        }
        Visitor v = checkedOut();
        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "4").param("feedback", "ok"))
            .andExpect(view().name("feedback-success"));
        assertThat(v.getZoneName()).isNull();
    }

    @Test
    void aiOfflineStillSavesTheReviewWithZoneAndNoTopic() throws Exception {
        Visitor v = checkedOut();
        when(zoneRepo.findByNameIgnoreCase("restrooms/facilities")).thenReturn(Optional.of(zone("Restrooms/Facilities", true)));
        when(aiService.analyzeFeedbackSentiment(anyString())).thenReturn(null);

        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "1").param("feedback", "mabaho").param("zone", "restrooms/facilities"))
            .andExpect(view().name("feedback-success"));

        assertThat(v.getRating()).isEqualTo(1);
        assertThat(v.getZoneName()).isEqualTo("Restrooms/Facilities"); // canonical spelling from the zone table
        assertThat(v.getTopicCategory()).isNull();
        verify(repo).save(v);
        verify(emailService, never()).sendCriticalFeedbackAlert(anyString(), anyString(), anyString(), anyDouble());
    }

    @Test
    void oldAiResponseWithoutTopicKeepsTheAlertWorking() throws Exception {
        Visitor v = checkedOut();
        when(aiService.analyzeFeedbackSentiment(anyString())).thenReturn(new JSONObject(
            "{\"sentiment\":\"NEGATIVE\",\"critical_alert\":true,\"score\":0.88}"));

        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "1").param("feedback", "bad"))
            .andExpect(view().name("feedback-success"));

        assertThat(v.getTopicCategory()).isNull();
        verify(repo).save(any());
        verify(emailService).sendCriticalFeedbackAlert(anyString(), anyString(), eq("bad"), eq(0.88));
    }
}
