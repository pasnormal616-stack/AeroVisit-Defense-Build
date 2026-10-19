package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Visitor Tally matrix: archive months, AeroVisit months, "no record" months and the totals. No Spring, no database. */
class VisitorTallyTest {

    private static final YearMonth NOW = YearMonth.of(2026, 10);

    private static Map<YearMonth, Integer> months(Object... pairs) {
        Map<YearMonth, Integer> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((YearMonth) pairs[i], (Integer) pairs[i + 1]);
        return map;
    }

    private static Integer cell(VisitorTally.View view, int month, int year) {
        return view.rows().get(month - 1).cells().get(view.years().indexOf(year));
    }

    @Test
    void showsThisYearAndTheFourBeforeItAndRowsAreTheTwelveMonths() {
        VisitorTally.View view = VisitorTally.build(months(YearMonth.of(2022, 12), 467), Map.of(), null, NOW);
        assertThat(view.years()).containsExactly(2022, 2023, 2024, 2025, 2026);
        assertThat(view.rows()).hasSize(12);
        assertThat(view.rows().get(0).month()).isEqualTo("January");
        assertThat(view.rows().get(11).month()).isEqualTo("December");
    }

    @Test
    void archiveMonthsAreShownAndMonthsWithoutAnyRecordAreNull() {
        VisitorTally.View view = VisitorTally.build(months(YearMonth.of(2025, 3), 1508, YearMonth.of(2025, 4), 0),
                Map.of(), YearMonth.of(2026, 10), NOW);
        assertThat(cell(view, 3, 2025)).isEqualTo(1508);
        assertThat(cell(view, 4, 2025)).isZero();          // closed month in the paper records
        assertThat(cell(view, 1, 2026)).isNull();          // before AeroVisit, after the paper records
        assertThat(cell(view, 11, 2026)).isNull();         // after this month
    }

    @Test
    void aeroVisitMonthsCountFromTheFirstBookingAndAQuietMonthIsZero() {
        VisitorTally.View view = VisitorTally.build(Map.of(), months(YearMonth.of(2026, 9), 12),
                YearMonth.of(2026, 8), NOW);
        assertThat(cell(view, 7, 2026)).isNull();          // before the first booking
        assertThat(cell(view, 8, 2026)).isZero();          // in use, nobody checked in
        assertThat(cell(view, 9, 2026)).isEqualTo(12);
        assertThat(cell(view, 10, 2026)).isZero();
    }

    @Test
    void anArchiveMonthWinsOverAeroVisitCountsForTheSameMonth() {
        VisitorTally.View view = VisitorTally.build(months(NOW, 100), months(NOW, 7), NOW, NOW);
        assertThat(cell(view, 10, 2026)).isEqualTo(100);
    }

    @Test
    void rowYearAndGrandTotalsSkipMonthsWithoutRecords() {
        VisitorTally.View view = VisitorTally.build(
                months(YearMonth.of(2024, 5), 77, YearMonth.of(2025, 5), 2536, YearMonth.of(2025, 2), 21),
                months(YearMonth.of(2026, 10), 5), YearMonth.of(2026, 10), NOW);
        assertThat(view.rows().get(4).total()).isEqualTo(2613);          // May: 77 + 2,536, like the paper
        assertThat(view.rows().get(0).total()).isNull();                  // January: no record in any year
        assertThat(view.yearTotals()).containsExactly(null, null, 77, 2557, 5);
        assertThat(view.grandTotal()).isEqualTo(2639);
    }

    @Test
    void anEmptySystemShowsTheFiveYearsWithNoRecords() {
        VisitorTally.View view = VisitorTally.build(Map.of(), Map.of(), null, NOW);
        assertThat(view.years()).containsExactly(2022, 2023, 2024, 2025, 2026);
        assertThat(view.grandTotal()).isNull();
    }

    @Test
    void nextYearTheOldestYearIsHiddenAndLeftOutOfTheTotals() {
        Map<YearMonth, Integer> archive = months(YearMonth.of(2022, 12), 467, YearMonth.of(2023, 7), 426);
        VisitorTally.View view = VisitorTally.build(archive, Map.of(), null, YearMonth.of(2027, 1));
        assertThat(view.years()).containsExactly(2023, 2024, 2025, 2026, 2027);
        assertThat(view.grandTotal()).isEqualTo(426);                     // 2022's 467 is still stored, just not shown
        assertThat(archive).containsKey(YearMonth.of(2022, 12));
    }

    @Test
    void csvHasAHeaderTwelveMonthsAndATotalRowWithEmptyNoRecordCells() {
        VisitorTally.View view = VisitorTally.build(months(YearMonth.of(2025, 1), 0, YearMonth.of(2025, 3), 1508),
                months(YearMonth.of(2026, 10), 4), YearMonth.of(2026, 10), NOW);
        String[] lines = VisitorTally.toCsv(view).split("\r\n");
        assertThat(lines).hasSize(14);
        assertThat(lines[0]).isEqualTo("Month,Year 2022,Year 2023,Year 2024,Year 2025,Year 2026,Total");
        assertThat(lines[1]).isEqualTo("January,,,,0,,0");
        assertThat(lines[3]).isEqualTo("March,,,,1508,,1508");
        assertThat(lines[2]).isEqualTo("February,,,,,,");
        assertThat(lines[10]).isEqualTo("October,,,,,4,4");
        assertThat(lines[13]).isEqualTo("TOTAL,,,,1508,4,1512");
    }
}
