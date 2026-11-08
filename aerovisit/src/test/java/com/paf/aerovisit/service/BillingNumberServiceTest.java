package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.paf.aerovisit.model.BillingCounter;
import com.paf.aerovisit.repository.BillingCounterRepository;

/**
 * 💳 Billing numbers stay unique when many approvals ask at the same moment: BillingNumberService locks the one-row
 * counter (SELECT ... FOR UPDATE). Real concurrent transactions on an in-memory H2 database (never the real one), with
 * no test-managed transaction around them, like SeatReservationLockTest.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:billinglock;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
@Import(BillingNumberService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BillingNumberServiceTest {

    @Autowired private BillingNumberService billingNumbers;
    @Autowired private BillingCounterRepository counters;

    private final ExecutorService pool = Executors.newFixedThreadPool(24);

    @BeforeEach
    void freshCounter() {
        counters.deleteAll();
        billingNumbers.ensureCounter(); // what the startup event does in the real app
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    @Test
    void numbersAreSixDigitsZeroPaddedAndRunInOrder() {
        assertThat(billingNumbers.next()).isEqualTo("000001");
        assertThat(billingNumbers.next()).isEqualTo("000002");
        assertThat(BillingNumberService.format(1_000_000)).isEqualTo("1000000"); // past 999999 it simply grows
        assertThat(counters.findById(BillingCounter.SINGLETON_ID).orElseThrow().getLastNumber()).isEqualTo(2);
    }

    @Test
    void fiftyApprovalsAtTheSameMomentGetFiftyDifferentConsecutiveNumbers() throws Exception {
        int callers = 50;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return billingNumbers.next();
            }));
        }
        start.countDown(); // all at once
        List<String> numbers = new ArrayList<>();
        for (Future<String> f : results) numbers.add(f.get(30, TimeUnit.SECONDS));

        assertThat(numbers).doesNotHaveDuplicates().hasSize(callers);
        List<String> expected = new ArrayList<>();
        for (int n = 1; n <= callers; n++) expected.add(String.format("%06d", n));
        assertThat(numbers).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(counters.findById(BillingCounter.SINGLETON_ID).orElseThrow().getLastNumber()).isEqualTo(callers);
    }

    @Test
    void theFirstNumberWorksEvenBeforeTheStartupEventCreatedTheRow() {
        counters.deleteAll();
        assertThat(billingNumbers.next()).isEqualTo("000001");
    }
}
