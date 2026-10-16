package com.paf.aerovisit.model;

import jakarta.persistence.*;
import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Entity
public class MuseumEmployee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private String role;

    // Unused since 2026-10-05: 'role' itself became free text. Kept so the existing job_title column stays mapped.
    @Column(length = 100)
    private String jobTitle;

    // --- Shift Template (all optional; null on officers added before these fields existed) ---
    // Where the published duty roster is emailed
    private String email;

    // Comma-separated days this officer normally works, e.g. "MONDAY,WEDNESDAY,FRIDAY"
    private String workingDays;

    // "Morning", "Afternoon" or "Full Day"
    private String defaultShift;

    // 👻 GHOST RULE: a deactivated officer stays in the directory (history keeps their name) but is never scheduled again.
    // ddl-auto=update adds the column with this default, so every existing officer starts active.
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean active = true;

    public MuseumEmployee() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String jobTitle) { this.jobTitle = jobTitle; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getWorkingDays() { return workingDays; }
    public void setWorkingDays(String workingDays) { this.workingDays = workingDays; }

    public String getDefaultShift() { return defaultShift; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public void setDefaultShift(String defaultShift) { this.defaultShift = defaultShift; }

    // True when this officer's shift template includes the given day of the week
    public boolean worksOn(DayOfWeek day) {
        if (workingDays == null || day == null) return false;
        for (String d : workingDays.split(",")) {
            if (d.trim().equalsIgnoreCase(day.name())) return true;
        }
        return false;
    }

    // Short form for the Staff Directory table, e.g. "Mon, Wed, Fri" (empty when no working days are set)
    public String getWorkingDaysLabel() {
        List<String> labels = new ArrayList<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            if (worksOn(day)) labels.add(day.getDisplayName(TextStyle.SHORT, Locale.US));
        }
        return String.join(", ", labels);
    }
}
