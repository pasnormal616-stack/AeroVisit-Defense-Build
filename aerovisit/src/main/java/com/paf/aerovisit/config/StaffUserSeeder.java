package com.paf.aerovisit.config;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.paf.aerovisit.model.StaffUser;
import com.paf.aerovisit.repository.StaffUserRepository;

// First start only (empty staff table): creates the Director and the Guard login accounts, passwords BCrypt-hashed.
// The starting passwords are the configured ones (AEROVISIT_DIRECTOR_PASSWORD / AEROVISIT_GUARD_PASSWORD, see
// application.properties). After that the accounts live in the database and these settings are no longer read.
// Off in the test suite (aerovisit.staff.seed-enabled=false), so tests never write staff accounts into the real database.
@Component
public class StaffUserSeeder implements ApplicationRunner {

    static final String DIRECTOR_USERNAME = "PAFMuseumOfficial@gmail.com";
    static final String GUARD_USERNAME = "guard";

    private final StaffUserRepository staffRepo;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String directorPassword;
    private final String guardPassword;

    public StaffUserSeeder(StaffUserRepository staffRepo, PasswordEncoder passwordEncoder,
                           @Value("${aerovisit.staff.seed-enabled:true}") boolean enabled,
                           @Value("${aerovisit.security.director-password:}") String directorPassword,
                           @Value("${aerovisit.security.guard-password:}") String guardPassword) {
        this.staffRepo = staffRepo;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.directorPassword = directorPassword;
        this.guardPassword = guardPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || staffRepo.count() > 0) return;
        staffRepo.saveAll(List.of(
            account(DIRECTOR_USERNAME, StaffUser.DIRECTOR, "AEROVISIT_DIRECTOR_PASSWORD", directorPassword),
            account(GUARD_USERNAME, StaffUser.GUARD, "AEROVISIT_GUARD_PASSWORD", guardPassword)));
        System.out.println("👤 Staff accounts created: Director '" + DIRECTOR_USERNAME + "' and Guard '" + GUARD_USERNAME + "'.");
    }

    private StaffUser account(String username, String role, String envVar, String configuredPassword) {
        return new StaffUser(username, passwordEncoder.encode(resolvePassword(username, envVar, configuredPassword)), role);
    }

    // No password configured → a strong random one instead of a guessable default, shown once in the console
    private static String resolvePassword(String username, String envVar, String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        byte[] bytes = new byte[12];
        new SecureRandom().nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        System.err.println("⚠️ " + envVar + " is not set. Starting password for '" + username + "' (shown only now): " + generated);
        return generated;
    }
}
