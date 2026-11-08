package com.paf.aerovisit.service;

import com.paf.aerovisit.model.BillingCounter;
import com.paf.aerovisit.repository.BillingCounterRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 💳 Billing numbers: a 6-digit zero-padded running number (000001, 000002, ...), one per booking, for the visitor to
 * enter on Landbank Link.BizPortal. Walk-ins will use the same generator.
 *
 * Race-safe by a database row lock: next() reads the one-row counter with SELECT ... FOR UPDATE, so a second approval
 * at the same moment waits for the first to commit and then gets the following number. Chosen over "unique constraint
 * + retry" because it never collides (no retry loop), keeps the numbers strictly sequential, and is the same proven
 * pattern as the seat lock (SeatReservationService). Visitor.billingNumber is also UNIQUE as a backstop.
 *
 * Each number is committed in its own transaction (REQUIRES_NEW) before the booking is saved, so it is never handed out
 * twice; if that approval then fails, its number is simply skipped (never reused). Past 999999 it grows to 7 digits.
 */
@Service
public class BillingNumberService {

    private final BillingCounterRepository counters;

    public BillingNumberService(BillingCounterRepository counters) {
        this.counters = counters;
    }

    // Creates the counter row once at startup, single-threaded, so the first two approvals never race to insert it
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureCounter() {
        if (!counters.existsById(BillingCounter.SINGLETON_ID)) counters.save(new BillingCounter());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String next() {
        BillingCounter counter = counters.lockById(BillingCounter.SINGLETON_ID).orElse(null);
        if (counter == null) {
            // Only before ensureCounter ran (e.g. a test); the startup event normally created the row already
            counters.saveAndFlush(new BillingCounter());
            counter = counters.lockById(BillingCounter.SINGLETON_ID).orElseThrow();
        }
        long number = counter.getLastNumber() + 1;
        counter.setLastNumber(number);
        counters.save(counter);
        return format(number);
    }

    static String format(long number) {
        return String.format("%06d", number);
    }
}
