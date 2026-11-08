package com.paf.aerovisit.model;

import jakarta.persistence.*;
import java.time.LocalDate;

// 🎌 A holiday or closed day the Director lists in Slots & Staff (e.g. "Bonifacio Day"). Public booking, walk-ins and
// rescheduling refuse it, and the Bulk Slot Generator always skips it. repeatsYearly = the same month and day every year
// (fixed-date holidays such as Christmas Day). Unlike an emergency closure it never cancels or emails anyone.
@Entity
public class Holiday {

    public static final int LABEL_MAX = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate holidayDate;

    @Column(nullable = false, length = LABEL_MAX)
    private String label;

    @Column(nullable = false)
    private boolean repeatsYearly;

    public Holiday() {}

    public Holiday(LocalDate holidayDate, String label, boolean repeatsYearly) {
        this.holidayDate = holidayDate;
        this.label = label;
        this.repeatsYearly = repeatsYearly;
    }

    // The exact date, or (repeating every year) the same month and day in any year. Feb 29 only falls in leap years.
    public boolean fallsOn(LocalDate date) {
        if (date == null || holidayDate == null) return false;
        if (!repeatsYearly) return date.equals(holidayDate);
        return date.getMonth() == holidayDate.getMonth() && date.getDayOfMonth() == holidayDate.getDayOfMonth();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getHolidayDate() { return holidayDate; }
    public void setHolidayDate(LocalDate holidayDate) { this.holidayDate = holidayDate; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public boolean isRepeatsYearly() { return repeatsYearly; }
    public void setRepeatsYearly(boolean repeatsYearly) { this.repeatsYearly = repeatsYearly; }
}
