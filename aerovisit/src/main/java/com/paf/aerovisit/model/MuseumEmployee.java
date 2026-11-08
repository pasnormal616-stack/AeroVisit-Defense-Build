package com.paf.aerovisit.model;

import jakarta.persistence.*;

// 👤 Staff Directory entry. Since 2026-11-06 the directory is names only: the Director adds, renames and removes officers
// by full name/rank. The other fields below are no longer read or written; they stay mapped so their columns and the
// values already in them are kept (ddl-auto=update never drops a column).
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

    // Unused since 2026-11-06 (the old shift template and roster email address). Kept so the columns stay mapped.
    private String email;
    private String workingDays;
    private String defaultShift;

    // Unused since 2026-11-06 (Deactivate/Reactivate was removed). Kept so the column and its default stay as they are.
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

}
