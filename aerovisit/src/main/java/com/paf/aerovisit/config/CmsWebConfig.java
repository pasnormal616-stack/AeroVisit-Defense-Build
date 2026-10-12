package com.paf.aerovisit.config;

import com.paf.aerovisit.model.Guideline;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.repository.AnnouncementRepository;
import com.paf.aerovisit.repository.ExhibitPhotoRepository;
import com.paf.aerovisit.repository.GuidelineRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * Website Content (mini-CMS) wiring for the public portal, kept separate from the booking code:
 *  - adds the Director-managed announcements, guidelines and exhibit photos to the "register" view
 *    whenever it renders, so VisitorPortalController does not need to change;
 *  - adds the footer's contact email and social media links to every public page that shows the footer;
 *  - serves exhibit photos publicly at /exhibit-photos/** (the rest of /uploads stays staff-only).
 * Repositories are looked up lazily, so web-layer test slices without JPA still start, and any
 * content problem falls back to defaults instead of breaking the booking page.
 */
@Configuration
public class CmsWebConfig implements WebMvcConfigurer {

    // Views that include fragments/visitor :: footer
    static final Set<String> FOOTER_VIEWS = Set.of("register", "manage-booking", "success");

    private final ObjectProvider<AnnouncementRepository> announcementRepo;
    private final ObjectProvider<GuidelineRepository> guidelineRepo;
    private final ObjectProvider<ExhibitPhotoRepository> photoRepo;
    private final ObjectProvider<PortalSettingsRepository> settingsRepo;
    private final String exhibitsDir;

    public CmsWebConfig(ObjectProvider<AnnouncementRepository> announcementRepo,
                        ObjectProvider<GuidelineRepository> guidelineRepo,
                        ObjectProvider<ExhibitPhotoRepository> photoRepo,
                        ObjectProvider<PortalSettingsRepository> settingsRepo,
                        @Value("${aerovisit.exhibits.dir:uploads/exhibits}") String exhibitsDir) {
        this.announcementRepo = announcementRepo;
        this.guidelineRepo = guidelineRepo;
        this.photoRepo = photoRepo;
        this.settingsRepo = settingsRepo;
        this.exhibitsDir = exhibitsDir;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public void postHandle(HttpServletRequest request, HttpServletResponse response,
                                   Object handler, ModelAndView mav) {
                // No view name (no ModelAndView, a View object such as a redirect, or a @ResponseBody/void handler):
                // nothing to add. Checked first because Set.of(...).contains(null) throws a NullPointerException.
                if (mav == null || mav.getViewName() == null) {
                    return;
                }
                if ("register".equals(mav.getViewName())) {
                    addPortalContent(mav);
                }
                if (FOOTER_VIEWS.contains(mav.getViewName())) {
                    addFooterContent(mav);
                }
            }
        });
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/exhibit-photos/**")
                .addResourceLocations(Paths.get(exhibitsDir).toAbsolutePath().normalize().toUri().toString())
                .setCachePeriod(86400); // file names are random and never reused
    }

    void addPortalContent(ModelAndView mav) {
        List<?> announcements = List.of();
        List<?> guidelines = Guideline.defaults();
        List<?> photos = List.of();
        String promoVideoUrl = null;
        try {
            AnnouncementRepository annRepo = announcementRepo.getIfAvailable();
            if (annRepo != null) {
                announcements = annRepo.findByActiveTrueOrderByAnnouncementDateDescIdDesc();
            }
            GuidelineRepository rulesRepo = guidelineRepo.getIfAvailable();
            if (rulesRepo != null) {
                guidelines = rulesRepo.findAllByOrderByDisplayOrderAscIdAsc();
            }
            ExhibitPhotoRepository exhibitRepo = photoRepo.getIfAvailable();
            if (exhibitRepo != null) {
                photos = exhibitRepo.findAllByOrderByUploadDateAscIdAsc();
            }
            PortalSettingsRepository portalRepo = settingsRepo.getIfAvailable();
            if (portalRepo != null) {
                promoVideoUrl = portalRepo.findById(PortalSettings.SINGLETON_ID)
                        .map(PortalSettings::getPromoVideoUrl).orElse(null);
            }
        } catch (RuntimeException e) {
            System.err.println("⚠️ Website content could not be loaded; showing defaults. " + e.getMessage());
        }
        mav.addObject("announcements", announcements);
        mav.addObject("guidelines", guidelines);
        mav.addObject("exhibitPhotos", photos);
        mav.addObject("promoVideoUrl", promoVideoUrl);
    }

    // Only platforms with a saved link are passed on, so the footer never renders an empty icon;
    // a blank contact email is passed as null so the footer hides that line
    void addFooterContent(ModelAndView mav) {
        List<PortalSettings.SocialLink> socialLinks = List.of();
        String contactEmail = null;
        try {
            PortalSettingsRepository portalRepo = settingsRepo.getIfAvailable();
            if (portalRepo != null) {
                PortalSettings settings = portalRepo.findById(PortalSettings.SINGLETON_ID).orElse(null);
                if (settings != null) {
                    socialLinks = settings.socialLinks();
                    String email = settings.getContactEmail();
                    contactEmail = (email == null || email.isBlank()) ? null : email.trim();
                }
            }
        } catch (RuntimeException e) {
            System.err.println("⚠️ Footer contact details could not be loaded; hiding them. " + e.getMessage());
        }
        mav.addObject("socialLinks", socialLinks);
        mav.addObject("contactEmail", contactEmail);
    }
}
