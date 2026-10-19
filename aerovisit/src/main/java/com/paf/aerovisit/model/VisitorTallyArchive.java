package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

// One month of the museum's visitor count from before AeroVisit (the paper tally), shown in the Visitor Tally
@Entity
@Table(name = "visitor_tally_archive", uniqueConstraints = @UniqueConstraint(columnNames = {"tally_year", "tally_month"}))
public class VisitorTallyArchive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tally_year", nullable = false)
    private int year;

    @Column(name = "tally_month", nullable = false)
    private int month; // 1 = January

    @Column(nullable = false)
    private int visitors;

    public VisitorTallyArchive() {}

    public VisitorTallyArchive(int year, int month, int visitors) {
        this.year = year;
        this.month = month;
        this.visitors = visitors;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public int getYear() { return year; }
    public void setYear(int year) { this.year = year; }

    public int getMonth() { return month; }
    public void setMonth(int month) { this.month = month; }

    public int getVisitors() { return visitors; }
    public void setVisitors(int visitors) { this.visitors = visitors; }
}
