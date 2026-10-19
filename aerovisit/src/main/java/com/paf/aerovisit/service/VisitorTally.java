package com.paf.aerovisit.service;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Visitor Tally (Entire Database tab): visitors per month (rows) and year (columns), with totals, like the museum's
 * paper tally sheet. A month comes from the archive (paper records from before AeroVisit) when it has a row there,
 * otherwise from AeroVisit's own check-ins once the system was in use. A month neither covers is "no record" (null),
 * never a made-up 0. No Spring or database here: the controller passes in both sets of counts.
 */
public final class VisitorTally {

    private VisitorTally() {}

    /** Years shown before the current one (in 2026: 2022-2025 + 2026; in 2027: 2023-2026 + 2027). */
    public static final int PAST_YEARS = 4;

    /** One month across the years; a null cell or total means no record. */
    public record Row(String month, List<Integer> cells, Integer total) {}

    public record View(List<Integer> years, List<Row> rows, List<Integer> yearTotals, Integer grandTotal) {}

    /**
     * @param archive   paper records by month
     * @param live      AeroVisit visitors (checked in or out, group size counted) by month
     * @param liveFrom  month of AeroVisit's first real booking (null: none yet); live counts start there
     * @param thisMonth the current month; later months have no record yet
     */
    public static View build(Map<YearMonth, Integer> archive, Map<YearMonth, Integer> live, YearMonth liveFrom, YearMonth thisMonth) {
        // Rolling span: this year and the PAST_YEARS before it. Older years are only hidden (their counts stay stored)
        int firstYear = thisMonth.getYear() - PAST_YEARS;

        List<Integer> years = new ArrayList<>();
        for (int y = firstYear; y <= thisMonth.getYear(); y++) years.add(y);

        List<Row> rows = new ArrayList<>();
        Integer[] yearTotals = new Integer[years.size()];
        for (Month month : Month.values()) {
            List<Integer> cells = new ArrayList<>();
            Integer rowTotal = null;
            for (int i = 0; i < years.size(); i++) {
                Integer value = monthValue(YearMonth.of(years.get(i), month), archive, live, liveFrom, thisMonth);
                cells.add(value);
                rowTotal = plus(rowTotal, value);
                yearTotals[i] = plus(yearTotals[i], value);
            }
            rows.add(new Row(month.getDisplayName(TextStyle.FULL, Locale.ENGLISH), cells, rowTotal));
        }
        Integer grandTotal = null;
        for (Integer t : yearTotals) grandTotal = plus(grandTotal, t);
        return new View(years, rows, java.util.Arrays.asList(yearTotals), grandTotal); // a year can be all "no record" (null)
    }

    static Integer monthValue(YearMonth ym, Map<YearMonth, Integer> archive, Map<YearMonth, Integer> live,
                              YearMonth liveFrom, YearMonth thisMonth) {
        if (archive.containsKey(ym)) return archive.get(ym);
        if (liveFrom != null && !ym.isBefore(liveFrom) && !ym.isAfter(thisMonth)) return live.getOrDefault(ym, 0);
        return null;
    }

    private static Integer plus(Integer total, Integer value) {
        if (value == null) return total;
        return total == null ? value : total + value;
    }

    /** The same table as CSV (Excel-friendly); "no record" cells are left empty. */
    public static String toCsv(View view) {
        StringBuilder csv = new StringBuilder("Month");
        for (Integer y : view.years()) csv.append(",Year ").append(y);
        csv.append(",Total\r\n");
        for (Row row : view.rows()) {
            csv.append(row.month());
            for (Integer cell : row.cells()) csv.append(',').append(cell == null ? "" : cell);
            csv.append(',').append(row.total() == null ? "" : row.total()).append("\r\n");
        }
        csv.append("TOTAL");
        for (Integer t : view.yearTotals()) csv.append(',').append(t == null ? "" : t);
        csv.append(',').append(view.grandTotal() == null ? "" : view.grandTotal()).append("\r\n");
        return csv.toString();
    }
}
