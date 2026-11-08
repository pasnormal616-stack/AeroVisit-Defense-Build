package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.repository.PortalSettingsRepository;

/** 📅 The earliest advance date: Manila's today + the Director's minimum days, around Manila midnight. */
class AdvanceBookingRulesTest {

    private static AdvanceBookingRules at(String instant) {
        AdvanceBookingRules rules = new AdvanceBookingRules();
        // A UTC clock on purpose: the rule must still count days in Manila (UTC+8)
        rules.clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return rules;
    }

    @Test
    void oneMinuteBeforeManilaMidnightTheEarliestDateIsTomorrow() {
        AdvanceBookingRules rules = at("2026-11-05T15:59:00Z"); // Nov 5, 11:59 PM in Manila
        assertThat(rules.today()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(rules.earliestDate()).isEqualTo(LocalDate.of(2026, 11, 6));
    }

    @Test
    void atManilaMidnightTheDayHasTurnedEvenThoughItIsStillTheFifthInUtc() {
        AdvanceBookingRules rules = at("2026-11-05T16:00:00Z"); // Nov 6, 12:00 AM in Manila
        assertThat(rules.today()).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(rules.earliestDate()).isEqualTo(LocalDate.of(2026, 11, 7));
    }

    @Test
    void theDirectorsSettingMovesTheEarliestDate() {
        AdvanceBookingRules rules = at("2026-11-05T02:00:00Z"); // Nov 5, 10:00 AM in Manila
        PortalSettingsRepository repo = mock(PortalSettingsRepository.class);
        PortalSettings s = new PortalSettings();
        s.setAdvanceMinDaysAhead(3);
        when(repo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.of(s));
        rules.settingsRepo = repo;
        assertThat(rules.minDays()).isEqualTo(3);
        assertThat(rules.earliestDate()).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    void theSettingIsAWholeNumberFrom1To30() {
        assertThat(AdvanceBookingRules.settingsError(0)).isNotNull();
        assertThat(AdvanceBookingRules.settingsError(31)).isNotNull();
        assertThat(AdvanceBookingRules.settingsError(1)).isNull();
        assertThat(AdvanceBookingRules.settingsError(30)).isNull();
        assertThat(AdvanceBookingRules.tooSoonMessage(1))
            .isEqualTo("Advance bookings must be made at least 1 day(s) ahead. For today, please choose Walk-in.");
    }
}
