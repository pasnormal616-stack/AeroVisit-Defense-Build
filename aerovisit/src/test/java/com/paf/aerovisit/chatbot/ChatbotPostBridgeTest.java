package com.paf.aerovisit.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** Java -> Python chat bridge: a POST with a JSON body, never a GET with the chat text in the URL. Local stub server, no Python or Gemini. */
class ChatbotPostBridgeTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> rawQuery = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AiAdvisorService service = new AiAdvisorService();

    @BeforeEach
    void startStubPythonService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); // a slow handler can't block stop()
        server.createContext("/chat", exchange -> {
            method.set(exchange.getRequestMethod());
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = "{\"type\":\"chat\",\"reply\":\"Roger that.\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        service.chatEndpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void chatTextGoesInAPostBodyNotTheUrl() throws Exception {
        String secret = "My name is Ana Cruz, email ana@example.com & ID 1234?";

        String reply = service.getAiResponse(secret, "SGT-K3J9X2QA");

        assertThat(reply).isEqualTo("Roger that.");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(rawQuery.get()).as("nothing in the URL").isNull();
        assertThat(contentType.get()).startsWith("application/json");
        JSONObject json = new JSONObject(body.get());
        assertThat(json.getString("message")).isEqualTo(secret);
        assertThat(json.getString("session_id")).isEqualTo("SGT-K3J9X2QA");
    }

    @Test
    void aTamperedSessionIdIsReplacedBeforeItIsSent() throws Exception {
        service.getAiResponse("hello", "civilian_terminal_1");

        assertThat(new JSONObject(body.get()).getString("session_id")).startsWith("ANON-");
    }

    @Test
    void aHungPythonServiceTimesOutWithTheOfflineReplyInsteadOfHoldingTheThread() {
        assertThat(AiAdvisorService.TIMEOUT).isEqualTo(java.time.Duration.ofSeconds(10));
        assertThat(new AiAdvisorService().timeout).isEqualTo(java.time.Duration.ofSeconds(10));

        server.removeContext("/chat");
        server.createContext("/chat", exchange -> {
            try {
                Thread.sleep(3_000); // Gemini or the Python service hanging
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        service.timeout = java.time.Duration.ofMillis(300);

        long start = System.nanoTime();
        String reply = service.getAiResponse("hello", "SGT-K3J9X2QA");

        assertThat(reply).isEqualTo("Sgt. Aero is currently away from the comms terminal. Please try again later.");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_000);
    }

    @Test
    void unicodeAndQuotesSurviveTheTrip() throws Exception {
        service.getAiResponse("Saan ang \"hangar\"? ✈️\nSalamat", "SGT-K3J9X2QA");

        assertThat(new JSONObject(body.get()).getString("message")).isEqualTo("Saan ang \"hangar\"? ✈️\nSalamat");
    }
}
