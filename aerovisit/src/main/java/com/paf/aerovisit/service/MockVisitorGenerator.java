package com.paf.aerovisit.service;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.util.ReferenceIdGenerator;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

// Builds the simulated visitor history behind /admin/inject-mock-data. It only creates Visitor objects;
// the controller saves them. Every mock visitor has an email ending in MOCK_EMAIL_DOMAIN, which is how
// VisitorRepository.deleteMockVisitors() finds them again without touching real bookings.
public final class MockVisitorGenerator {

    public static final String MOCK_EMAIL_DOMAIN = "@mock.aerovisit";

    // Purpose the dashboard already hides from the visitor tables and leaves out of the funnel totals
    public static final String HIDDEN_PURPOSE = "Historical Baseline";

    // Days before today that get ordinary, visible visitors: the same 30 days the analytics trend chart shows
    public static final int VISIBLE_DAYS = 30;

    // Share of arrivals per opening hour (08:00 to 16:00): a late-morning peak and a smaller mid-afternoon one
    private static final int[] HOUR_WEIGHTS = { 5, 12, 20, 12, 5, 10, 18, 11, 7 };
    private static final int FIRST_HOUR = 8;

    private static final String[] FIRST_NAMES = { "Juan", "Maria", "Jose", "Ana", "Carlo", "Liza", "Mark", "Grace", "Paolo", "Jenny",
        "Miguel", "Rosa", "Daniel", "Carmela", "Rafael", "Theresa", "Noel", "Angelica", "Ramon", "Kristine" };
    private static final String[] LAST_NAMES = { "Dela Cruz", "Santos", "Reyes", "Garcia", "Mendoza", "Bautista", "Ramos", "Aquino",
        "Villanueva", "Castillo", "Navarro", "Domingo", "Salazar", "Mercado", "Flores", "Torres", "Gonzales", "Pascual" };

    // Purposes and visitor types offered by the public booking form; repeats make a value more likely
    private static final String[] PURPOSES = { "Educational Tour", "Educational Tour", "Recreation", "Recreation", "Recreation",
        "Research", "Official Business" };
    private static final String[] VISITOR_TYPES = { "Private Individuals", "Private Individuals", "Private Individuals", "Private Individuals",
        "Dependent of Active AFP Personnel", "Active Military Personnel", "AFP Civilian Personnel", "Retired Military Personnel",
        "Other Government Agencies" };

    private static final String[][] FEEDBACK = {
        { "The tour felt rushed and some displays had no labels." },
        { "Good collection, but the hangar was quite warm in the afternoon.", "Nice exhibits. Parking was hard to find." },
        { "Very organized and secure.", "Great educational tour for students.", "The aircraft park was worth the visit." },
        { "Exceptional PAF history display.", "The aircraft hangars were amazing.", "Highly disciplined and helpful staff." } };

    private MockVisitorGenerator() {}

    // One visitor per person, for every day from 'start' up to yesterday, skipping 'closedDates'.
    // Weekdays draw 15 to 40 arrivals and weekends 50 to 120. 'usedReferenceIds' holds the Reference IDs already
    // taken and is added to, so no two bookings share one.
    public static List<Visitor> generate(LocalDate start, LocalDate today, Set<LocalDate> closedDates,
                                         Set<String> usedReferenceIds, Random rand) {
        List<Visitor> visitors = new ArrayList<>();
        LocalDate visibleFrom = today.minusDays(VISIBLE_DAYS - 1);
        int sequence = 0;

        for (LocalDate date = start; date.isBefore(today); date = date.plusDays(1)) {
            if (closedDates.contains(date)) continue;

            boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
            int arrivals = weekend ? 50 + rand.nextInt(71) : 15 + rand.nextInt(26);
            boolean visible = !date.isBefore(visibleFrom);

            for (int i = 0; i < arrivals; i++) {
                Visitor v = newVisitor(date, ++sequence, visible, usedReferenceIds, rand);
                LocalDateTime checkIn = date.atTime(arrivalHour(rand), rand.nextInt(60));
                LocalDateTime checkOut = checkIn.plusMinutes(45 + rand.nextInt(106));
                LocalDateTime closing = date.atTime(17, 30);
                v.setStatus("CHECKED-OUT");
                v.setCheckedInBy("Gate Guard (Mock)");
                v.setCheckInTime(checkIn);
                v.setCheckOutTime(checkOut.isAfter(closing) ? closing : checkOut);

                // About 1 visible visitor in 12 leaves feedback, mostly 4 and 5 stars
                if (visible && rand.nextInt(12) == 0) {
                    int roll = rand.nextInt(100);
                    int rating = roll < 50 ? 5 : roll < 85 ? 4 : roll < 97 ? 3 : 2;
                    String[] comments = FEEDBACK[rating - 2];
                    v.setRating(rating);
                    v.setFeedback(comments[rand.nextInt(comments.length)]);
                }
                visitors.add(v);
            }

            // The visible month also gets requests that never arrived, so the funnel is not a flat 100%
            if (visible) {
                for (int i = 0; i < Math.round(arrivals * 0.06f); i++) {
                    Visitor v = newVisitor(date, ++sequence, true, usedReferenceIds, rand);
                    v.setStatus("REJECTED");
                    v.setRejectionReason("Blurry or unreadable ID");
                    visitors.add(v);
                }
                for (int i = 0; i < Math.round(arrivals * 0.04f); i++) {
                    Visitor v = newVisitor(date, ++sequence, true, usedReferenceIds, rand);
                    v.setStatus("CANCELLED_BY_VISITOR");
                    visitors.add(v);
                }
            }
        }
        return visitors;
    }

    private static Visitor newVisitor(LocalDate date, int sequence, boolean visible, Set<String> usedReferenceIds, Random rand) {
        String first = FIRST_NAMES[rand.nextInt(FIRST_NAMES.length)];
        String last = LAST_NAMES[rand.nextInt(LAST_NAMES.length)];

        Visitor v = new Visitor();
        v.setName(first + " " + last);
        v.setFullName(last + ", " + first);
        v.setEmail("visitor" + sequence + MOCK_EMAIL_DOMAIN);
        v.setContactNumber("0917" + String.format("%07d", rand.nextInt(10_000_000)));
        v.setVisitorType(VISITOR_TYPES[rand.nextInt(VISITOR_TYPES.length)]);
        v.setPurpose(visible ? PURPOSES[rand.nextInt(PURPOSES.length)] : HIDDEN_PURPOSE);
        v.setVisitDate(date);
        v.setGroupSize(1);

        String referenceId;
        do {
            referenceId = ReferenceIdGenerator.generate();
        } while (!usedReferenceIds.add(referenceId));
        v.setReferenceId(referenceId);
        return v;
    }

    private static int arrivalHour(Random rand) {
        int roll = rand.nextInt(100);
        for (int i = 0; i < HOUR_WEIGHTS.length; i++) {
            roll -= HOUR_WEIGHTS[i];
            if (roll < 0) return FIRST_HOUR + i;
        }
        return FIRST_HOUR;
    }
}
