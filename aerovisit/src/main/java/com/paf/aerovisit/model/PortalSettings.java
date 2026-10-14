package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.ArrayList;
import java.util.List;

// One-row settings for the public portal (always row 1). Managed by the Director on the Website Content page.
@Entity
public class PortalSettings {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    // YouTube embed link for the Promotional Video Tour; null means no video is shown
    @Column(length = 200)
    private String promoVideoUrl;

    // Official social media pages shown as icons in the public footer; null means that icon is hidden
    @Column(length = 300)
    private String facebookUrl;

    @Column(length = 300)
    private String instagramUrl;

    @Column(length = 300)
    private String xUrl;

    // Official museum email shown as a mailto: link in the public footer; null hides the line
    @Column(length = 150)
    private String contactEmail;

    // Official museum phone shown as a tel: link in the public footer; null hides the line
    @Column(length = 30)
    private String contactPhone;

    // Optional small italic line under the phone (e.g. a prank-call warning); only shown with a phone number
    @Column(length = 160)
    private String contactPhoneNote;

    // One footer icon: platform name (for the screen-reader label), Bootstrap Icons class and the saved link
    public record SocialLink(String name, String icon, String url) {}

    public PortalSettings() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPromoVideoUrl() { return promoVideoUrl; }
    public void setPromoVideoUrl(String promoVideoUrl) { this.promoVideoUrl = promoVideoUrl; }

    public String getFacebookUrl() { return facebookUrl; }
    public void setFacebookUrl(String facebookUrl) { this.facebookUrl = facebookUrl; }

    public String getInstagramUrl() { return instagramUrl; }
    public void setInstagramUrl(String instagramUrl) { this.instagramUrl = instagramUrl; }

    public String getXUrl() { return xUrl; }
    public void setXUrl(String xUrl) { this.xUrl = xUrl; }

    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String contactEmail) { this.contactEmail = contactEmail; }

    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String contactPhone) { this.contactPhone = contactPhone; }

    public String getContactPhoneNote() { return contactPhoneNote; }
    public void setContactPhoneNote(String contactPhoneNote) { this.contactPhoneNote = contactPhoneNote; }

    // Only the platforms that have a saved link, in footer order
    public List<SocialLink> socialLinks() {
        List<SocialLink> links = new ArrayList<>();
        addIfSet(links, "Facebook", "bi-facebook", facebookUrl);
        addIfSet(links, "Instagram", "bi-instagram", instagramUrl);
        addIfSet(links, "X (Twitter)", "bi-twitter-x", xUrl);
        return links;
    }

    private static void addIfSet(List<SocialLink> links, String name, String icon, String url) {
        if (url != null && !url.isBlank()) {
            links.add(new SocialLink(name, icon, url));
        }
    }
}
