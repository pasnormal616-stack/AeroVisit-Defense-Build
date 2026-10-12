package com.paf.aerovisit.config;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 🛡️ CSRF ON: every POST / PUT / PATCH / DELETE must carry the session's token (hidden "_csrf" field in forms,
            // X-CSRF-TOKEN header in fetch calls, read from the <meta> tags). One exception: the public booking upload
            // /save. Its multipart body is not parsed yet when this filter runs (an over-size photo would turn into a 403
            // instead of the "photo too large" message), and it carries its own session-bound one-time token instead:
            // the KYC nonce, checked and burned by VisitorPortalController before anything else.
            .csrf(csrf -> csrf.ignoringRequestMatchers("/save"))
            .authorizeHttpRequests(auth -> auth
                // 1. PUBLIC SECTOR: Permitting pages, uploads, static resources, and Chatbot/AI API endpoints
                .requestMatchers("/", "/home", "/save", "/success-page", "/check-status", "/manage-booking", "/manage-booking/**", "/error", "/login", "/css/**", "/js/**", "/styles/**", "/images/**", "/downloads/**", "/leave-feedback", "/submit-feedback", "/api/chatbot/**", "/chat").permitAll()

                // 📧 Booking-form email verification codes (OtpController): public, POST only
                .requestMatchers(HttpMethod.POST, "/otp/send", "/otp/verify").permitAll()

                // 2. EXPLICIT LOGIN PATHS: Ensure login endpoints are open
                .requestMatchers("/admin/login", "/guard/login").permitAll()

                // 🖼️ Exhibits slideshow photos (Website Content) are public; they live apart from /uploads/**
                .requestMatchers("/exhibit-photos/**").permitAll()

                // 🗺️ Museum zones: the feedback form reads the active list; adding/disabling zones is Director-only
                .requestMatchers(HttpMethod.GET, "/api/zones").permitAll()
                .requestMatchers("/api/zones", "/api/zones/**").hasRole("DIRECTOR")

                // 🔒 Visitor ID photos and entry QR codes: staff only (file names are guessable, e.g. QR_5.png)
                .requestMatchers("/uploads/**").hasAnyRole("GUARD", "DIRECTOR")

                // 3. HIGH COMMAND (DIRECTOR ONLY): Restrict sensitive operations
                .requestMatchers("/admin/staff/**", "/admin/slots/**", "/admin/report", "/admin/export-excel", "/admin/inject-mock-data",
                                 "/admin/delete/**", "/admin/bans/**", "/admin/employee/add", "/admin/employee/edit/**", "/admin/employee/delete/**").hasRole("DIRECTOR")

                // 3b. WEBSITE CONTENT (DIRECTOR ONLY): announcements, visitor guidelines and exhibit photos
                .requestMatchers("/admin/content", "/admin/content/**").hasRole("DIRECTOR")

                // 3c. CAPACITY OVERRIDE (DIRECTOR ONLY): Force Approve skips the seat check, so a Guard must not reach it
                .requestMatchers("/admin/approve-override/**").hasRole("DIRECTOR")

                // 3d. 🛂 BOOKING REVIEW (DIRECTOR ONLY, least privilege): guards work the gate, they never approve, reject or
                // KYC-review a booking. The full manifest (/admin) sends a guard to the Guard Dashboard (AdminDashboardController).
                .requestMatchers("/admin/approve/**", "/admin/reject/**", "/admin/reject-visitor", "/admin/api/pending-count").hasRole("DIRECTOR")
                .requestMatchers("/api/booking/**").hasRole("DIRECTOR")

                // 3e. 🛡️ GUARD DASHBOARD: today's arrivals, visitors inside and visitors who left (the Director may open it too)
                .requestMatchers("/guard/dashboard", "/guard/dashboard/**").hasAnyRole("GUARD", "DIRECTOR")

                // 4. BASE SECURITY (GUARD & DIRECTOR): Shared operational access (including Ban Visitor; lifting a ban is Director-only above)
                .requestMatchers("/admin/**").hasAnyRole("GUARD", "DIRECTOR")

                // 4b. Header bell notifications (shell.html polling script): staff only
                .requestMatchers("/api/notifications", "/api/notifications/**").hasAnyRole("GUARD", "DIRECTOR")
                
                // 5. Any other request requires authentication
                .anyRequest().authenticated()
            )
            // 🧭 An anonymous visitor on a bad URL gets the 404 page instead of the staff login (see notFoundOrLogin)
            .exceptionHandling(ex -> ex.authenticationEntryPoint(notFoundOrLogin()))
            .formLogin(form -> form
                .loginPage("/login") // Tells Spring to use your custom page
                .defaultSuccessUrl("/admin", true) // Send them straight to dashboard after login
                .permitAll()
            )
            // 🚪 LOGOUT: POST /logout only (CSRF is on, so a link or an image on another site can't sign a guard out, and a
            // GET can't sign anyone out by accident). The session is invalidated, the security context cleared and the
            // session cookie deleted, so the Back button can't reopen the dashboard.
            .logout(logout -> logout
                .logoutUrl("/logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("JSESSIONID")
                .logoutSuccessUrl("/login?logout") // Sends them back to the login screen
                .permitAll()
            );

        return http.build();
    }

    // Is the signed-in user the Director? Server-side role check for the few handlers that serve both roles differently.
    public static boolean isDirector() {
        return hasRole("ROLE_DIRECTOR");
    }

    // Same check for the Guard role (the screens use it instead of comparing usernames)
    public static boolean isGuard() {
        return hasRole("ROLE_GUARD");
    }

    private static boolean hasRole(String authority) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    // Staff areas: a logged-out user is sent to the login page, as before. Everything protected lives under these.
    static final java.util.List<String> STAFF_AREAS = java.util.List.of("/admin", "/api/", "/uploads/", "/guard/");

    // 🧭 Who reaches the login page when not signed in: only requests for a staff area. Any other unknown URL
    // (a typo, an old link) is answered with 404, so the public sees "Page Not Found" instead of the staff login.
    // Access is unchanged: the request is still refused either way; only the response differs.
    static org.springframework.security.web.AuthenticationEntryPoint notFoundOrLogin() {
        org.springframework.security.web.AuthenticationEntryPoint login =
                new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login");
        return (request, response, authException) -> {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (STAFF_AREAS.stream().anyMatch(path::startsWith)) {
                login.commence(request, response, authException);
            } else {
                response.sendError(jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND);
            }
        };
    }

    // 👤 STAFF ACCOUNTS: one Director (the admin role: approvals, KYC review, staff, analytics) and one Guard (gate).
    // Screens and handlers decide by ROLE, never by username.
    // 🔒 Passwords come from the environment / .env (see application.properties), never from source code.
    @Bean
    public UserDetailsService userDetailsService(
            @Value("${aerovisit.security.director-password:}") String directorPassword,
            @Value("${aerovisit.security.guard-password:}") String guardPassword) {
        return new InMemoryUserDetailsManager(
            account("director", "DIRECTOR", "AEROVISIT_DIRECTOR_PASSWORD", directorPassword),
            account("guard", "GUARD", "AEROVISIT_GUARD_PASSWORD", guardPassword));
    }

    private UserDetails account(String username, String role, String envVar, String configuredPassword) {
        return User.builder()
            .username(username)
            .password(passwordEncoder().encode(resolvePassword(username, envVar, configuredPassword)))
            .roles(role)
            .build();
    }

    // No password configured → generate a strong one-time password for this run instead of a guessable default
    private static String resolvePassword(String username, String envVar, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        System.err.println("⚠️ " + envVar + " is not set. Temporary password for '" + username + "' (this run only): " + generated);
        return generated;
    }

    // PASSWORD ENCODER (Required by Spring Security)
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}