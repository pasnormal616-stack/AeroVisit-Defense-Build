package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;

/**
 * 📝 The feedback email is claimed by one conditional UPDATE, so the window-end sweep and a guard's check-out at the
 * same moment send it once. Real concurrent transactions on an in-memory H2 database (never the real one), no
 * test-managed transaction around them, like SeatReservationLockTest. The email itself is mocked.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:feedbackonce;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
@Import(FeedbackEmails.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FeedbackEmailRaceTest {

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Autowired private FeedbackEmails feedback;
    @Autowired private VisitorRepository repo;
    @MockitoBean private EmailService emailService;

    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private Long id;

    @BeforeEach
    void aVisitorInside() {
        Visitor v = new Visitor();
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setSecureToken("token-race");
        v.setStatus("CHECKED-IN");
        v.setVisitDate(LocalDate.now());
        v.setTimeWindow("Morning (08:00 AM - 12:00 PM)");
        id = repo.save(v).getId();
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        repo.deleteAll();
    }

    private Visitor freshCopy() {
        return repo.findById(id).orElseThrow();
    }

    @Test
    void theSweepAndACheckOutAtTheSameMomentSendOnlyOneEmail() throws Exception {
        // Each side loaded the booking before either sent (both see no timestamp), then both try at once
        List<Visitor> copies = List.of(freshCopy(), freshCopy(), freshCopy(), freshCopy());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        for (Visitor copy : copies) {
            attempts.add(pool.submit(() -> {
                go.await();
                return feedback.sendOnce(copy, NOW);
            }));
        }
        go.countDown();
        int sent = 0;
        for (Future<Boolean> a : attempts) if (a.get(30, TimeUnit.SECONDS)) sent++;

        assertThat(sent).isEqualTo(1);
        verify(emailService, times(1)).sendCheckOutFeedback("juan@example.com", "Juan Dela Cruz", "token-race");
        assertThat(freshCopy().getFeedbackEmailSentAt()).isEqualTo(NOW);
    }

    @Test
    void aGuardsCheckOutSaveAfterTheSweepDoesNotResetTheStampSoNothingIsSentAgain() {
        Visitor guardsCopy = freshCopy();                        // the guard's screen loaded it before the sweep
        assertThat(feedback.sendOnce(freshCopy(), NOW)).isTrue(); // the sweep sends

        guardsCopy.setStatus("CHECKED-OUT");                      // then the guard checks the visitor out
        guardsCopy.setCheckOutTime(NOW.plusMinutes(5));
        repo.save(guardsCopy);                                    // a full save of the old copy (timestamp null in it)
        assertThat(freshCopy().getFeedbackEmailSentAt()).isEqualTo(NOW); // not written by entity saves

        assertThat(feedback.sendOnce(guardsCopy, NOW.plusMinutes(5))).isFalse();
        verify(emailService, times(1)).sendCheckOutFeedback(anyString(), anyString(), anyString());
        assertThat(freshCopy().getStatus()).isEqualTo("CHECKED-OUT");
    }

    @Test
    void theSweepAfterAGuardsCheckOutSendsNothingAgain() {
        Visitor v = freshCopy();
        v.setStatus("CHECKED-OUT");
        repo.save(v);
        assertThat(feedback.sendOnce(v, NOW)).isTrue();                     // the guard's check-out sends

        assertThat(feedback.sendDue(NOW.toLocalDate().atTime(23, 0))).isZero(); // window long over: nothing due
        verify(emailService, times(1)).sendCheckOutFeedback(anyString(), anyString(), anyString());
    }

    @Test
    void theSweepFindsTodaysAttendedVisitsOnly() {
        assertThat(feedback.sendDue(NOW.toLocalDate().atTime(23, 0))).isEqualTo(1);
        assertThat(freshCopy().getStatus()).isEqualTo("CHECKED-IN"); // still inside: the email checks nobody out
    }
}
