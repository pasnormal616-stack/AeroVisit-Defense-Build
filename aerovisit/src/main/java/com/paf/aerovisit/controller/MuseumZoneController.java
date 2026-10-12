package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.MuseumZone;
import com.paf.aerovisit.repository.MuseumZoneRepository;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// Museum zones for the Root Cause Matrix. GET /api/zones is public (visitor feedback dropdown);
// everything else is Director-only in SecurityConfig. There is no delete or rename: past feedback
// stores the zone name as text, so a zone is only ever disabled.
@RestController
public class MuseumZoneController {

    private final MuseumZoneRepository zoneRepo;

    public MuseumZoneController(MuseumZoneRepository zoneRepo) {
        this.zoneRepo = zoneRepo;
    }

    // Active zones only, in the order they were added
    @GetMapping("/api/zones")
    public List<Map<String, Object>> activeZones() {
        return zoneRepo.findByIsActiveTrueOrderByIdAsc().stream().map(MuseumZoneController::toJson).toList();
    }

    // Every zone, disabled ones included (Manage Zones modal)
    @GetMapping("/api/zones/all")
    public List<Map<String, Object>> allZones() {
        return zoneRepo.findAll().stream().map(MuseumZoneController::toJson).toList();
    }

    @PostMapping("/api/zones")
    public ResponseEntity<Map<String, Object>> addZone(@RequestBody Map<String, Object> body) {
        String name = body.get("name") == null ? "" : body.get("name").toString().trim();
        if (name.isEmpty() || name.length() > 60) {
            return error(HttpStatus.BAD_REQUEST, "Zone name is required (60 characters max).");
        }
        if (zoneRepo.findByNameIgnoreCase(name).isPresent()) {
            return error(HttpStatus.CONFLICT, "A zone with that name already exists. Re-enable it instead.");
        }
        MuseumZone saved = zoneRepo.save(new MuseumZone(name));
        return ResponseEntity.status(HttpStatus.CREATED).body(toJson(saved));
    }

    // Body: {"active": false} disables a zone, {"active": true} brings it back
    @PatchMapping("/api/zones/{id}")
    public ResponseEntity<Map<String, Object>> setActive(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        MuseumZone zone = zoneRepo.findById(id).orElse(null);
        if (zone == null) {
            return error(HttpStatus.NOT_FOUND, "That zone no longer exists.");
        }
        if (!(body.get("active") instanceof Boolean active)) {
            return error(HttpStatus.BAD_REQUEST, "Send {\"active\": true} or {\"active\": false}.");
        }
        zone.setActive(active);
        return ResponseEntity.ok(toJson(zoneRepo.save(zone)));
    }

    private static Map<String, Object> toJson(MuseumZone zone) {
        return Map.of("id", zone.getId(), "name", zone.getName(), "isActive", zone.isActive());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
