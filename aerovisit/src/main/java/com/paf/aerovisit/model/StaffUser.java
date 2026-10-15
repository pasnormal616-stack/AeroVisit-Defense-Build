package com.paf.aerovisit.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

// 👤 A staff login account (role-based access control). Two exist: the Director (role DIRECTOR) and the Guard
// (role GUARD). The password is stored ONLY as a BCrypt hash, never as plain text.
@Entity
public class StaffUser {

    public static final String DIRECTOR = "DIRECTOR";
    public static final String GUARD = "GUARD";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String username;

    // BCrypt hash ($2a$10$..., 60 characters)
    @Column(nullable = false, length = 100)
    private String password;

    // DIRECTOR or GUARD, without Spring's "ROLE_" prefix (SecurityConfig's hasRole(...) adds it)
    @Column(nullable = false, length = 20)
    private String role;

    public StaffUser() {}

    public StaffUser(String username, String passwordHash, String role) {
        this.username = username;
        this.password = passwordHash;
        this.role = role;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String passwordHash) { this.password = passwordHash; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
}
