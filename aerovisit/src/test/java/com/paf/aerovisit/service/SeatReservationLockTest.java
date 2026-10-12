package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;

/**
 * The 99/100 race: SeatReservationService locks the slot row (SELECT ... FOR UPDATE) and re-counts inside the lock.
 * Real concurrent transactions on an in-memory H2 database (never the real one); no test-managed transaction around them.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:seatlock;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
@Import(SeatReservationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeatReservationLockTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(7);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";

    @Autowired private SeatReservationService seats;
    @Autowired private VisitorRepository repo;
    @Autowired private BookingSlotRepository slots;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ExecutorService pool = Executors.newFixedThreadPool(24);

    @BeforeEach
    void slotWith100SeatsAnd99Taken() {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(100);
        slots.save(slot);
        repo.save(booking("Big Group", "APPROVED", 99));
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        repo.deleteAll();
        slots.deleteAll();
    }

    private static Visitor booking(String name, String status, int groupSize) {
        Visitor v = new Visitor();
        v.setName(name);
        v.setEmail(name.replace(' ', '.') + "@example.com");
        v.setStatus(status);
        v.setVisitDate(DAY);
        v.setTimeWindow(WINDOW);
        v.setGroupSize(groupSize);
        return v;
    }

    private long seatsHeld() {
        return repo.sumSeatsHeld(DAY, WINDOW, Visitor.SEAT_HOLDING_STATUSES, null);
    }

    @Test
    void theLastSeatGoesToOneBookingOnly() {
        seats.saveIfSeatsRemain(booking("First", "APPROVED", 1));
        assertThatThrownBy(() -> seats.saveIfSeatsRemain(booking("Second", "APPROVED", 1)))
                .isInstanceOf(SeatReservationService.CapacityFullException.class);
        assertThat(seatsHeld()).isEqualTo(100);
        assertThat(repo.count()).isEqualTo(2);
    }

    @Test
    void aSecondBookingWaitsForTheLockThenSeesTheFirstOnesSeat() throws Exception {
        CountDownLatch firstHoldsLock = new CountDownLatch(1);
        CountDownLatch letFirstFinish = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        // Booking A: takes the slot lock, then (while B is already waiting) saves the 100th seat and commits
        Future<?> first = pool.submit(() -> tx.executeWithoutResult(status -> {
            slots.lockBySlotDateAndTimeWindow(DAY, WINDOW);
            firstHoldsLock.countDown();
            try {
                letFirstFinish.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            repo.save(booking("A", "APPROVED", 1));
        }));
        assertThat(firstHoldsLock.await(10, TimeUnit.SECONDS)).isTrue();

        // Booking B: same moment, same slot. Without the lock it would count 99 and also succeed (101/100).
        Future<Visitor> second = pool.submit(() -> seats.saveIfSeatsRemain(booking("B", "APPROVED", 1)));
        Thread.sleep(700);
        assertThat(second.isDone()).as("B must be blocked on A's row lock").isFalse();

        letFirstFinish.countDown();
        first.get(10, TimeUnit.SECONDS);

        assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(SeatReservationService.CapacityFullException.class);
        assertThat(seatsHeld()).isEqualTo(100);
    }

    @Test
    void aStampedeOfTwentyForFiveSeatsNeverOverbooks() throws Exception {
        repo.deleteAll();
        repo.save(booking("Big Group", "APPROVED", 95));
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String name = "Walk In " + i;
            attempts.add(pool.submit(() -> {
                go.await();
                try {
                    seats.saveIfSeatsRemain(booking(name, "APPROVED", 1));
                    return true;
                } catch (SeatReservationService.CapacityFullException full) {
                    return false;
                }
            }));
        }
        go.countDown();

        int booked = 0;
        for (Future<Boolean> attempt : attempts) {
            if (attempt.get(30, TimeUnit.SECONDS)) booked++;
        }
        assertThat(booked).isEqualTo(5);
        assertThat(seatsHeld()).isEqualTo(100);
    }

    @Test
    void approvingAPendingBookingDoesNotCountItsOwnRow() {
        Visitor pending = repo.save(booking("Pending One", "PENDING", 1));
        pending.setStatus("APPROVED");

        Visitor saved = seats.saveIfSeatsRemain(pending);

        assertThat(saved.getStatus()).isEqualTo("APPROVED");
        assertThat(seatsHeld()).isEqualTo(100);
    }

    @Test
    void onlySeatHoldingStatusesInTheSameWindowCount() {
        repo.save(booking("Waitlisted", "PENDING", 50));
        repo.save(booking("Rejected", "REJECTED", 50));
        repo.save(booking("Cancelled", "CANCELLED_BY_VISITOR", 50));
        Visitor otherWindow = booking("Afternoon Group", "APPROVED", 50);
        otherWindow.setTimeWindow("Afternoon (01:00 PM - 05:00 PM)");
        repo.save(otherWindow);

        seats.saveIfSeatsRemain(booking("Last Seat", "APPROVED", 1));
        assertThat(seatsHeld()).isEqualTo(100);
    }

    @Test
    void aSlotThatDoesNotExistHasNoSeats() {
        Visitor ghost = booking("Ghost", "APPROVED", 1);
        ghost.setTimeWindow("Midnight (12:00 AM - 01:00 AM)");
        assertThatThrownBy(() -> seats.saveIfSeatsRemain(ghost)).isInstanceOf(SeatReservationService.CapacityFullException.class);
    }

    @Test
    void purgeQueryFindsOnlyFinishedVisitsOlderThanTheCutoffThatStillHaveFiles() {
        LocalDate cutoff = LocalDate.now().minusDays(30);
        Visitor old = booking("Old Checked Out", "CHECKED-OUT", 1);
        old.setVisitDate(cutoff.minusDays(1));
        old.setIdImageUrl("a.jpg");
        Visitor oldQrOnly = booking("Old No Show", "NO_SHOW", 1);
        oldQrOnly.setVisitDate(cutoff.minusDays(40));
        oldQrOnly.setQrCodeUrl("QR_2.png");
        Visitor alreadyPurged = booking("Already Purged", "REJECTED", 1);
        alreadyPurged.setVisitDate(cutoff.minusDays(3));
        alreadyPurged.setFullName("REDACTED"); // fully processed: files gone and details anonymized
        Visitor onCutoff = booking("On Cutoff", "CANCELLED", 1);
        onCutoff.setVisitDate(cutoff);
        onCutoff.setIdImageUrl("b.jpg");
        Visitor approvedOld = booking("Approved Old", "APPROVED", 1);
        approvedOld.setVisitDate(cutoff.minusDays(3));
        approvedOld.setQrCodeUrl("QR_5.png");
        repo.saveAll(List.of(old, oldQrOnly, alreadyPurged, onCutoff, approvedOld));

        List<String> found = repo.findExpiredWithFiles(Set.of("CHECKED-OUT", "NO_SHOW", "CANCELLED", "REJECTED"), cutoff)
                .stream().map(Visitor::getName).toList();

        assertThat(found).containsExactlyInAnyOrder("Old Checked Out", "Old No Show");
    }
}
