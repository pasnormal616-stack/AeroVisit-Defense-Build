package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Feedback submit hands the analysed visitor to NotificationService. No database. */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
class FeedbackNotificationHookTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private NotificationService notificationService;

    private Visitor checkedOut() {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setName("Juan Dela Cruz");
        v.setStatus("CHECKED-OUT");
        v.setVisitDate(LocalDate.now());
        v.setSecureToken("tok");
        when(repo.findBySecureToken("tok")).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void analysedFeedbackIsPassedOnWithItsSentiment() throws Exception {
        Visitor v = checkedOut();
        when(aiService.analyzeFeedbackSentiment(anyString())).thenReturn(new JSONObject(
            "{\"sentiment\":\"NEGATIVE\",\"critical_alert\":false,\"score\":0.6,\"topic_category\":\"Facilities\"}"));
        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "1").param("feedback", "mabaho"))
            .andExpect(view().name("feedback-success"));
        verify(notificationService).negativeFeedback(v);
    }

    @Test
    void aiOfflineMeansNoSentimentAndNoNotificationCall() throws Exception {
        checkedOut();
        when(aiService.analyzeFeedbackSentiment(anyString())).thenReturn(null);
        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "1").param("feedback", "mabaho"))
            .andExpect(view().name("feedback-success"));
        verify(notificationService, never()).negativeFeedback(any());
    }

    @Test
    void aNotificationFailureStillThanksTheVisitor() throws Exception {
        checkedOut();
        when(aiService.analyzeFeedbackSentiment(anyString())).thenReturn(new JSONObject(
            "{\"sentiment\":\"NEGATIVE\",\"critical_alert\":false,\"score\":0.6,\"topic_category\":\"Facilities\"}"));
        doThrow(new RuntimeException("boom")).when(notificationService).negativeFeedback(any());
        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "1").param("feedback", "mabaho"))
            .andExpect(view().name("feedback-success"));
    }
}
