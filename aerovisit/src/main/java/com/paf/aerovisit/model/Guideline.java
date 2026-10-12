package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// One rule card in the public Visitor Guidelines tab (icon + title + plain-text description).
// Managed by the Director on the Website Content page; the portal renders the card HTML itself.
@Entity
public class Guideline {

    // The icons the Director can pick from (Bootstrap Icons class -> label shown in the dropdown).
    // Anything else is refused, so the portal only ever renders one of these classes.
    public static final Map<String, String> ICONS;
    static {
        Map<String, String> icons = new LinkedHashMap<>();
        icons.put("bi-person-badge", "ID / Identification");
        icons.put("bi-shop", "Dress code");
        icons.put("bi-camera", "Photography");
        icons.put("bi-exclamation-triangle", "Warning");
        icons.put("bi-bag-x", "No bags");
        icons.put("bi-cup-straw", "No food or drinks");
        icons.put("bi-clock", "Schedule / Time");
        icons.put("bi-people", "Groups");
        icons.put("bi-universal-access", "Accessibility");
        icons.put("bi-shield-check", "Security");
        ICONS = Collections.unmodifiableMap(icons);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String icon;

    @Column(nullable = false, length = 80)
    private String title;

    // Plain text; line breaks are kept when displayed
    @Column(nullable = false, length = 1000)
    private String description;

    @Column(nullable = false)
    private Integer displayOrder;

    public Guideline() {}

    public Guideline(String icon, String title, String description, Integer displayOrder) {
        this.icon = icon;
        this.title = title;
        this.description = description;
        this.displayOrder = displayOrder;
    }

    // The two rules the Guidelines tab showed when it was hardcoded (seeded on first start)
    public static List<Guideline> defaults() {
        return List.of(
            new Guideline("bi-person-badge", "Identification",
                "All civilian visitors must surrender a valid Government ID at the main gate in exchange for a visitor's pass.", 1),
            new Guideline("bi-shop", "Dress Code",
                "Smart casual attire is required. Sando, shorts, and open-toed slippers are strictly prohibited inside the facility.", 2)
        );
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
}
