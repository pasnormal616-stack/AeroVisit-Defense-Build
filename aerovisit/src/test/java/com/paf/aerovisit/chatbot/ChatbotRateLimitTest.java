package com.paf.aerovisit.chatbot;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.paf.aerovisit.config.SecurityConfig;

/**
 * /api/chatbot/ask: 10 questions per visitor per minute; the 11th gets 429 and never reaches the Python service / Gemini.
 * Each test uses its own visitor IPs, because the controller (and its limiter) is shared by the cached test context.
 */
@WebMvcTest(AiAdvisorController.class)
@Import(SecurityConfig.class)
@WithAnonymousUser
class ChatbotRateLimitTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private AiAdvisorService aiAdvisorService;

    // A visitor coming in through ngrok: the agent connects from localhost and adds the visitor's IP
    private static MockHttpServletRequestBuilder ask(String visitorIp, String message) {
        return get("/api/chatbot/ask").param("message", message).param("session_id", "SGT-RATE0001")
            .with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
            .header("X-Forwarded-For", visitorIp);
    }

    @Test
    void theEleventhQuestionInAMinuteGets429AndNeverReachesGemini() throws Exception {
        when(aiAdvisorService.getAiResponse(anyString(), anyString())).thenReturn("Roger that.");

        for (int i = 1; i <= AiAdvisorController.MAX_REQUESTS_PER_MINUTE; i++) {
            mvc.perform(ask("198.51.100.10", "question " + i))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("Roger that."));
        }
        mvc.perform(ask("198.51.100.10", "question 11"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.reply").value("Rate limit exceeded. Please wait a moment."))
            .andExpect(header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]?")));

        verify(aiAdvisorService, times(10)).getAiResponse(anyString(), eq("SGT-RATE0001"));
        verify(aiAdvisorService, times(0)).getAiResponse(eq("question 11"), anyString());
    }

    @Test
    void anotherVisitorBehindTheSameProxyIsNotAffected() throws Exception {
        when(aiAdvisorService.getAiResponse(anyString(), anyString())).thenReturn("Roger that.");
        for (int i = 0; i < 11; i++) mvc.perform(ask("198.51.100.20", "spam " + i));

        mvc.perform(ask("198.51.100.21", "Is the museum open today?")).andExpect(status().isOk());
    }

    @Test
    void forgingAnXForwardedForHopDoesNotEscapeTheLimit() throws Exception {
        when(aiAdvisorService.getAiResponse(anyString(), anyString())).thenReturn("Roger that.");
        for (int i = 0; i < 10; i++) {
            // a new fake first hop each time; the proxy-added last hop (the real IP) stays the same
            mvc.perform(ask("10.0.0." + i + ", 198.51.100.30", "q" + i)).andExpect(status().isOk());
        }
        mvc.perform(ask("10.9.9.9, 198.51.100.30", "q11")).andExpect(status().isTooManyRequests());
    }
}
