package com.paf.aerovisit.chatbot;

import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Duration;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

@Service
public class AiAdvisorService {

    private static final String API_BASE_URL = "http://localhost:8000";

    // 🔒 SESSION ISOLATION: the chat widget's per-browser ID (e.g. SGT-K3J9X2QA). Letters, digits, '_' and '-' only.
    private static final java.util.regex.Pattern SESSION_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{6,64}");
    // The old shared ID every visitor used to get; its chat_logs history mixes many visitors, so it is never reused
    private static final String LEGACY_SHARED_SESSION = "civilian_terminal_1";

    public String getAiResponse(String userMessage, String requestedSessionId) {
        // Each visitor's browser keeps its own Gemini conversation (and chat_logs history) on the Python side
        String sessionId = resolveSessionId(requestedSessionId);

        try {
            // 🔒 PRIVACY: the chat text travels in a POST body, never in the URL, so it can't end up in access/proxy logs
            // ⏱️ Never wait on the Python service forever: a hung call would hold a server thread (thread exhaustion)
            HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatEndpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(chatBody(userMessage, sessionId), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Parse the JSON payload sent back from Python
                JSONObject jsonResponse = new JSONObject(response.body());
                
                // Check if Python sent back an error type
                if (jsonResponse.has("type") && "error".equals(jsonResponse.getString("type"))) {
                    System.err.println("Chatbot Python Error: " + jsonResponse.getString("reply"));
                }
                
                return jsonResponse.getString("reply");
            } else {
                return "Comm link degraded. Command server returned code: " + response.statusCode();
            }

        } catch (Exception e) {
            System.err.println("AI Chatbot Engine Offline: " + e.getMessage());
            return "Sgt. Aero is currently away from the comms terminal. Please try again later.";
        }
    }

    // The browser's session ID when it is well-formed; otherwise a fresh one-off ID, so a missing or tampered ID
    // gets a blank conversation and never lands in another visitor's (or the old shared) history
    static String resolveSessionId(String requested) {
        if (requested != null && SESSION_ID.matcher(requested).matches() && !LEGACY_SHARED_SESSION.equalsIgnoreCase(requested)) {
            return requested;
        }
        return "ANON-" + java.util.UUID.randomUUID();
    }

    // Python POST /chat endpoint (no query string: everything goes in the JSON body)
    static final String CHAT_URL = API_BASE_URL + "/chat";
    String chatEndpoint = CHAT_URL; // tests point this at a local stub server

    // AI service address from aerovisit.ai.base-url (AI_SERVICE_URL); http://localhost:8000 when not set
    @org.springframework.beans.factory.annotation.Value("${aerovisit.ai.base-url:" + API_BASE_URL + "}")
    void setApiBaseUrl(String baseUrl) {
        chatEndpoint = baseUrl + "/chat";
    }

    // ⏱️ Connect + response limit for the Python service; past it the visitor gets the "away from the comms terminal" reply
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    Duration timeout = TIMEOUT; // tests shorten this

    // JSON body for POST /chat, matching main.py's ChatRequest model; JSONObject escapes quotes, newlines and unicode
    static String chatBody(String userMessage, String sessionId) {
        return new JSONObject()
                .put("message", userMessage == null ? "" : userMessage)
                .put("session_id", sessionId)
                .toString();
    }
}