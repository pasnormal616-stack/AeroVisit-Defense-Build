package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

// An operating time window the Director configures (e.g. "Morning", 08:00-12:00).
// Booking slots copy its label when they are generated, so editing or deleting a window
// only affects slots created afterwards; existing slots and bookings keep their label.
@Entity
public class TimeWindowConfig {

    private static final DateTimeFormatter LABEL_TIME = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String name;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    public TimeWindowConfig() {}

    public TimeWindowConfig(String name, LocalTime startTime, LocalTime endTime) {
        this.name = name;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    // Stored on BookingSlot and Visitor, e.g. "Morning (08:00 AM - 12:00 PM)".
    // Keep this format: the Manage Booking countdown reads the start time from it.
    public String getLabel() {
        return name + " (" + startTime.format(LABEL_TIME) + " - " + endTime.format(LABEL_TIME) + ")";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime startTime) { this.startTime = startTime; }

    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime endTime) { this.endTime = endTime; }
}
