package com.paf.aerovisit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.service.GateClaim;

/** 🚦 The one-UPDATE status claim behind GateClaim. Runs on an in-memory H2 database, never the real one. */
@DataJpaTest
class ClaimStatusQueryTest {

    @Autowired
    private VisitorRepository repo;

    @Test
    void onlyTheFirstOfTwoClaimsOnTheSamePassWins() {
        Visitor v = new Visitor();
        v.setName("Juan");
        v.setEmail("juan@example.com");
        v.setStatus("APPROVED");
        v.setVisitDate(LocalDate.now());
        Long id = repo.saveAndFlush(v).getId();
        GateClaim gate = new GateClaim(repo);

        assertThat(gate.claim(id, "APPROVED", "CHECKED-IN")).isTrue();   // gate 1
        assertThat(gate.claim(id, "APPROVED", "CHECKED-IN")).isFalse();  // gate 2, a moment later
        assertThat(gate.claim(id, "CHECKED-IN", "CHECKED-OUT")).isTrue();
        assertThat(gate.claim(id, "CHECKED-IN", "CHECKED-OUT")).isFalse();
        assertThat(gate.claim(999_999L, "APPROVED", "CHECKED-IN")).isFalse(); // no such visitor
    }

    @Test
    void withoutAGateClaimEveryClaimSucceedsAsBefore() {
        assertThat(GateClaim.claim(null, 5L, "APPROVED", "CHECKED-IN")).isTrue();
    }
}
