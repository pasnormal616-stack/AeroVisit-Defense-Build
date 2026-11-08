package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

// 📰 One photo of a News post, in displayOrder. The file was checked, downsized (max 1600 px) and re-encoded on upload
// (NewsPhotoStorage) and lives in uploads/exhibits/ under a random name; width/height are the stored file's size, so the
// feed can reserve the space before it loads. altText is optional; the feed falls back to a descriptive default.
@Entity
@Table(name = "news_photo") // MySQL indexes the post_id foreign key itself
public class NewsPhoto {

    public static final int MAX_ALT = 250;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private NewsPost post;

    @Column(nullable = false, unique = true, length = 80)
    private String filename;

    @Column(length = MAX_ALT)
    private String altText;

    @Column(nullable = false)
    private int displayOrder;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    public NewsPhoto() {}

    public NewsPhoto(NewsPost post, String filename, int displayOrder, int width, int height) {
        this.post = post;
        this.filename = filename;
        this.displayOrder = displayOrder;
        this.width = width;
        this.height = height;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public NewsPost getPost() { return post; }
    public void setPost(NewsPost post) { this.post = post; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public String getAltText() { return altText; }
    public void setAltText(String altText) { this.altText = altText; }

    public int getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(int displayOrder) { this.displayOrder = displayOrder; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }
}
