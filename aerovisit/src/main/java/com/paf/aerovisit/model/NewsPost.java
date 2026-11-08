package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;

// 📰 One post of the visitor portal's News feed (Website Content > News). The caption is plain text, always shown escaped.
// A draft (published = false) is never shown to visitors. Pinned posts come first, then the newest postDate.
// Photos are NewsPhoto rows (files in uploads/exhibits/, served at /exhibit-photos/{filename}).
@Entity
@Table(name = "news_post")
public class NewsPost {

    public static final int MAX_CAPTION = 2200;
    public static final int MAX_PHOTOS = 10;
    public static final int PAGE_SIZE = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = MAX_CAPTION)
    private String caption;

    @Column(nullable = false)
    private LocalDate postDate;

    @Column(nullable = false)
    private boolean published;

    @Column(nullable = false)
    private boolean pinned;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public NewsPost() {}

    public NewsPost(String caption, LocalDate postDate, LocalDateTime createdAt) {
        this.caption = caption;
        this.postDate = postDate;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }

    public LocalDate getPostDate() { return postDate; }
    public void setPostDate(LocalDate postDate) { this.postDate = postDate; }

    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
