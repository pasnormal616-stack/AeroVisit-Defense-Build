package com.paf.aerovisit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.paf.aerovisit.model.Visitor;

/**
 * ⌛ The "Waiting for payment receipt" query matches Visitor.waitingForReceipt() (and never a ready request), and the
 * Resend payment link claim lets one resend through per 10 minutes. Real queries on in-memory H2.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:waitingreceipt;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.show-sql=false"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WaitingForReceiptQueryTest {

    @Autowired private VisitorRepository repo;

    @AfterEach
    void cleanUp() {
        repo.deleteAll();
    }

    private Visitor saved(String name, String status, String paymentStatus) {
        Visitor v = new Visitor();
        v.setName(name);
        v.setEmail(name.replace(' ', '.') + "@example.com");
        v.setStatus(status);
        v.setPaymentStatus(paymentStatus);
        v.setVisitDate(LocalDate.now().plusDays(3));
        v.setTimeWindow("Morning (08:00 AM - 12:00 PM)");
        v.setAdmissionTotalCentavos(30_000L);
        v.setRegularCount(2);
        return repo.save(v);
    }

    @Test
    void theWaitingQueryAndThePendingQueueNeverOverlap() {
        saved("No Receipt", Visitor.STATUS_AWAITING_RECEIPT, Visitor.PAY_AWAITING);
        saved("Rejected Receipt", "PENDING", Visitor.PAY_REJECTED);
        saved("Pending Awaiting", "PENDING", Visitor.PAY_AWAITING);
        saved("Receipt In", "PENDING", Visitor.PAY_PROOF_SUBMITTED);
        saved("Free", "PENDING", Visitor.PAY_NOT_REQUIRED);
        saved("Approved Unpaid", "APPROVED", Visitor.PAY_AWAITING);   // older approve-then-pay: neither group
        saved("Expired", Visitor.STATUS_PAYMENT_EXPIRED, Visitor.PAY_AWAITING);

        assertThat(repo.findWaitingForReceipt()).extracting(Visitor::getName)
                .containsExactlyInAnyOrder("No Receipt", "Rejected Receipt", "Pending Awaiting");
        assertThat(repo.countWaitingForReceipt()).isEqualTo(3);
        assertThat(repo.findWaitingForReceipt()).allMatch(Visitor::waitingForReceipt).noneMatch(Visitor::inPendingQueue);
        assertThat(repo.findPendingQueue()).extracting(Visitor::getName).containsExactlyInAnyOrder("Receipt In", "Free");
        assertThat(repo.findAll()).filteredOn(Visitor::waitingForReceipt).hasSize(3);
    }

    @Test
    void theResendClaimLetsOneThroughEveryTenMinutes() {
        Visitor v = saved("No Receipt", Visitor.STATUS_AWAITING_RECEIPT, Visitor.PAY_AWAITING);
        LocalDateTime t0 = LocalDateTime.of(2026, 11, 5, 10, 0);

        assertThat(repo.claimPaymentLinkResend(v.getId(), t0, t0.minusMinutes(10))).isEqualTo(1);
        assertThat(repo.claimPaymentLinkResend(v.getId(), t0.plusMinutes(1), t0.minusMinutes(9))).isZero();   // a second click
        assertThat(repo.claimPaymentLinkResend(v.getId(), t0.plusMinutes(9), t0.minusMinutes(1))).isZero();
        assertThat(repo.claimPaymentLinkResend(v.getId(), t0.plusMinutes(10), t0)).isEqualTo(1);              // 10 minutes later
        assertThat(repo.findById(v.getId()).orElseThrow().getPaymentLinkResentAt()).isEqualTo(t0.plusMinutes(10));
    }
}
