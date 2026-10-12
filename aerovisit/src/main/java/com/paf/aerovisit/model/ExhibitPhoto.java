package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

// A photo in the public Exhibits slideshow. The file lives in uploads/exhibits/ under a server-generated name
// and is served publicly at /exhibit-photos/{filename} (the rest of /uploads stays staff-only).
@Entity
public class ExhibitPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String filename;

    @Column(nullable = false)
    private LocalDateTime uploadDate;

    public ExhibitPhoto() {}

    public ExhibitPhoto(String filename, LocalDateTime uploadDate) {
        this.filename = filename;
        this.uploadDate = uploadDate;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public LocalDateTime getUploadDate() { return uploadDate; }
    public void setUploadDate(LocalDateTime uploadDate) { this.uploadDate = uploadDate; }
}
