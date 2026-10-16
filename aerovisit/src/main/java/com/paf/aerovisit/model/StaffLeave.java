package com.paf.aerovisit.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;

// 🏖️ VACATION RULE: an approved leave block. The officer is never scheduled from startDate to endDate (both included).
// Shifts point to officers by name, so leave does too.
@Entity
public class StaffLeave {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String staffName;
    private LocalDate startDate;
    private LocalDate endDate;

    public StaffLeave() {}

    public StaffLeave(String staffName, LocalDate startDate, LocalDate endDate) {
        this.staffName = staffName;
        this.startDate = startDate;
        this.endDate = endDate;
    }

    // True when the date falls inside this leave, both ends included
    public boolean covers(LocalDate date) {
        return date != null && startDate != null && endDate != null && !date.isBefore(startDate) && !date.isAfter(endDate);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
}
