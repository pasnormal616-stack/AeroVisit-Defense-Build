package com.paf.aerovisit.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.repository.PortalSettingsRepository;

/**
 * 🤖 AI features switch (Settings page, Director, confirmed with the emailed security code). When OFF, nothing calls
 * the Python AI service: bookings go to manual review at once, and the chatbot, forecast, feedback analysis and AI
 * roster are hidden. After switching ON, the features wait (STARTING) until the AI service answers again, so nobody
 * sits through time-outs while its server boots. The choice is saved in PortalSettings and survives restarts.
 */
@Service
public class AiSwitch {

    public enum State { ON, STARTING, OFF }

    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    // Optional, so test slices without a database still start; without it the AI is always on
    @Autowired(required = false)
    PortalSettingsRepository settingsRepo;

    @Value("${aerovisit.ai.base-url:http://localhost:8000}")
    String apiBaseUrl = "http://localhost:8000";

    private volatile Boolean wanted;            // the Director's choice, read from the database once
    private volatile boolean reachable = true;  // false from switching ON until the AI service first answers

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(PROBE_TIMEOUT).build();

    /** True when the AI features may be used right now: switched on, and the AI service has answered since. */
    public boolean enabled() {
        return wanted() && reachable;
    }

    public State state() {
        if (!wanted()) return State.OFF;
        return reachable ? State.ON : State.STARTING;
    }

    /** The Director's choice (on until it is ever switched off). */
    public boolean wanted() {
        Boolean w = wanted;
        if (w == null) {
            w = settingsRepo == null || settingsRepo.findById(PortalSettings.SINGLETON_ID).map(PortalSettings::isAiEnabled).orElse(true);
            wanted = w;
        }
        return w;
    }

    public synchronized void set(boolean on) {
        if (settingsRepo != null) {
            PortalSettings settings = settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
            settings.setAiEnabled(on);
            settingsRepo.save(settings);
        }
        wanted = on;
        reachable = false; // OFF: nothing is used; ON: wait for the first answer (probeWhileStarting)
        System.out.println("🤖 AI features switched " + (on ? "ON (waiting for the AI service to answer)" : "OFF"));
    }

    // Every 30 s while STARTING: the features come back on their own once the AI service answers
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void probeWhileStarting() {
        if (wanted() && !reachable && probe()) {
            reachable = true;
            System.out.println("🤖 AI service is answering again: AI features are ON");
        }
    }

    // Any answer below 500 from the AI service means its app is up (a 404 or 401 still comes from FastAPI);
    // a 502/503/504 is the web server in front of it while the app is still starting
    boolean probe() {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(apiBaseUrl + "/")).timeout(PROBE_TIMEOUT).GET().build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() < 500;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
