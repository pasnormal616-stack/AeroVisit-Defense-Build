package com.paf.aerovisit;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * ⏱️ Today at 12:00 noon, Manila time, as the app's Clock (ClockConfig). Tests that build time windows around "now"
 * import this instead of reading the real clock, so they run the same at any hour and are never skipped at night.
 * Use TODAY / TIME / NOW for every date and time the test builds.
 */
@TestConfiguration
public class FixedClock {

    public static final ZoneId ZONE = ZoneId.of("Asia/Manila");
    public static final LocalDate TODAY = LocalDate.now(ZONE);
    public static final LocalTime TIME = LocalTime.NOON;
    public static final LocalDateTime NOW = TODAY.atTime(TIME);
    public static final Clock CLOCK = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);

    @Bean
    @Primary
    public Clock fixedClock() {
        return CLOCK;
    }
}
