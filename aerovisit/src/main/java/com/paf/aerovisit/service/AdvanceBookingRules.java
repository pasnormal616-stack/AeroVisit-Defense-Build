package com.paf.aerovisit.service;

import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 📅 ADVANCE BOOKINGS: how many days ahead an advance visit date must be (Director setting, 1-30, default 1). With 1, the
 * earliest advance date is tomorrow, so today is Walk-in only. "Today" is always Manila's, whatever zone the server runs
 * in. /save and Manage Booking enforce it; the booking calendar only mirrors it.
 */
@Service
public class AdvanceBookingRules {

    public static final int MIN_DAYS = 1, MAX_DAYS = 30;
    public static final ZoneId MANILA = ZoneId.of("Asia/Manila");

    // Optional, so test slices without a database still start; without it the default applies
    @Autowired(required = false)
    PortalSettingsRepository settingsRepo;

    Clock clock = Clock.system(MANILA); // tests use a fixed clock

    // ⏱️ The shared clock (ClockConfig) when there is one, so the booking page and the gate agree on "today"
    @Autowired(required = false)
    void useClock(Clock shared) {
        if (shared != null) clock = shared;
    }

    private PortalSettings settings() {
        if (settingsRepo == null) return new PortalSettings();
        try {
            return settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Advance booking setting could not be read; using the default. " + e.getMessage());
            return new PortalSettings();
        }
    }

    public LocalDate today() { return LocalDate.now(clock.withZone(MANILA)); }

    public int minDays() { return settings().advanceMinDays(); }

    // The earliest date an advance booking may be for
    public LocalDate earliestDate() { return today().plusDays(minDays()); }

    // Used when the service isn't there (test slices): the default rule, Manila time
    public static LocalDate fallbackEarliest() {
        return LocalDate.now(MANILA).plusDays(PortalSettings.DEFAULT_ADVANCE_MIN_DAYS);
    }

    public static String tooSoonMessage(int days) {
        return "Advance bookings must be made at least " + days + " day(s) ahead. For today, please choose Walk-in.";
    }

    public static String settingsError(int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            return "The minimum days ahead must be a whole number from " + MIN_DAYS + " to " + MAX_DAYS + ".";
        }
        return null;
    }

    // Saves the Director's setting (already validated)
    public void saveMinDays(int days) {
        PortalSettings s = settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
        s.setAdvanceMinDaysAhead(days);
        settingsRepo.save(s);
    }
}
