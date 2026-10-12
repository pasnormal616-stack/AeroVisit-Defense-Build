package com.paf.aerovisit;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;

import jakarta.annotation.PostConstruct;

/** The app pins the JVM to Manila time at startup, so a UTC cloud server doesn't shift dates, schedules or gate windows. */
class TimeZonePinTest {

    @Test
    void startupPinsTheJvmToManilaEvenOnAUtcServer() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            new AerovisitApplication().pinTimeZone();

            assertThat(TimeZone.getDefault().getID()).isEqualTo("Asia/Manila");
            assertThat(TimeZone.getDefault().getRawOffset()).isEqualTo(8 * 60 * 60 * 1000);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void itRunsAsAPostConstructHook() throws Exception {
        Method pin = AerovisitApplication.class.getMethod("pinTimeZone");
        assertThat(pin.isAnnotationPresent(PostConstruct.class)).isTrue();
    }
}
