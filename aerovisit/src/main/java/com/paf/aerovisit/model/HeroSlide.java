package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDateTime;

// A background photo of the visitor portal's hero slideshow (Website Content > Hero Slideshow). The file lives with the
// exhibit photos in uploads/exhibits/ under a server-generated name and is served publicly at /exhibit-photos/{filename}.
// Kicker and caption are optional words shown over the photo; slides play in displayOrder.
@Entity
public class HeroSlide {

    public static final int MAX_SLIDES = 8;
    public static final int MAX_KICKER = 40;
    public static final int MAX_CAPTION = 90;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String filename;

    @Column(length = MAX_KICKER)
    private String kicker;

    @Column(length = MAX_CAPTION)
    private String caption;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private LocalDateTime uploadDate;

    public HeroSlide() {}

    public HeroSlide(String filename, int displayOrder, LocalDateTime uploadDate) {
        this.filename = filename;
        this.displayOrder = displayOrder;
        this.uploadDate = uploadDate;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getKicker() { return kicker; }
    public void setKicker(String kicker) { this.kicker = kicker; }

    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public LocalDateTime getUploadDate() { return uploadDate; }
    public void setUploadDate(LocalDateTime uploadDate) { this.uploadDate = uploadDate; }
}
