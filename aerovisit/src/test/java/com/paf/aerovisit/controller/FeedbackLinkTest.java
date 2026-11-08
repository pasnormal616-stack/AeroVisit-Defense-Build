package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.FeedbackEmails;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * 📝 The feedback link opens once the visit is over: checked out, or still CHECKED-IN after the window-end feedback email
 * went out (they left without being checked out). Still one feedback per booking. Plus the Director's delay setting.
 */
@WebMvcTest({VisitorPortalController.class, FeedbackSettingsController.class})
@Import({SecurityConfig.class, FeedbackEmails.class})
class FeedbackLinkTest {

    private static final String TOKEN = "feedback-token-1";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private PortalSettingsRepository settingsRepo;

    private Visitor visitor(String status, LocalDateTime emailSent) {
        Visitor v = new Visitor();
        v.setId(1L);
        v.setName("Juan Dela Cruz");
        v.setStatus(status);
        v.setVisitDate(LocalDate.now());
        v.setSecureToken(TOKEN);
        v.setFeedbackEmailSentAt(emailSent);
        when(repo.findBySecureToken(TOKEN)).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    void aVisitorStillShownInsideCanGiveFeedbackOnceTheEmailWentOut() throws Exception {
        Visitor v = visitor("CHECKED-IN", LocalDateTime.now().minusMinutes(10));

        mvc.perform(get("/leave-feedback").param("token", TOKEN)).andExpect(view().name("feedback"));
        mvc.perform(post("/submit-feedback").with(csrf()).param("token", TOKEN).param("rating", "5").param("feedback", "Great tour!"))
           .andExpect(status().isOk());
        assertThat(v.getRating()).isEqualTo(5);
        assertThat(v.getFeedback()).isEqualTo("Great tour!");
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN"); // feedback never checks anyone out

        // still one feedback per booking
        mvc.perform(post("/submit-feedback").with(csrf()).param("token", TOKEN).param("rating", "1").param("feedback", "again"))
           .andExpect(view().name("feedback-error"))
           .andExpect(model().attribute("error", "You have already submitted an After-Action Report for this visit. Thank you!"));
        assertThat(v.getRating()).isEqualTo(5);
    }

    @Test
    void someoneStillInsideBeforeTheEmailCannotReviewEarly() throws Exception {
        visitor("CHECKED-IN", null);
        mvc.perform(get("/leave-feedback").param("token", TOKEN))
           .andExpect(model().attribute("error", "Invalid link or your visit has not been completed yet."));
        mvc.perform(post("/submit-feedback").with(csrf()).param("token", TOKEN).param("rating", "5").param("feedback", "x"))
           .andExpect(view().name("feedback-error"));
        verify(repo, never()).save(any());
    }

    @Test
    void aCheckedOutVisitorCanStillGiveFeedbackAsBefore() throws Exception {
        visitor("CHECKED-OUT", null);
        mvc.perform(get("/leave-feedback").param("token", TOKEN)).andExpect(view().name("feedback"));
        visitor("NO_SHOW", LocalDateTime.now());
        mvc.perform(get("/leave-feedback").param("token", TOKEN)).andExpect(view().name("feedback-error"));
    }

    // --- The Director's delay setting ---

    @Test
    void theDirectorSetsTheDelayWithinRangeAndAGuardCannot() throws Exception {
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        mvc.perform(post("/admin/settings/feedback").with(csrf()).with(user("director").roles("DIRECTOR")).param("feedbackDelayMinutes", "181"))
           .andExpect(flash().attribute("error", "The feedback email delay must be a whole number of minutes from 0 to 180."));
        mvc.perform(post("/admin/settings/feedback").with(csrf()).with(user("guard").roles("GUARD")).param("feedbackDelayMinutes", "45"))
           .andExpect(status().isForbidden());
        verify(settingsRepo, never()).save(any());

        mvc.perform(post("/admin/settings/feedback").with(csrf()).with(user("director").roles("DIRECTOR")).param("feedbackDelayMinutes", "45"))
           .andExpect(redirectedUrl("/admin/settings#feedbackSettings"))
           .andExpect(flash().attribute("success", "Feedback email setting saved: sent 45 minutes after a visit's time window ends."));
        ArgumentCaptor<PortalSettings> saved = ArgumentCaptor.forClass(PortalSettings.class);
        verify(settingsRepo).save(saved.capture());
        assertThat(saved.getValue().feedbackDelay()).isEqualTo(45);
    }
}
