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

    // 🤖 AI features switch (Settings page, Director + emailed code). Null on rows from before the switch = on.
    @Column(columnDefinition = "boolean default true")
    private Boolean aiEnabled;

    // 💱 Admission unit prices in whole centavos (Settings page, Director). Null on rows from before prices = the defaults.
    public static final long DEFAULT_REGULAR_PRICE_CENTAVOS = 15_000L;     // PHP 150.00
    public static final long DEFAULT_DISCOUNTED_PRICE_CENTAVOS = 10_000L;  // PHP 100.00

    private Long regularPriceCentavos;

    private Long discountedPriceCentavos;

    // 💳 Payment settings (Settings page, Director). Hours an approved visitor has to pay (null = 48), and the Landbank
    // payment page: null = the default link, "" = cleared by the Director (the button is hidden).
    public static final int DEFAULT_PAYMENT_WINDOW_HOURS = 48;
    public static final String DEFAULT_LANDBANK_URL = "https://www.lbp-eservices.com/egps/portal/index.jsp";

    private Integer paymentWindowHours;

    @Column(length = 300)
    private String landbankUrl;

    // 🚶 Walk-in settings (Settings page, Director): the largest group one walk-in booking may hold (null = 20), and the
    // minutes a walk-in has to pay or, when free, to check in (null = 30)
    public static final int DEFAULT_WALK_IN_MAX_PAX = 20;
    public static final int DEFAULT_WALK_IN_MINUTES = 30;

    private Integer walkInMaxPax;

    private Integer walkInPaymentMinutes;

    // 🔑 Secret behind the daily walk-in code (WalkInService.todaysCode): random, made on first use, replaced by the
    // Director's "New code for today". Never shown anywhere; only the day's code derived from it is.
    @Column(length = 64)
    private String walkInCodeSecret;

    // 📝 Minutes after a visit's time window ends before its feedback email is sent (Settings, Director; null = 30)
    public static final int DEFAULT_FEEDBACK_DELAY_MINUTES = 30;

    private Integer feedbackDelayMinutes;

    // 📅 Advance bookings: the fewest days ahead an advance visit date may be (Settings, Director; null = 1, so today is
    // Walk-in only and the earliest advance date is tomorrow, Manila time)
    public static final int DEFAULT_ADVANCE_MIN_DAYS = 1;

    private Integer advanceMinDaysAhead;

    // One footer icon: platform name (for the screen-reader label), Bootstrap Icons class and the saved link
    public record SocialLink(String name, String icon, String url) {}

    public PortalSettings() {}

    public Long getId() { return id; }

    public boolean isAiEnabled() { return aiEnabled == null || aiEnabled; }
    public void setAiEnabled(boolean aiEnabled) { this.aiEnabled = aiEnabled; }
    public void setId(Long id) { this.id = id; }

    // The prices in force: the saved ones, or the defaults while none has been saved
    public long regularPrice() { return regularPriceCentavos != null ? regularPriceCentavos : DEFAULT_REGULAR_PRICE_CENTAVOS; }
    public long discountedPrice() { return discountedPriceCentavos != null ? discountedPriceCentavos : DEFAULT_DISCOUNTED_PRICE_CENTAVOS; }

    // The payment settings in force
    public int paymentWindow() { return paymentWindowHours != null ? paymentWindowHours : DEFAULT_PAYMENT_WINDOW_HOURS; }
    public String landbankLink() { return landbankUrl == null ? DEFAULT_LANDBANK_URL : landbankUrl; }

    public Integer getPaymentWindowHours() { return paymentWindowHours; }
    public void setPaymentWindowHours(Integer paymentWindowHours) { this.paymentWindowHours = paymentWindowHours; }
    public String getLandbankUrl() { return landbankUrl; }
    public void setLandbankUrl(String landbankUrl) { this.landbankUrl = landbankUrl; }

    // The walk-in settings in force
    public int walkInMax() { return walkInMaxPax != null ? walkInMaxPax : DEFAULT_WALK_IN_MAX_PAX; }
    public int walkInMinutes() { return walkInPaymentMinutes != null ? walkInPaymentMinutes : DEFAULT_WALK_IN_MINUTES; }

    public int feedbackDelay() { return feedbackDelayMinutes != null ? feedbackDelayMinutes : DEFAULT_FEEDBACK_DELAY_MINUTES; }

    public int advanceMinDays() { return advanceMinDaysAhead != null ? advanceMinDaysAhead : DEFAULT_ADVANCE_MIN_DAYS; }

    public Integer getAdvanceMinDaysAhead() { return advanceMinDaysAhead; }
    public void setAdvanceMinDaysAhead(Integer advanceMinDaysAhead) { this.advanceMinDaysAhead = advanceMinDaysAhead; }

    public Integer getFeedbackDelayMinutes() { return feedbackDelayMinutes; }
    public void setFeedbackDelayMinutes(Integer feedbackDelayMinutes) { this.feedbackDelayMinutes = feedbackDelayMinutes; }

    public Integer getWalkInMaxPax() { return walkInMaxPax; }
    public void setWalkInMaxPax(Integer walkInMaxPax) { this.walkInMaxPax = walkInMaxPax; }
    public Integer getWalkInPaymentMinutes() { return walkInPaymentMinutes; }
    public void setWalkInPaymentMinutes(Integer walkInPaymentMinutes) { this.walkInPaymentMinutes = walkInPaymentMinutes; }
    public String getWalkInCodeSecret() { return walkInCodeSecret; }
    public void setWalkInCodeSecret(String walkInCodeSecret) { this.walkInCodeSecret = walkInCodeSecret; }

    public Long getRegularPriceCentavos() { return regularPriceCentavos; }
    public void setRegularPriceCentavos(Long regularPriceCentavos) { this.regularPriceCentavos = regularPriceCentavos; }

    public Long getDiscountedPriceCentavos() { return discountedPriceCentavos; }
    public void setDiscountedPriceCentavos(Long discountedPriceCentavos) { this.discountedPriceCentavos = discountedPriceCentavos; }

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
