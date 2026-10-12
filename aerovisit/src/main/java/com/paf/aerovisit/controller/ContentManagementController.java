package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.Announcement;
import com.paf.aerovisit.model.Guideline;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.repository.AnnouncementRepository;
import com.paf.aerovisit.repository.ExhibitPhotoRepository;
import com.paf.aerovisit.repository.GuidelineRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.service.ExhibitPhotoStorage;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Director-only (covered by /admin/content/** in SecurityConfig): the Website Content page that edits
// the public portal's Announcement Board, Visitor Guidelines tab and Exhibits slideshow.
@Controller
public class ContentManagementController {

    private final AnnouncementRepository announcementRepo;
    private final GuidelineRepository guidelineRepo;
    private final ExhibitPhotoRepository photoRepo;
    private final ExhibitPhotoStorage photoStorage;
    private final PortalSettingsRepository settingsRepo;

    public ContentManagementController(AnnouncementRepository announcementRepo,
                                       GuidelineRepository guidelineRepo,
                                       ExhibitPhotoRepository photoRepo,
                                       ExhibitPhotoStorage photoStorage,
                                       PortalSettingsRepository settingsRepo) {
        this.announcementRepo = announcementRepo;
        this.guidelineRepo = guidelineRepo;
        this.photoRepo = photoRepo;
        this.photoStorage = photoStorage;
        this.settingsRepo = settingsRepo;
    }

    @GetMapping("/admin/content")
    public String contentPage(Model model) {
        model.addAttribute("announcements", announcementRepo.findAllByOrderByAnnouncementDateDescIdDesc());
        model.addAttribute("guidelines", guidelineRepo.findAllByOrderByDisplayOrderAscIdAsc());
        model.addAttribute("guidelineIcons", Guideline.ICONS);
        model.addAttribute("photos", photoRepo.findAllByOrderByUploadDateAscIdAsc());
        model.addAttribute("today", LocalDate.now());
        PortalSettings settings = settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
        model.addAttribute("promoVideoUrl", settings.getPromoVideoUrl());
        model.addAttribute("facebookUrl", settings.getFacebookUrl());
        model.addAttribute("instagramUrl", settings.getInstagramUrl());
        model.addAttribute("xUrl", settings.getXUrl());
        model.addAttribute("contactEmail", settings.getContactEmail());
        return "content";
    }

    // ---------- Announcement Board ----------

    @PostMapping("/admin/content/announcements/add")
    public String addAnnouncement(@RequestParam("title") String title,
                                  @RequestParam("message") String message,
                                  @RequestParam(value = "announcementDate", required = false) String announcementDate,
                                  @RequestParam(value = "active", defaultValue = "false") boolean active,
                                  RedirectAttributes redirectAttributes) {
        Announcement announcement = new Announcement();
        String error = applyAndValidate(announcement, title, message, announcementDate, active);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/content#announcements";
        }
        announcementRepo.save(announcement);
        redirectAttributes.addFlashAttribute("success", "Announcement posted: " + announcement.getTitle()
                + (announcement.isActive() ? "" : " (hidden until you switch it on)"));
        return "redirect:/admin/content#announcements";
    }

    @PostMapping("/admin/content/announcements/{id}/update")
    public String updateAnnouncement(@PathVariable Long id,
                                     @RequestParam("title") String title,
                                     @RequestParam("message") String message,
                                     @RequestParam(value = "announcementDate", required = false) String announcementDate,
                                     @RequestParam(value = "active", defaultValue = "false") boolean active,
                                     RedirectAttributes redirectAttributes) {
        Announcement announcement = announcementRepo.findById(id).orElse(null);
        if (announcement == null) {
            redirectAttributes.addFlashAttribute("error", "That announcement no longer exists.");
            return "redirect:/admin/content#announcements";
        }
        String error = applyAndValidate(announcement, title, message, announcementDate, active);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/content#announcements";
        }
        announcementRepo.save(announcement);
        redirectAttributes.addFlashAttribute("success", "Announcement updated: " + announcement.getTitle());
        return "redirect:/admin/content#announcements";
    }

    @PostMapping("/admin/content/announcements/{id}/toggle")
    public String toggleAnnouncement(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        announcementRepo.findById(id).ifPresent(announcement -> {
            announcement.setActive(!announcement.isActive());
            announcementRepo.save(announcement);
            redirectAttributes.addFlashAttribute("success", announcement.isActive()
                    ? "Announcement is now live on the visitor portal: " + announcement.getTitle()
                    : "Announcement hidden from the visitor portal: " + announcement.getTitle());
        });
        return "redirect:/admin/content#announcements";
    }

    @PostMapping("/admin/content/announcements/{id}/delete")
    public String deleteAnnouncement(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (announcementRepo.existsById(id)) {
            announcementRepo.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Announcement deleted.");
        }
        return "redirect:/admin/content#announcements";
    }

    // Copies the form values onto the announcement; returns an error message, or null when they are valid
    private String applyAndValidate(Announcement announcement, String title, String message,
                                    String announcementDate, boolean active) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanMessage = message == null ? "" : message.trim();
        if (cleanTitle.isEmpty() || cleanTitle.length() > 120) {
            return "Announcement title is required (120 characters max).";
        }
        if (cleanMessage.isEmpty() || cleanMessage.length() > 2000) {
            return "Announcement message is required (2,000 characters max).";
        }
        LocalDate date = LocalDate.now();
        if (announcementDate != null && !announcementDate.isBlank()) {
            try {
                date = LocalDate.parse(announcementDate.trim());
            } catch (DateTimeParseException e) {
                return "Please enter a valid announcement date.";
            }
        }
        announcement.setTitle(cleanTitle);
        announcement.setMessage(cleanMessage);
        announcement.setAnnouncementDate(date);
        announcement.setActive(active);
        return null;
    }

    // ---------- Promotional Video Tour ----------

    // Any normal YouTube link (watch, youtu.be, shorts, live or embed); group 1 is the 11-character video id
    private static final Pattern YOUTUBE_LINK = Pattern.compile(
            "^(?:https?://)?(?:www\\.|m\\.)?(?:youtube\\.com/(?:watch\\?(?:[^#]*&)?v=|embed/|shorts/|live/)"
            + "|youtube-nocookie\\.com/embed/|youtu\\.be/)([A-Za-z0-9_-]{11})(?:[?&#].*)?$");

    // Saves the video shown below Museum Announcements on the portal; a blank field removes it
    @PostMapping("/admin/content/video/save")
    public String savePromoVideo(@RequestParam(value = "promoVideoUrl", required = false) String promoVideoUrl,
                                 RedirectAttributes redirectAttributes) {
        String pasted = promoVideoUrl == null ? "" : promoVideoUrl.trim();
        String embedUrl = null;
        if (!pasted.isEmpty()) {
            // Only the video id is kept and the link is rebuilt, so nothing else can ever reach the portal's iframe
            Matcher link = YOUTUBE_LINK.matcher(pasted);
            if (!link.matches()) {
                redirectAttributes.addFlashAttribute("error",
                        "That is not a YouTube video link. Paste a link like https://www.youtube.com/watch?v=...");
                return "redirect:/admin/content#announcements";
            }
            embedUrl = "https://www.youtube.com/embed/" + link.group(1);
        }
        PortalSettings settings = settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
        settings.setPromoVideoUrl(embedUrl);
        settingsRepo.save(settings);
        redirectAttributes.addFlashAttribute("success", embedUrl == null
                ? "Promotional video removed from the visitor portal."
                : "Promotional video saved. It now shows below Museum Announcements on the visitor portal.");
        return "redirect:/admin/content#announcements";
    }

    // ---------- Contact & Social Media (public footer) ----------

    // Saves the contact email and the three footer links together; a blank field hides that line or icon.
    // Nothing is saved if any field is wrong.
    @PostMapping("/admin/content/social/save")
    public String saveSocialLinks(@RequestParam(value = "contactEmail", required = false) String contactEmail,
                                  @RequestParam(value = "facebookUrl", required = false) String facebookUrl,
                                  @RequestParam(value = "instagramUrl", required = false) String instagramUrl,
                                  @RequestParam(value = "xUrl", required = false) String xUrl,
                                  RedirectAttributes redirectAttributes) {
        String facebook = socialUrl(facebookUrl, "facebook.com", "fb.com");
        String instagram = socialUrl(instagramUrl, "instagram.com");
        String x = socialUrl(xUrl, "x.com", "twitter.com");
        String email = contactEmail(contactEmail);
        List<String> wrong = new ArrayList<>();
        if (email == null) wrong.add("Contact email (an address like museum@paf.mil.ph)");
        if (facebook == null) wrong.add("Facebook (a facebook.com page link)");
        if (instagram == null) wrong.add("Instagram (an instagram.com profile link)");
        if (x == null) wrong.add("X (an x.com or twitter.com profile link)");
        if (!wrong.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Contact & social links not saved. Check: " + String.join(", ", wrong) + ".");
            return "redirect:/admin/content#announcements";
        }
        PortalSettings settings = settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
        settings.setFacebookUrl(facebook.isEmpty() ? null : facebook);
        settings.setInstagramUrl(instagram.isEmpty() ? null : instagram);
        settings.setXUrl(x.isEmpty() ? null : x);
        settings.setContactEmail(email.isEmpty() ? null : email);
        settingsRepo.save(settings);
        redirectAttributes.addFlashAttribute("success", settings.socialLinks().isEmpty() && settings.getContactEmail() == null
                ? "Contact email and social links cleared. The visitor portal footer shows neither."
                : "Contact & social links saved. Only the filled-in ones appear in the visitor portal footer.");
        return "redirect:/admin/content#announcements";
    }

    // One plain address: something@domain.tld, no spaces, commas or extra mailto: parts (?cc=, &bcc=) that would change
    // what the visitor's email app opens. "" for a blank field, null when it is not an email address.
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");

    static String contactEmail(String pasted) {
        String text = pasted == null ? "" : pasted.trim();
        if (text.isEmpty()) {
            return "";
        }
        return text.length() <= 150 && EMAIL.matcher(text).matches() ? text : null;
    }

    // A pasted page link rebuilt as https://<host><path>[?query]: "" for a blank field, null when it is not a page on
    // one of the given sites. Rebuilding means only an https link to that site can ever reach the public footer.
    static String socialUrl(String pasted, String... allowedHosts) {
        String text = pasted == null ? "" : pasted.trim();
        if (text.isEmpty()) {
            return "";
        }
        if (!text.regionMatches(true, 0, "http://", 0, 7) && !text.regionMatches(true, 0, "https://", 0, 8)) {
            text = "https://" + text;
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(text);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getPort() != -1) {
            return null;
        }
        String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean allowed = false;
        for (String allowedHost : allowedHosts) {
            allowed |= host.equals(allowedHost) || host.endsWith("." + allowedHost);
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        if (!allowed || (path.length() <= 1 && query.isEmpty())) {
            return null; // another site, or just the home page with no museum account in it
        }
        String url = "https://" + host + path + query;
        return url.length() <= 300 ? url : null;
    }

    // ---------- Visitor Guidelines ----------

    @PostMapping("/admin/content/guidelines/add")
    public String addGuideline(@RequestParam("icon") String icon,
                               @RequestParam("title") String title,
                               @RequestParam("description") String description,
                               RedirectAttributes redirectAttributes) {
        Guideline guideline = new Guideline();
        String error = applyAndValidate(guideline, icon, title, description);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/content#guidelines";
        }
        List<Guideline> existing = guidelineRepo.findAllByOrderByDisplayOrderAscIdAsc();
        guideline.setDisplayOrder(existing.isEmpty() ? 1 : existing.get(existing.size() - 1).getDisplayOrder() + 1);
        guidelineRepo.save(guideline);
        redirectAttributes.addFlashAttribute("success", "Guideline added: " + guideline.getTitle());
        return "redirect:/admin/content#guidelines";
    }

    @PostMapping("/admin/content/guidelines/{id}/update")
    public String updateGuideline(@PathVariable Long id,
                                  @RequestParam("icon") String icon,
                                  @RequestParam("title") String title,
                                  @RequestParam("description") String description,
                                  RedirectAttributes redirectAttributes) {
        Guideline guideline = guidelineRepo.findById(id).orElse(null);
        if (guideline == null) {
            redirectAttributes.addFlashAttribute("error", "That guideline no longer exists.");
            return "redirect:/admin/content#guidelines";
        }
        String error = applyAndValidate(guideline, icon, title, description);
        if (error != null) {
            redirectAttributes.addFlashAttribute("error", error);
            return "redirect:/admin/content#guidelines";
        }
        guidelineRepo.save(guideline);
        redirectAttributes.addFlashAttribute("success", "Guideline updated: " + guideline.getTitle());
        return "redirect:/admin/content#guidelines";
    }

    @PostMapping("/admin/content/guidelines/{id}/delete")
    public String deleteGuideline(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        if (guidelineRepo.existsById(id)) {
            guidelineRepo.deleteById(id);
            redirectAttributes.addFlashAttribute("success", "Guideline deleted.");
        }
        return "redirect:/admin/content#guidelines";
    }

    // Moves a card one place up or down, then renumbers all cards 1, 2, 3... so the order stays tidy
    @PostMapping("/admin/content/guidelines/{id}/move")
    public String moveGuideline(@PathVariable Long id, @RequestParam("direction") String direction) {
        List<Guideline> ordered = new ArrayList<>(guidelineRepo.findAllByOrderByDisplayOrderAscIdAsc());
        int index = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(id)) index = i;
        }
        int target = "up".equals(direction) ? index - 1 : index + 1;
        if (index < 0 || target < 0 || target >= ordered.size()) {
            return "redirect:/admin/content#guidelines";
        }
        ordered.add(target, ordered.remove(index));
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setDisplayOrder(i + 1);
        }
        guidelineRepo.saveAll(ordered);
        return "redirect:/admin/content#guidelines";
    }

    // Copies the form values onto the guideline; returns an error message, or null when they are valid
    private String applyAndValidate(Guideline guideline, String icon, String title, String description) {
        String cleanTitle = title == null ? "" : title.trim();
        String cleanDescription = description == null ? "" : description.trim();
        if (!Guideline.ICONS.containsKey(icon)) {
            return "Please choose an icon from the list.";
        }
        if (cleanTitle.isEmpty() || cleanTitle.length() > 80) {
            return "Guideline title is required (80 characters max).";
        }
        if (cleanDescription.isEmpty() || cleanDescription.length() > 1000) {
            return "Guideline description is required (1,000 characters max).";
        }
        guideline.setIcon(icon);
        guideline.setTitle(cleanTitle);
        guideline.setDescription(cleanDescription);
        return null;
    }

    // ---------- Exhibits slideshow ----------

    @PostMapping("/admin/content/photos/upload")
    public String uploadPhotos(@RequestParam(value = "photos", required = false) List<MultipartFile> photos,
                               RedirectAttributes redirectAttributes) {
        List<MultipartFile> chosen = photos == null ? List.of()
                : photos.stream().filter(p -> p != null && !p.isEmpty()).toList();
        if (chosen.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Please choose at least one photo to upload.");
            return "redirect:/admin/content#photos";
        }
        int saved = 0;
        List<String> problems = new ArrayList<>();
        for (MultipartFile photo : chosen) {
            try {
                photoStorage.store(photo);
                saved++;
            } catch (ExhibitPhotoStorage.InvalidImageException e) {
                problems.add(e.getMessage());
            } catch (IOException e) {
                problems.add("A photo could not be saved on the server. Please try again.");
            }
        }
        if (saved > 0) {
            redirectAttributes.addFlashAttribute("success", saved == 1
                    ? "1 photo added to the Exhibits slideshow."
                    : saved + " photos added to the Exhibits slideshow.");
        }
        if (!problems.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Not uploaded: " + String.join(" ", problems));
        }
        return "redirect:/admin/content#photos";
    }

    @PostMapping("/admin/content/photos/{id}/delete")
    public String deletePhoto(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            if (photoStorage.delete(id)) {
                redirectAttributes.addFlashAttribute("success", "Photo removed from the Exhibits slideshow.");
            }
        } catch (IOException e) {
            redirectAttributes.addFlashAttribute("error", "The photo file could not be deleted. Please try again.");
        }
        return "redirect:/admin/content#photos";
    }
}
