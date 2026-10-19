package com.paf.aerovisit.chatbot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.service.AiSwitch;

/** With the AI switched off, Sgt. Aero answers at once that it is off duty, without calling the AI service. */
class AiAdvisorOffTest {

    @Test
    void theChatbotSaysItIsOffDuty() {
        AiAdvisorService chat = new AiAdvisorService();
        chat.aiSwitch = mock(AiSwitch.class);
        when(chat.aiSwitch.enabled()).thenReturn(false);
        assertThat(chat.getAiResponse("hello", "SGT-ABCDEF12")).isEqualTo(AiAdvisorService.OFF_DUTY_REPLY);
    }
}
