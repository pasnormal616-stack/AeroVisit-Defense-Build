package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;

// A date the Director has closed to all visitors (e.g. typhoon signal); public booking is locked for it
@Entity
public class MuseumClosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private LocalDate closureDate;

    @Column(nullable = false, length = 255)
    private String reason;

    public MuseumClosure() {}

    public MuseumClosure(LocalDate closureDate, String reason) {
        this.closureDate = closureDate;
        this.reason = reason;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getClosureDate() { return closureDate; }
    public void setClosureDate(LocalDate closureDate) { this.closureDate = closureDate; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
