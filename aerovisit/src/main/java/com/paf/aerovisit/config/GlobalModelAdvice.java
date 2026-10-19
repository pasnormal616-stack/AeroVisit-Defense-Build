package com.paf.aerovisit.config;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the signed-in operator to every view so the shared command shell
 * (templates/fragments/shell.html) renders identical role-gated navigation on
 * all admin pages. Controllers that already set "loggedInUser" override this
 * with the same value, so existing behaviour is unchanged.
 */
@ControllerAdvice
public class GlobalModelAdvice {

    @ModelAttribute("loggedInUser")
    public String loggedInUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return auth.getName();
    }

    // Role flags for the templates. Screens decide what to show by ROLE, never by comparing usernames, so a renamed
    // or added staff account gets the right controls automatically.
    @ModelAttribute("isDirector")
    public boolean isDirector() {
        return SecurityConfig.isDirector();
    }

    @ModelAttribute("isGuard")
    public boolean isGuard() {
        return SecurityConfig.isGuard();
    }

    // 🤖 AI features switch (Settings): templates hide the chatbot, AI roster and forecast when false. Optional, so web
    // test slices (no AiSwitch bean) still start, and then the AI counts as on, as before the switch existed.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.paf.aerovisit.service.AiSwitch aiSwitch;

    @ModelAttribute("aiEnabled")
    public boolean aiEnabled() {
        return aiSwitch == null || aiSwitch.enabled();
    }
}
