package com.paf.aerovisit.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;

/** Chatbot: each browser's session_id reaches the Python service, so visitors never share one Gemini conversation. */
@WebMvcTest(AiAdvisorController.class)
@Import(SecurityConfig.class)
@WithAnonymousUser
class ChatbotSessionIsolationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private AiAdvisorService aiAdvisorService;

    @Test
    void eachBrowsersSessionIdIsPassedThrough() throws Exception {
        when(aiAdvisorService.getAiResponse(anyString(), anyString())).thenAnswer(inv -> "reply for " + inv.getArgument(1));

        mvc.perform(get("/api/chatbot/ask").param("message", "My name is Ana").param("session_id", "SGT-AAAA1111"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reply").value("reply for SGT-AAAA1111"));
        mvc.perform(get("/api/chatbot/ask").param("message", "What is my name?").param("session_id", "SGT-BBBB2222"))
            .andExpect(jsonPath("$.reply").value("reply for SGT-BBBB2222"));

        verify(aiAdvisorService).getAiResponse("My name is Ana", "SGT-AAAA1111");
        verify(aiAdvisorService).getAiResponse("What is my name?", "SGT-BBBB2222");
    }

    @Test
    void missingSessionIdReachesTheServiceAsNull() throws Exception {
        mvc.perform(get("/api/chatbot/ask").param("message", "hello")).andExpect(status().isOk());
        verify(aiAdvisorService).getAiResponse(eq("hello"), isNull());
    }

    @Test
    void wellFormedIdsAreKept() {
        assertThat(AiAdvisorService.resolveSessionId("SGT-K3J9X2QA")).isEqualTo("SGT-K3J9X2QA");
        assertThat(AiAdvisorService.resolveSessionId("SGT-AB")).isEqualTo("SGT-AB");
        assertThat(AiAdvisorService.resolveSessionId("a_b-C_d-e_f")).isEqualTo("a_b-C_d-e_f");
    }

    @Test
    void missingTamperedOrLegacySharedIdsGetAFreshPrivateSession() {
        String[] bad = {null, "", "   ", "SGT", "x".repeat(65), "SGT-1&session_id=SGT-2", "../etc", "SGT 123456",
                        "civilian_terminal_1", "CIVILIAN_TERMINAL_1"};
        java.util.Set<String> issued = new java.util.HashSet<>();
        for (String id : bad) {
            String resolved = AiAdvisorService.resolveSessionId(id);
            assertThat(resolved).as(String.valueOf(id)).startsWith("ANON-").isNotEqualTo(id);
            issued.add(resolved);
        }
        assertThat(issued).as("never a shared fallback").hasSize(bad.length);
    }

    @Test
    void pythonRequestCarriesTheSessionIdInAJsonBody() throws Exception {
        org.json.JSONObject body = new org.json.JSONObject(AiAdvisorService.chatBody("Is it open today? 50% sure \"Ana\"\nok", "SGT-K3J9X2QA"));
        assertThat(body.getString("message")).isEqualTo("Is it open today? 50% sure \"Ana\"\nok");
        assertThat(body.getString("session_id")).isEqualTo("SGT-K3J9X2QA");
        assertThat(body.length()).as("only message + session_id").isEqualTo(2);
        assertThat(AiAdvisorService.CHAT_URL).isEqualTo("http://localhost:8000/chat").doesNotContain("?");
    }
}
