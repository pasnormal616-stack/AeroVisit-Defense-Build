package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * 🗓️ Visitor calendar: days shown in two months (Nov 2-6 at the bottom of October) must still highlight when picked
 * after pressing "next". FullCalendar keeps those cells and resets their classes without mounting them again, so the
 * pickable / picked classes must come from dayCellClassNames (re-applied on every redraw), not from dayCellDidMount.
 */
class CalendarDayPickTest {

    private static String register() throws Exception {
        return Files.readString(Path.of("src/main/resources/templates/register.html"));
    }

    @Test
    void pickableAndPickedClassesAreReappliedOnEveryRedraw() throws Exception {
        String html = register();
        assertThat(html).contains("dayCellClassNames: function (arg) {",
                "return date === pickedDate ? ['vp-day-pick', 'is-picked'] : ['vp-day-pick'];");
        String mount = html.substring(html.indexOf("dayCellDidMount: function (arg) {"), html.indexOf("calendar.render();"));
        assertThat(mount).doesNotContain("classList.add('vp-day-pick')");
    }

    @Test
    void anEarlierPickIsClearedEvenIfItsCellLostItsPickableClass() throws Exception {
        assertThat(register()).doesNotContain("querySelectorAll('.vp-day-pick.is-picked')")
                .contains("calendarEl.querySelectorAll('.is-picked')");
    }
}
