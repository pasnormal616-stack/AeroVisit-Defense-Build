package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

// An area of the museum visitors can review (Root Cause Matrix). Zones are disabled, never deleted,
// because past feedback keeps the zone's name as plain text.
@Entity
public class MuseumZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String name;

    @Column(nullable = false)
    private boolean isActive = true;

    public MuseumZone() {}

    public MuseumZone(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { this.isActive = active; }
}
