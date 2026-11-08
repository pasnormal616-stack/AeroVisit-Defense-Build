package com.paf.aerovisit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.paf.aerovisit.model.Visitor;

/** ⚡ The booking calendar's seat totals, added up by the database. Runs on an in-memory H2 database, never the real one. */
@DataJpaTest
class SeatsHeldQueryTest {

    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final String AFTERNOON = "Afternoon (01:00 PM - 05:00 PM)";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 2);

    @Autowired
    private VisitorRepository repo;

    private void save(String status, LocalDate date, String window, int groupSize) {
        save(status, date, window, groupSize, "Leisure");
    }

    private void save(String status, LocalDate date, String window, int groupSize, String purpose) {
        Visitor v = new Visitor();
        v.setPurpose(purpose);
        v.setName("Visitor");
        v.setEmail("v@example.com");
        v.setStatus(status);
        v.setVisitDate(date);
        v.setTimeWindow(window);
        v.setGroupSize(groupSize);
        repo.save(v);
    }

    private Map<String, Integer> totals() {
        Map<String, Integer> seats = new HashMap<>();
        for (Object[] row : repo.sumSeatsHeldFrom(TODAY, Visitor.SEAT_HOLDING_STATUSES)) {
            seats.put(row[0] + "|" + row[1], ((Number) row[2]).intValue());
        }
        return seats;
    }

    @Test
    void countsSeatHoldingBookingsPerDateAndWindowFromTodayOn() {
        save("APPROVED", TODAY, MORNING, 5);
        save("CHECKED-IN", TODAY, MORNING, 3);
        save("CHECKED-OUT", TODAY, MORNING, 2);
        save("APPROVED", TODAY, AFTERNOON, 4);
        save("APPROVED", TODAY.plusDays(1), MORNING, 0);   // a group size of 0 still takes one seat, like holdsSeat
        save("PENDING", TODAY, MORNING, 20);               // 💳 pay first: a pending request holds its seats
        save("AWAITING_RECEIPT", TODAY, MORNING, 6);       // a paid booking holds its seats from submission
        save("PAYMENT_EXPIRED", TODAY, MORNING, 20);       // unpaid by the deadline: seats released
        save("REJECTED", TODAY, MORNING, 20);
        save("CANCELLED_BY_VISITOR", TODAY, MORNING, 20);
        save("APPROVED", TODAY.minusDays(1), MORNING, 30); // yesterday: not read at all

        assertThat(totals()).containsOnly(
                Map.entry(TODAY + "|" + MORNING, 36),
                Map.entry(TODAY + "|" + AFTERNOON, 4),
                Map.entry(TODAY.plusDays(1) + "|" + MORNING, 1));
    }

    @Test
    void anApprovedOlderMasterBookingHoldsItsSeatsLikeAnyGroup() {
        save("APPROVED", TODAY, MORNING, 40, "Educational Tour (Master Booking Request)");
        save("APPROVED", TODAY, MORNING, 5);

        assertThat(totals()).containsOnly(Map.entry(TODAY + "|" + MORNING, 45));
    }
}
