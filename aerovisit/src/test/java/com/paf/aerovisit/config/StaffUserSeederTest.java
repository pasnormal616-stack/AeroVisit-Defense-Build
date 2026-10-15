package com.paf.aerovisit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.paf.aerovisit.model.StaffUser;
import com.paf.aerovisit.repository.StaffUserRepository;

/** First start with an empty staff table: the Director and Guard accounts are created with BCrypt-hashed passwords. */
class StaffUserSeederTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @SuppressWarnings("unchecked")
    private List<StaffUser> seed(StaffUserRepository repo, String directorPassword, String guardPassword) {
        new StaffUserSeeder(repo, encoder, true, directorPassword, guardPassword).run(null);
        ArgumentCaptor<Iterable<StaffUser>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(repo).saveAll(saved.capture());
        return (List<StaffUser>) saved.getValue();
    }

    private static StaffUserRepository emptyRepo() {
        StaffUserRepository repo = mock(StaffUserRepository.class);
        when(repo.count()).thenReturn(0L);
        return repo;
    }

    @Test
    void seedsTheDirectorAndGuardWithHashedConfiguredPasswords() {
        List<StaffUser> accounts = seed(emptyRepo(), "admin123", "guard123");

        assertThat(accounts).extracting(StaffUser::getUsername).containsExactly("PAFMuseumOfficial@gmail.com", "guard");
        assertThat(accounts).extracting(StaffUser::getRole).containsExactly("DIRECTOR", "GUARD");
        StaffUser director = accounts.get(0);
        StaffUser guard = accounts.get(1);
        assertThat(director.getPassword()).startsWith("$2").isNotEqualTo("admin123");
        assertThat(encoder.matches("admin123", director.getPassword())).isTrue();
        assertThat(encoder.matches("guard123", guard.getPassword())).isTrue();
        assertThat(encoder.matches("admin123", guard.getPassword())).isFalse();
    }

    @Test
    void aBlankConfiguredPasswordGetsARandomOneInsteadOfAGuessableDefault() {
        List<StaffUser> accounts = seed(emptyRepo(), "", " ");
        for (StaffUser account : accounts) {
            assertThat(encoder.matches("", account.getPassword())).isFalse();
            assertThat(encoder.matches("admin123", account.getPassword())).isFalse();
            assertThat(encoder.matches("guard123", account.getPassword())).isFalse();
        }
    }

    @Test
    void existingAccountsAreNeverOverwritten() {
        StaffUserRepository seeded = mock(StaffUserRepository.class);
        when(seeded.count()).thenReturn(2L);
        new StaffUserSeeder(seeded, encoder, true, "admin123", "guard123").run(null);
        verify(seeded, never()).saveAll(any());
    }

    @Test
    void switchedOffItDoesNotTouchTheDatabase() {
        StaffUserRepository repo = emptyRepo();
        new StaffUserSeeder(repo, encoder, false, "admin123", "guard123").run(null);
        verify(repo, never()).count();
        verify(repo, never()).saveAll(any());
    }
}
