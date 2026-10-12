package com.paf.aerovisit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.service.MockVisitorGenerator;

/** Excel export query: last 30 days only, no mock / Historical Baseline rows. Runs on an in-memory H2 database, never the real one. */
@DataJpaTest
class ExcelExportQueryTest {

    @Autowired
    private VisitorRepository repo;

    private final LocalDate today = LocalDate.now();
    private final LocalDate from = today.minusDays(29);

    private Visitor save(String name, String email, String purpose, LocalDate date) {
        Visitor v = new Visitor();
        v.setName(name);
        v.setEmail(email);
        v.setPurpose(purpose);
        v.setVisitDate(date);
        return repo.save(v);
    }

    private List<String> exported() {
        return repo.findRealVisitorsForExport(from, today).stream().map(Visitor::getName).toList();
    }

    @Test
    void keepsOnlyVisitsFromTheLast30DaysNewestIdFirst() {
        save("Oldest In Range", "a@example.com", "Recreation", from);
        save("Today", "b@example.com", "Recreation", today);
        save("Too Old", "c@example.com", "Recreation", from.minusDays(1));
        save("Future Booking", "d@example.com", "Recreation", today.plusDays(1));
        save("No Date", "e@example.com", "Recreation", null);
        save("No Purpose", "f@example.com", null, today.minusDays(5));

        assertThat(exported()).containsExactly("No Purpose", "Today", "Oldest In Range");
    }

    @Test
    void leavesOutEveryKindOfMockRow() {
        save("Real Visitor", "juan@gmail.com", "Educational Tour", today.minusDays(2));
        // current generator: visible mock visitors look real except for the email domain
        save("Mock Visible", "visitor7" + MockVisitorGenerator.MOCK_EMAIL_DOMAIN, "Recreation", today.minusDays(2));
        save("Mock Hidden", "visitor8" + MockVisitorGenerator.MOCK_EMAIL_DOMAIN, MockVisitorGenerator.HIDDEN_PURPOSE, today.minusDays(2));
        // any Historical Baseline row, whatever its email, plus the two kinds the earlier generator made
        save("Baseline Real Email", "x@example.com", "Historical Baseline", today.minusDays(3));
        save("Archival Record - 12", "archive@paf.mil.ph", "Historical Baseline", today.minusDays(3));
        save("Civilian Trainee - 4", "civ_4@mail.com", "Recreation", today.minusDays(4));
        // real people who only look a little like the old patterns are kept
        save("Civilian Trainee - Real", "trainee@gmail.com", "Recreation", today.minusDays(4));
        save("Ana Cruz", "civ_ana@mail.com", "Recreation", today.minusDays(4));
        save(null, "civ_1@mail.com", "Recreation", today.minusDays(4));

        assertThat(exported()).containsExactlyInAnyOrder("Real Visitor", "Civilian Trainee - Real", "Ana Cruz", null);
    }
}
