package com.paf.aerovisit.service;

import com.paf.aerovisit.model.MuseumPrediction;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.json.JSONObject;

@Service 
public class PredictiveAdvisorService {

    private static final String API_BASE_URL = "http://localhost:8000";
    // AI service address (aerovisit.ai.base-url / AI_SERVICE_URL); tests point this at a local stub server
    @org.springframework.beans.factory.annotation.Value("${aerovisit.ai.base-url:" + API_BASE_URL + "}")
    String apiBaseUrl = API_BASE_URL;

    // ⏱️ Connect + response limit for every call to the Python service, so a hung AI service can never hold a
    // server thread forever (thread exhaustion). Past it each method returns its usual "AI offline" result.
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    Duration timeout = TIMEOUT; // tests shorten this

    // Reusing a single HttpClient is much faster for microservice communication
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    // Shared secret for the AI service (AI_INTERNAL_KEY on both services); blank (local dev, tests) = not sent
    @org.springframework.beans.factory.annotation.Value("${aerovisit.ai.internal-key:}")
    String internalKey = "";

    // 🤖 AI features switch (Settings): off = each method returns its usual "AI offline" result at once, no call made
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    AiSwitch aiSwitch;

    private boolean aiOff() {
        return aiSwitch != null && !aiSwitch.enabled();
    }

    // 🔌 A network failure or 502-504 pauses the AI features until the AI service answers again (AiSwitch), so the
    // next dashboard load or feedback form doesn't wait out the same time-out
    private void noteFailure(Exception e, String what) {
        if (aiSwitch != null && AiSwitch.unreachable(e)) aiSwitch.reportUnreachable(what);
    }

    private void noteStatus(int status, String what) {
        if (aiSwitch != null && AiSwitch.unreachable(status)) aiSwitch.reportUnreachable(what);
    }

    // Every call to the AI service starts here: address, time limit and the shared-secret header
    private HttpRequest.Builder aiRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout);
        return internalKey == null || internalKey.isBlank() ? builder : builder.header("X-Internal-Key", internalKey);
    }

    // --- DATA FUSION PREDICTION ENGINE ---
    public MuseumPrediction fetchTurnoutPrediction(int dayOfWeek, String weatherCondition, int preBooked) {
        if (aiOff()) return null;
        try {
            String url = apiBaseUrl + "/predict?day_of_week=" + dayOfWeek 
                         + "&weather=" + weatherCondition.replace(" ", "%20")
                         + "&pre_booked=" + preBooked;
            
            HttpRequest request = aiRequest(url).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject jsonResponse = new JSONObject(response.body());
                JSONObject briefing = jsonResponse.getJSONObject("briefing");
                
                MuseumPrediction prediction = new MuseumPrediction(
                    jsonResponse.getInt("total_prediction"),
                    jsonResponse.getInt("walk_ins"),
                    jsonResponse.optInt("min_walk_ins", 0),
                    jsonResponse.optInt("max_walk_ins", 0),
                    jsonResponse.optInt("confidence_percentage", 0),
                    jsonResponse.optString("weather", "Clear / Nominal"),
                    briefing.getString("security_condition"),
                    briefing.getString("personnel"),
                    briefing.getString("facility_protocol")
                );
                prediction.setBaselineEstimate("baseline".equals(jsonResponse.optString("data_source")));
                return prediction;
            }
            noteStatus(response.statusCode(), "forecast");
        } catch (Exception e) {
            System.err.println("AI Tactical Engine Offline: " + e.getMessage());
            noteFailure(e, "forecast");
        }
        return null;
    }

    // --- NLP FEEDBACK RADAR ENGINE ---
    public JSONObject analyzeFeedbackSentiment(String feedbackText) {
        if (aiOff()) return null;
        try {
            String encodedText = URLEncoder.encode(feedbackText, StandardCharsets.UTF_8);
            String url = apiBaseUrl + "/analyze?text=" + encodedText;
            
            HttpRequest request = aiRequest(url).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return new JSONObject(response.body());
            }
            noteStatus(response.statusCode(), "feedback analysis");
        } catch (Exception e) {
            System.err.println("NLP AI Offline: " + e.getMessage());
            noteFailure(e, "feedback analysis");
        }
        return null;
    }

    // --- SGT. AERO CHATBOT ENGINE ---
    public JSONObject chatWithSgtAero(String message, String sessionId) {
        if (aiOff()) return null;
        try {
            // 🔒 Same JSON POST contract as AiAdvisorService: the chat text never goes in the URL
            String body = new JSONObject().put("message", message).put("session_id", sessionId).toString();
            HttpRequest request = aiRequest(apiBaseUrl + "/chat")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return new JSONObject(response.body());
            }
            noteStatus(response.statusCode(), "chatbot");
        } catch (Exception e) {
            System.err.println("Sgt. Aero Offline: " + e.getMessage());
            noteFailure(e, "chatbot");
        }
        return null;
    }
    
    // --- FORCE RETRAIN ENGINE ---
    public boolean triggerAiRetrain() {
        if (aiOff()) return false;
        try {
            String url = apiBaseUrl + "/retrain";
            // The retrain endpoint is a POST request according to your Python file
            HttpRequest request = aiRequest(url)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            noteStatus(response.statusCode(), "forecast retraining");
            return response.statusCode() == 200;
        } catch (Exception e) {
            System.err.println("AI Retrain Failed: " + e.getMessage());
            noteFailure(e, "forecast retraining");
            return false;
        }
    }
}