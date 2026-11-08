package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.BillingCounter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface BillingCounterRepository extends JpaRepository<BillingCounter, Long> {

    // The counter row under a database row lock (SELECT ... FOR UPDATE): a second caller waits until the first commits
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM BillingCounter c WHERE c.id = :id")
    Optional<BillingCounter> lockById(@org.springframework.data.repository.query.Param("id") Long id);
}
