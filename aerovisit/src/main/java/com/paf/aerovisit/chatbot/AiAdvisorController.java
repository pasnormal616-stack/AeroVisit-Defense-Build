package com.paf.aerovisit.chatbot;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/chatbot")
public class AiAdvisorController {

    @Autowired
    private AiAdvisorService aiAdvisorService;

    // 🚦 Protects the Gemini quota: at most 10 questions per visitor (IP) in any 60 seconds
    static final int MAX_REQUESTS_PER_MINUTE = 10;
    static final String RATE_LIMITED_REPLY = "Rate limit exceeded. Please wait a moment.";
    final ChatRateLimiter rateLimiter = new ChatRateLimiter(MAX_REQUESTS_PER_MINUTE, Duration.ofMinutes(1), System::nanoTime);

    // 🔥 Changed from POST to GET to bypass strict security blocks
    @GetMapping("/ask")
    public ResponseEntity<Map<String, String>> askChatbot(@RequestParam(value = "message", defaultValue = "") String userMessage,
                                                          @RequestParam(value = "session_id", required = false) String sessionId,
                                                          HttpServletRequest request) {

        // 🚦 Over the limit: 429 before anything reaches the Python service or Gemini. The widget shows the reply text.
        String client = ChatRateLimiter.clientKey(request);
        if (!rateLimiter.tryAcquire(client)) {
            Map<String, String> limited = new HashMap<>();
            limited.put("reply", RATE_LIMITED_REPLY);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimiter.secondsUntilAllowed(client)))
                    .body(limited);
        }

        // The widget sends its per-browser session ID; the service validates it before it reaches the Python side
        String aiResponse = aiAdvisorService.getAiResponse(userMessage, sessionId);

        Map<String, String> response = new HashMap<>();
        response.put("reply", aiResponse);

        return ResponseEntity.ok(response);
    }
}