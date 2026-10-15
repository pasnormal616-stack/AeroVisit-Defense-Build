package com.paf.aerovisit.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.paf.aerovisit.model.StaffUser;
import com.paf.aerovisit.repository.StaffUserRepository;

// 👤 STAFF LOGIN (database-backed RBAC): the login form's username is looked up in the staff_user table. Spring
// Security checks the typed password against the stored BCrypt hash (SecurityConfig.passwordEncoder) and grants
// ROLE_<role>, which the URL rules in SecurityConfig and the screens decide by. Accounts: StaffUserSeeder.
// A @Service, not a SecurityConfig bean, so the @WebMvcTest slices that import SecurityConfig need no repository.
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final StaffUserRepository staffRepo;

    public CustomUserDetailsService(StaffUserRepository staffRepo) {
        this.staffRepo = staffRepo;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        StaffUser staff = staffRepo.findByUsernameIgnoreCase(username == null ? "" : username.trim())
                .orElseThrow(() -> new UsernameNotFoundException("No staff account with that username"));
        return User.withUsername(staff.getUsername())
                .password(staff.getPassword())
                .roles(staff.getRole())
                .build();
    }
}
