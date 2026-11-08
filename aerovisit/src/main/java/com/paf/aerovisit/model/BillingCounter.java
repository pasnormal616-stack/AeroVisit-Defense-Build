package com.paf.aerovisit.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

// 💳 One-row counter behind the 6-digit billing numbers (BillingNumberService). Row 1 holds the last number handed out.
@Entity
public class BillingCounter {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    private long lastNumber;

    public BillingCounter() {}

    public Long getId() { return id; }
    public long getLastNumber() { return lastNumber; }
    public void setLastNumber(long lastNumber) { this.lastNumber = lastNumber; }
}
