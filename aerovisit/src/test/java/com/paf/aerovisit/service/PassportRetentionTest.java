package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.paf.aerovisit.model.PassportReview;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.PassportReviewRepository;
import com.paf.aerovisit.repository.VisitorRepository;

/**
 * 🛂 Passport files in the nightly retention purge: a finished booking's passport copy goes with its ID photos, and a
 * passport review untouched for 30 days loses its file and personal details (its emailed link stops working). A review
 * still waiting for the Director is never touched. Temp folder, no database.
 */
class PassportRetentionTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 2, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final PassportReviewRepository reviews = mock(PassportReviewRepository.class);
    private final AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, mock(EmailService.class));

    @TempDir
    Path uploads;

    private PassportReview review(long id, String status, LocalDateTime submitted, LocalDateTime decided) throws IOException {
        PassportReview r = new PassportReview();
        r.setId(id);
        r.setStatus(status);
        r.setFullName("Schmidt, Anna " + id);
        r.setEmail("anna" + id + "@example.de");
        r.setContactNumber("+49 151 2345678");
        r.setToken("tok-" + id);
        r.setPossibleBanMatch("Ban #1: Anna Schmidt");
        r.setSubmittedAt(submitted);
        r.setDecidedAt(decided);
        r.setFileName("passport-" + id + ".pdf");
        Files.writeString(uploads.resolve(r.getFileName()), "%PDF- passport " + id);
        return r;
    }

    @Test
    void aFinishedBookingsPassportCopyIsRemovedWithItsIdFiles() throws IOException {
        Visitor v = new Visitor();
        v.setId(9L);
        v.setStatus("CHECKED-OUT");
        v.setVisitDate(TODAY.minusDays(45));
        v.setFullName("Schmidt, Anna");
        v.setPassportFileUrl("passport-booking-9.pdf");
        Files.writeString(uploads.resolve(v.getPassportFileUrl()), "%PDF- booking");
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(v));

        assertThat(engine.purgeExpiredFiles(TODAY, uploads)).isEqualTo(1);
        assertThat(v.getPassportFileUrl()).isNull();
        assertThat(Files.exists(uploads.resolve("passport-booking-9.pdf"))).isFalse();
        assertThat(v.getFullName()).isEqualTo("REDACTED");
    }

    @Test
    void oldDecidedReviewsLoseTheirFileAndDetailsAndPendingOrRecentOnesAreKept() throws IOException {
        engine.passportReviews = reviews;
        PassportReview oldRejected = review(1, PassportReview.REJECTED, NOW.minusDays(50), NOW.minusDays(40));
        PassportReview oldApproved = review(2, PassportReview.APPROVED, NOW.minusDays(50), NOW.minusDays(31)); // link long expired
        PassportReview recentRejected = review(3, PassportReview.REJECTED, NOW.minusDays(20), NOW.minusDays(5));
        PassportReview oldPending = review(4, PassportReview.PENDING, NOW.minusDays(60), null);
        when(reviews.findPurgeCandidates()).thenReturn(List.of(oldRejected, oldApproved, recentRejected, oldPending));

        assertThat(engine.purgePassportReviews(NOW, uploads)).isEqualTo(2);

        for (PassportReview r : List.of(oldRejected, oldApproved)) {
            assertThat(r.getFileName()).isNull();
            assertThat(r.getFullName()).isEqualTo("REDACTED");
            assertThat(r.getEmail()).isEqualTo("REDACTED");
            assertThat(r.getContactNumber()).isEqualTo("REDACTED");
            assertThat(r.getPossibleBanMatch()).isNull();
            assertThat(r.getToken()).isNull();            // the re-upload / booking link stops working
            assertThat(r.getTokenExpiresAt()).isNull();
            verify(reviews).save(r);
        }
        assertThat(Files.exists(uploads.resolve("passport-1.pdf"))).isFalse();
        assertThat(Files.exists(uploads.resolve("passport-2.pdf"))).isFalse();

        // untouched: still inside 30 days, or still waiting for the Director
        for (PassportReview r : List.of(recentRejected, oldPending)) {
            assertThat(r.getFileName()).isNotNull();
            assertThat(Files.exists(uploads.resolve(r.getFileName()))).isTrue();
            assertThat(r.getFullName()).startsWith("Schmidt, Anna");
            assertThat(r.getToken()).isNotNull();
            verify(reviews, never()).save(r);
        }
    }

    @Test
    void aUsedReviewLeavesTheFileToItsBooking() throws IOException {
        engine.passportReviews = reviews;
        PassportReview used = review(5, PassportReview.USED, NOW.minusDays(60), NOW.minusDays(55));
        // the booking owns the file now (its own purge removes it); keep it here even if the name was not cleared
        when(reviews.findPurgeCandidates()).thenReturn(List.of(used));

        assertThat(engine.purgePassportReviews(NOW, uploads)).isEqualTo(1);
        assertThat(Files.exists(uploads.resolve("passport-5.pdf"))).isTrue();
        assertThat(used.getFileName()).isNull();
        assertThat(used.getFullName()).isEqualTo("REDACTED");
    }

    @Test
    void withoutTheRepositoryNothingIsPurged() {
        assertThat(engine.purgePassportReviews(NOW, uploads)).isZero();
    }
}
