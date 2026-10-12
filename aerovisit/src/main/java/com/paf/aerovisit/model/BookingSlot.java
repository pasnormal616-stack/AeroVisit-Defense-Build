package com.paf.aerovisit.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;

@Entity
public class BookingSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate slotDate;
    private String timeWindow; // Example: "Morning (08:00 AM - 12:00 PM)"
    private int maxCapacity;

    // Empty constructor required by Spring
    public BookingSlot() {}

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getSlotDate() { return slotDate; }
    public void setSlotDate(LocalDate slotDate) { this.slotDate = slotDate; }

    public String getTimeWindow() { return timeWindow; }
    public void setTimeWindow(String timeWindow) { this.timeWindow = timeWindow; }

    public int getMaxCapacity() { return maxCapacity; }
    public void setMaxCapacity(int maxCapacity) { this.maxCapacity = maxCapacity; }
}