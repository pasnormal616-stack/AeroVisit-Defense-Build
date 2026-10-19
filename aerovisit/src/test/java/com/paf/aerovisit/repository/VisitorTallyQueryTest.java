package com.paf.aerovisit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.service.MockVisitorGenerator;

/** Visitor Tally queries: who counts and how. Runs on an in-memory H2 database, never the real one. */
@DataJpaTest
class VisitorTallyQueryTest {

    @Autowired
    private VisitorRepository repo;

    private void save(String status, LocalDate date, int groupSize, String email, String purpose) {
        Visitor v = new Visitor();
        v.setName("Visitor");
        v.setEmail(email);
        v.setPurpose(purpose);
        v.setStatus(status);
        v.setVisitDate(date);
        v.setGroupSize(groupSize);
        repo.save(v);
    }

    private Map<String, Integer> tally() {
        Map<String, Integer> byMonth = new HashMap<>();
        for (Object[] row : repo.tallyVisitorsByMonth()) {
            byMonth.put(((Number) row[0]).intValue() + "-" + ((Number) row[1]).intValue(), ((Number) row[2]).intValue());
        }
        return byMonth;
    }

    @Test
    void countsCheckedInAndOutVisitorsWithTheirGroupSizePerMonth() {
        save("CHECKED-OUT", LocalDate.of(2026, 9, 3), 5, "a@example.com", "Educational Tour");
        save("CHECKED-IN", LocalDate.of(2026, 9, 20), 1, "b@example.com", "Recreation");
        save("CHECKED-OUT", LocalDate.of(2026, 9, 21), 0, "c@example.com", "Recreation");   // no size recorded = 1 person
        save("CHECKED-OUT", LocalDate.of(2026, 10, 1), 3, "REDACTED", "Recreation");         // purged after 30 days: still counted

        assertThat(tally()).containsExactlyInAnyOrderEntriesOf(Map.of("2026-9", 7, "2026-10", 3));
    }

    @Test
    void visitorsWhoNeverCameAreNotCounted() {
        for (String status : new String[] {"APPROVED", "NO_SHOW", "CANCELLED", "REJECTED", "DENIED_ENTRY", "PENDING"}) {
            save(status, LocalDate.of(2026, 9, 3), 4, "x@example.com", "Recreation");
        }
        assertThat(tally()).isEmpty();
    }

    @Test
    void mockAndHistoricalBaselineRowsAreNeverCounted() {
        save("CHECKED-OUT", LocalDate.of(2026, 9, 3), 2, "real@example.com", "Recreation");
        save("CHECKED-OUT", LocalDate.of(2026, 9, 3), 9, "v1" + MockVisitorGenerator.MOCK_EMAIL_DOMAIN, "Recreation");
        save("CHECKED-OUT", LocalDate.of(2025, 2, 3), 9, "old@example.com", MockVisitorGenerator.HIDDEN_PURPOSE);

        assertThat(tally()).containsExactlyInAnyOrderEntriesOf(Map.of("2026-9", 2));
        assertThat(repo.findFirstRealVisitDate()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void theFirstRealBookingCountsWhateverItsStatus() {
        save("REJECTED", LocalDate.of(2026, 8, 30), 1, "early@example.com", "Recreation");
        save("CHECKED-OUT", LocalDate.of(2026, 9, 3), 1, "later@example.com", "Recreation");
        assertThat(repo.findFirstRealVisitDate()).isEqualTo(LocalDate.of(2026, 8, 30));
    }

    @Test
    void noBookingsMeansNoFirstDate() {
        assertThat(repo.findFirstRealVisitDate()).isNull();
    }
}
