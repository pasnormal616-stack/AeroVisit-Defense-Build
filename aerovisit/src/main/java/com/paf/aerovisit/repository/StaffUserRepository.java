package com.paf.aerovisit.repository;

import com.paf.aerovisit.model.StaffUser;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface StaffUserRepository extends JpaRepository<StaffUser, Long> {

    // Login lookup: usernames are email-like, so the case typed at the login form does not matter
    Optional<StaffUser> findByUsernameIgnoreCase(String username);

    // The single account of a role (the Director, or the Guard)
    Optional<StaffUser> findFirstByRoleOrderByIdAsc(String role);
}
