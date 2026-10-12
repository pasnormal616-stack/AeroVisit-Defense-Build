package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;

@Entity
public class StaffSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- Core Staff Details ---
    private String staffName;
    
    // e.g., "Gate Guard", "Tour Guide", "Admin" (This single field eliminates redundant database tables!)
    private String role; 

    // --- Scheduling Details ---
    private LocalDate dutyDate;
    
    // e.g., "Morning (0800H-1200H)", "Afternoon (1300H-1700H)", "Full Day"
    private String shiftTime; 

    // --- Archive Flag ---
    // Past shifts are flagged instead of deleted so they stay available for historical auditing
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean archived = false;

    // --- Roster Notification Flag ---
    // True once "Publish Roster & Notify Staff" has emailed this shift to its officer, so it is not sent twice
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean rosterNotified = false;

    // Default constructor (Required by Spring Data JPA behind the scenes)
    public StaffSchedule() {}

    // --- GETTERS AND SETTERS ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public LocalDate getDutyDate() { return dutyDate; }
    public void setDutyDate(LocalDate dutyDate) { this.dutyDate = dutyDate; }

    public String getShiftTime() { return shiftTime; }
    public void setShiftTime(String shiftTime) { this.shiftTime = shiftTime; }

    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }

    public boolean isRosterNotified() { return rosterNotified; }
    public void setRosterNotified(boolean rosterNotified) { this.rosterNotified = rosterNotified; }
}