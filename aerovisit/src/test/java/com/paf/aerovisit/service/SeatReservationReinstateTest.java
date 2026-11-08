package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;

/**
 * ♻️ Reinstate is race-safe: only a booking still PAYMENT_EXPIRED is claimed (conditional UPDATE inside the slot lock),
 * so two clicks or two tabs reinstate it once; a full slot rolls the claim back. Real transactions on in-memory H2.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:reinstate;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
@Import(SeatReservationService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeatReservationReinstateTest {

    private static final LocalDate DAY = LocalDate.now().plusDays(7);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";

    @Autowired private SeatReservationService seats;
    @Autowired private VisitorRepository repo;
    @Autowired private BookingSlotRepository slots;

    private final ExecutorService pool = Executors.newFixedThreadPool(8);

    @BeforeEach
    void slotWith10Seats() {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(10);
        slots.save(slot);
    }

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        repo.deleteAll();
        slots.deleteAll();
    }

    private Visitor saved(String name, String status, int groupSize) {
        Visitor v = new Visitor();
        v.setName(name);
        v.setEmail(name.replace(' ', '.') + "@example.com");
        v.setStatus(status);
        v.setVisitDate(DAY);
        v.setTimeWindow(WINDOW);
        v.setGroupSize(groupSize);
        v.setBillingNumber(String.format("%06d", Math.floorMod(name.hashCode(), 1_000_000))); // unique per booking
        return repo.save(v);
    }

    private static Visitor prepared(Visitor v) {
        BookingReinstatement.prepare(v, DAY, WINDOW, "Paid; confirmed in Landbank records", "director", LocalDateTime.now());
        return v;
    }

    @Test
    void anExpiredBookingIsReinstatedOnceAndASecondReinstateChangesNothing() {
        Visitor expired = saved("Late Receipt", Visitor.STATUS_PAYMENT_EXPIRED, 3);

        seats.reinstateIfSeatsRemain(prepared(repo.findById(expired.getId()).orElseThrow()));
        Visitor after = repo.findById(expired.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("APPROVED");
        assertThat(after.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
        assertThat(after.getReinstatedBy()).isEqualTo("director");

        // A second click (its copy of the booking still says PAYMENT_EXPIRED) is refused by the conditional UPDATE
        Visitor staleCopy = prepared(repo.findById(expired.getId()).orElseThrow());
        staleCopy.setStatus(Visitor.STATUS_PAYMENT_EXPIRED);
        staleCopy.setReinstatementNote("second click");
        assertThatThrownBy(() -> seats.reinstateIfSeatsRemain(staleCopy))
                .isInstanceOf(SeatReservationService.NotReinstatableException.class);
        assertThat(repo.findById(expired.getId()).orElseThrow().getReinstatementNote()).isEqualTo("Paid; confirmed in Landbank records");
    }

    @Test
    void aFullSlotRollsTheClaimBackAndTheBookingStaysExpired() {
        saved("Others", "APPROVED", 8);
        Visitor expired = saved("Late Receipt", Visitor.STATUS_PAYMENT_EXPIRED, 3);

        assertThatThrownBy(() -> seats.reinstateIfSeatsRemain(prepared(repo.findById(expired.getId()).orElseThrow())))
                .isInstanceOf(SeatReservationService.CapacityFullException.class);
        Visitor after = repo.findById(expired.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Visitor.STATUS_PAYMENT_EXPIRED);
        assertThat(after.getReinstatedAt()).isNull();
    }

    @Test
    void parallelReinstatesOfOneBookingSucceedOnlyOnce() throws Exception {
        Visitor expired = saved("Late Receipt", Visitor.STATUS_PAYMENT_EXPIRED, 2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Visitor copy = prepared(repo.findById(expired.getId()).orElseThrow());
            results.add(pool.submit(() -> {
                start.await();
                try {
                    seats.reinstateIfSeatsRemain(copy);
                    return true;
                } catch (SeatReservationService.NotReinstatableException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) succeeded++;

        assertThat(succeeded).isEqualTo(1);
        assertThat(repo.sumSeatsHeld(DAY, WINDOW, Visitor.SEAT_HOLDING_STATUSES, null)).isEqualTo(2);
    }
}
