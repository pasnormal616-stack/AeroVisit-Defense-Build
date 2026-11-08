package com.paf.aerovisit.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// ⏱️ The one clock for "today" and "now" at the gate, on the booking page and on the payment pages: the system clock
// (pinned to Manila when the app starts). Classes take it as an optional bean, so a test slice without it still uses the
// system clock, and a test can swap in a fixed time of day (FixedClock in the tests) instead of skipping at night.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    // The injected clock, or the system clock when there is none (null-safe for optional injection)
    public static Clock or(Clock clock) {
        return clock != null ? clock : Clock.systemDefaultZone();
    }
}
