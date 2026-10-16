package com.paf.aerovisit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import com.paf.aerovisit.model.Visitor;

/**
 * Attrition funnel No-Show stage, run for real on an in-memory H2 database (never the real one). A no-show is only a row
 * the 11:59 PM sweep already marked NO_SHOW: a past APPROVED booking the sweep has not reached yet is not one.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:noshowfunnel;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
class NoShowFunnelQueryTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired private VisitorRepository repo;

    @BeforeEach
    void empty() {
        repo.deleteAll();
    }

    private void save(String status, LocalDate date, String purpose) {
        Visitor v = new Visitor();
        v.setName(status);
        v.setFullName(status);
        v.setStatus(status);
        v.setVisitDate(date);
        v.setPurpose(purpose);
        repo.save(v);
    }

    @Test
    void onlySweptNoShowsAreCountedAndTheyStayInsideApproved() {
        save("NO_SHOW", TODAY.minusDays(3), "Educational Tour");
        save("NO_SHOW", TODAY, "Leisure");                            // night window swept the same day
        save("NO_SHOW", TODAY.minusDays(400), "Historical Baseline"); // archival data: never in live metrics
        save("APPROVED", TODAY.minusDays(1), "Leisure");              // past, sweep not run yet: still approved
        save("APPROVED", TODAY.plusDays(5), "Leisure");               // future visit
        save("CHECKED-IN", TODAY, "Leisure");
        save("CHECKED-OUT", TODAY.minusDays(2), "Leisure");
        save("REJECTED", TODAY.minusDays(2), "Leisure");
        save("DENIED_ENTRY", TODAY.minusDays(2), "Leisure");
        save("CANCELLED_BY_VISITOR", TODAY.minusDays(2), "Leisure");

        assertThat(repo.countTotalNoShows()).isEqualTo(2);
        assertThat(repo.countTotalCheckedIn()).isEqualTo(2);
        assertThat(repo.countTotalApproved()).isEqualTo(6);           // 2 no-shows + 2 approved + 2 checked in/out
        assertThat(repo.countTotalNoShows() + repo.countTotalCheckedIn()).isLessThanOrEqualTo(repo.countTotalApproved());
    }

    @Test
    void noBookingsMeansZeroNoShows() {
        assertThat(repo.countTotalNoShows()).isZero();
    }
}
