package com.paf.aerovisit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import com.paf.aerovisit.model.Visitor;

/**
 * The two retention queries run for real on an in-memory H2 database (never the real one): the purge must still find a
 * visit whose files are gone but whose details are not anonymized, and the expiry must only see requests dated before today.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:retention;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
class RetentionQueriesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 10);
    private static final LocalDate CUTOFF = TODAY.minusDays(30);
    private static final Set<String> FINISHED = Set.of("CHECKED-OUT", "NO_SHOW", "CANCELLED", "REJECTED",
            "CANCELLED_BY_VISITOR", "CANCELLED_OVERRIDE", "DENIED_ENTRY");

    @Autowired private VisitorRepository repo;

    @BeforeEach
    void empty() {
        repo.deleteAll();
    }

    private Visitor save(String fullName, String status, LocalDate date, String photo) {
        Visitor v = new Visitor();
        v.setFullName(fullName);
        v.setName(fullName);
        v.setStatus(status);
        v.setVisitDate(date);
        v.setIdImageUrl(photo);
        return repo.save(v);
    }

    @Test
    void thePurgeFindsVisitsWithFilesOrWithDetailsStillToAnonymize() {
        Visitor withFiles = save("Dela Cruz, Juan", "CHECKED-OUT", CUTOFF.minusDays(1), "a.jpg");
        Visitor filesGoneDetailsLeft = save("Reyes, Ana", "NO_SHOW", CUTOFF.minusDays(60), null);
        Visitor noName = save(null, "REJECTED", CUTOFF.minusDays(5), null);
        save("REDACTED", "CHECKED-OUT", CUTOFF.minusDays(90), null);       // fully done: never again
        save("Santos, Maria", "CHECKED-OUT", CUTOFF, "b.jpg");              // exactly 30 days: not yet
        Visitor denied = save("Garcia, Leo", "DENIED_ENTRY", CUTOFF.minusDays(90), "c.jpg"); // purged like the rest
        save("Lim, Ben", "CHECKED-IN", CUTOFF.minusDays(90), "d.jpg");      // never finished: not a finished status

        List<Visitor> found = repo.findExpiredWithFiles(FINISHED, CUTOFF);

        assertThat(found).extracting(Visitor::getId)
                .containsExactlyInAnyOrder(withFiles.getId(), filesGoneDetailsLeft.getId(), noName.getId(), denied.getId());
    }

    @Test
    void theExpiryFindsOnlyUnreviewedRequestsDatedBeforeToday() {
        Visitor pendingYesterday = save("A", "PENDING", TODAY.minusDays(1), null);
        Visitor reviewLongAgo = save("B", "PENDING_MANUAL_REVIEW", TODAY.minusDays(45), null);
        Visitor verifiedPast = save("C", "VERIFIED", TODAY.minusDays(2), null);
        save("D", "PENDING", TODAY, null);                     // visit day itself: still reviewable
        save("E", "PENDING", TODAY.plusDays(3), null);         // future
        save("F", "APPROVED", TODAY.minusDays(1), null);       // decided (the no-show sweep's job)

        List<Visitor> found = repo.findByStatusInAndVisitDateBefore(Visitor.APPROVABLE_STATUSES, TODAY);

        assertThat(found).extracting(Visitor::getId)
                .containsExactlyInAnyOrder(pendingYesterday.getId(), reviewLongAgo.getId(), verifiedPast.getId());
    }
}
