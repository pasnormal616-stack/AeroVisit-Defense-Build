package com.paf.aerovisit.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.service.AiSwitch;

/** 🔌 Sgt. Aero with its AI server down: one "away" reply, then off-duty replies at once until the server is back. */
class ChatbotUnreachableTest {

    @Test
    void afterTheFirstFailedMessageTheNextOnesAnswerAtOnce() throws Exception {
        String dead;
        try (ServerSocket s = new ServerSocket(0)) {
            dead = "http://127.0.0.1:" + s.getLocalPort() + "/chat";
        }
        AiAdvisorService chat = new AiAdvisorService();
        chat.chatEndpoint = dead;
        chat.aiSwitch = new AiSwitch(); // no database: switched on

        assertThat(chat.getAiResponse("hello", "SGT-ABCDEF12")).startsWith("Sgt. Aero is currently away");
        assertThat(chat.aiSwitch.state()).isEqualTo(AiSwitch.State.STARTING);
        assertThat(chat.getAiResponse("still there?", "SGT-ABCDEF12")).isEqualTo(AiAdvisorService.OFF_DUTY_REPLY);
    }
}
