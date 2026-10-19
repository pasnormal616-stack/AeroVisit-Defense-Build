package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.annotation.Scheduled;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;

/** Nightly 2 AM purge: finished visits older than 30 days lose their ID photo + QR files; the database row stays. Temp folder, no database. */
class DataRetentionPurgeTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final LocalDate CUTOFF = TODAY.minusDays(30);

    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final EmailService emailService = mock(EmailService.class);
    private final AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, emailService);

    @TempDir
    Path uploads;

    private Visitor visit(long id, String status, LocalDate date) throws IOException {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setStatus(status);
        v.setVisitDate(date);
        v.setIdImageUrl("photo-" + id + ".jpg");
        v.setQrCodeUrl("QR_" + id + ".png");
        Files.writeString(uploads.resolve(v.getIdImageUrl()), "id photo");
        Files.writeString(uploads.resolve(v.getQrCodeUrl()), "qr");
        return v;
    }

    @Test
    void runsNightlyAtTwoAm() throws Exception {
        Method purge = AeroVisitAutomationEngine.class.getMethod("executeDataRetentionPurge");
        assertThat(purge.getAnnotation(Scheduled.class).cron()).isEqualTo("0 0 2 * * ?");
    }

    @Test
    void asksOnlyForTheSevenFinishedStatusesOlderThan30Days() {
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of());
        engine.purgeExpiredFiles(TODAY, uploads);
        verify(repo).findExpiredWithFiles(eq(Set.of("CHECKED-OUT", "NO_SHOW", "CANCELLED", "REJECTED",
                "CANCELLED_BY_VISITOR", "CANCELLED_OVERRIDE", "DENIED_ENTRY")), eq(CUTOFF));
    }

    @Test
    void visitorCancelledAndVipOverrideBookingsLoseTheirPhotosAndFaceVector() throws IOException {
        Visitor byVisitor = visit(7, "CANCELLED_BY_VISITOR", CUTOFF.minusDays(3));
        Visitor override = visit(8, "CANCELLED_OVERRIDE", CUTOFF.minusDays(3));
        byVisitor.setFaceEncoding("[0.1,0.2]");
        override.setFaceEncoding("[0.3,0.4]");
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(byVisitor, override));

        assertThat(engine.purgeExpiredFiles(TODAY, uploads)).isEqualTo(2);

        for (Visitor v : List.of(byVisitor, override)) {
            assertThat(v.getIdImageUrl()).isNull();
            assertThat(v.getQrCodeUrl()).isNull();
            assertThat(v.getFaceEncoding()).isNull();
        }
        try (var left = Files.list(uploads)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void aGateDenialLosesItsPhotosAndFaceVectorAfter30DaysLikeAnyFinishedVisit() throws IOException {
        // Decided 2026-10-18: no indefinite security record. Someone who must stay out is banned (own face copy).
        Visitor denied = visit(9, "DENIED_ENTRY", CUTOFF.minusDays(1));
        denied.setFaceEncoding("[0.5,0.6]");
        String photo = denied.getIdImageUrl();
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(denied));

        assertThat(engine.purgeExpiredFiles(TODAY, uploads)).isEqualTo(1);

        assertThat(denied.getIdImageUrl()).isNull();
        assertThat(denied.getQrCodeUrl()).isNull();
        assertThat(denied.getFaceEncoding()).isNull();
        assertThat(uploads.resolve(photo)).doesNotExist();
        verify(repo).save(denied);
    }

    @Test
    void deletesTheFilesAndClearsTheFieldsButKeepsTheRow() throws IOException {
        Visitor checkedOut = visit(1, "CHECKED-OUT", CUTOFF.minusDays(1));
        Visitor noShow = visit(2, "NO_SHOW", CUTOFF.minusDays(90));
        Visitor cancelled = visit(3, "CANCELLED", CUTOFF.minusDays(5));
        Visitor rejected = visit(4, "REJECTED", CUTOFF.minusDays(400));
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(checkedOut, noShow, cancelled, rejected));

        int purged = engine.purgeExpiredFiles(TODAY, uploads);

        assertThat(purged).isEqualTo(4);
        for (Visitor v : List.of(checkedOut, noShow, cancelled, rejected)) {
            assertThat(v.getIdImageUrl()).isNull();
            assertThat(v.getQrCodeUrl()).isNull();
            assertThat(v.getName()).isEqualTo("REDACTED"); // personal details anonymized (R.A. 10173)
            verify(repo).save(v);
        }
        try (var left = Files.list(uploads)) {
            assertThat(left).isEmpty();
        }
        verify(repo, never()).delete(any());
        verify(repo, never()).deleteById(any());
        verifyNoInteractions(emailService);
    }

    @Test
    void theIdCloseUpAndTheFaceVectorGoWithTheSelfie() throws IOException {
        Visitor v = visit(5, "CHECKED-OUT", CUTOFF.minusDays(1));
        v.setIdCardImageUrl("card-5.jpg");
        Files.writeString(uploads.resolve("card-5.jpg"), "id close-up");
        v.setFaceEncoding("[0.1,0.2]");
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(v));

        assertThat(engine.purgeExpiredFiles(TODAY, uploads)).isEqualTo(1);

        assertThat(v.getIdCardImageUrl()).isNull();
        assertThat(v.getFaceEncoding()).isNull();
        assertThat(uploads.resolve("card-5.jpg")).doesNotExist();
        verify(repo).save(v);
    }

    @Test
    void aVisitWithOnlyAFaceVectorLeftIsStillCleared() {
        Visitor v = new Visitor();
        v.setId(6L);
        v.setStatus("NO_SHOW");
        v.setVisitDate(CUTOFF.minusDays(2));
        v.setFaceEncoding("[0.1,0.2]");
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(v));

        assertThat(engine.purgeExpiredFiles(TODAY, uploads)).isEqualTo(1);
        assertThat(v.getFaceEncoding()).isNull();
    }

    @Test
    void recentOrStillActiveVisitsAreNeverTouchedEvenIfTheQueryReturnedThem() throws IOException {
        Visitor exactly30Days = visit(1, "CHECKED-OUT", CUTOFF);               // not older than 30 days yet
        Visitor approvedOld = visit(2, "APPROVED", CUTOFF.minusDays(10));        // live pass
        Visitor pendingOld = visit(3, "PENDING", CUTOFF.minusDays(10));
        Visitor deniedOld = visit(4, "CHECKED-IN", CUTOFF.minusDays(10));        // still inside: not finished
        Visitor noDate = visit(5, "REJECTED", null);
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(exactly30Days, approvedOld, pendingOld, deniedOld, noDate));

        assertThat(engine.purgeExpiredFiles(TODAY, uploads)).isZero();

        verify(repo, never()).save(any());
        try (var left = Files.list(uploads)) {
            assertThat(left).hasSize(10);
        }
    }

    @Test
    void alreadyMissingFilesStillGetTheirFieldsCleared() throws IOException {
        Visitor v = visit(1, "REJECTED", CUTOFF.minusDays(2));
        Files.delete(uploads.resolve(v.getIdImageUrl()));
        v.setQrCodeUrl(null); // never approved, so it never had a QR
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(v));

        assertThat(engine.purgeExpiredFiles(TODAY, uploads)).isEqualTo(1);
        assertThat(v.getIdImageUrl()).isNull();
        verify(repo).save(v);
    }

    @Test
    void aTamperedPathNeverDeletesAnythingOutsideTheUploadsFolder(@TempDir Path elsewhere) throws IOException {
        Path outside = Files.writeString(elsewhere.resolve("secret.txt"), "keep me");
        Files.createDirectories(uploads.resolve("exhibits"));
        Path exhibit = Files.writeString(uploads.resolve("exhibits").resolve("plane.jpg"), "public photo");
        Visitor v = visit(1, "CHECKED-OUT", CUTOFF.minusDays(2));
        v.setIdImageUrl(uploads.relativize(outside).toString());   // ../.../secret.txt
        v.setQrCodeUrl("exhibits/plane.jpg");
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(v));

        engine.purgeExpiredFiles(TODAY, uploads);

        assertThat(outside).exists();
        assertThat(exhibit).exists();
        assertThat(v.getIdImageUrl()).isNull(); // the bad values are cleared from the row all the same
        assertThat(v.getQrCodeUrl()).isNull();
    }

    // --- GAP 3: anonymization of personal details ---

    @Test
    void thePurgeAnonymizesPersonalDetailsButKeepsTheAnalyticsFields() throws IOException {
        Visitor v = visit(11, "CHECKED-OUT", CUTOFF.minusDays(1));
        v.setFullName("Dela Cruz, Juan P.");
        v.setEmail("juan@example.com");
        v.setPhone("09171234567");
        v.setContactNumber("09171234567");
        v.setAddress("123 Rizal St, Pasay City");
        v.setSpecialConsideration("Wheelchair user");
        v.setEventJustification("Field trip led by Ms. Santos");
        v.setCompanionNames("Santos, Maria\nReyes, Jose");
        v.setGroupSize(3);
        v.setSex("Male");
        v.setAge(21);
        v.setAffiliation("PATTS College");
        v.setPurpose("Educational Tour");
        v.setTimeWindow("Morning (08:00 AM - 12:00 PM)");
        v.setRating(5);
        v.setFeedback("Great tour");
        v.setReferenceId("AV-TEST-0011");
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(v));

        assertThat(engine.purgeExpiredFiles(TODAY, uploads)).isEqualTo(1);

        assertThat(List.of(v.getName(), v.getFullName(), v.getEmail(), v.getPhone(), v.getContactNumber(),
                v.getAddress(), v.getSpecialConsideration(), v.getEventJustification())).containsOnly("REDACTED");
        assertThat(v.getCompanionNames()).isNull();
        // Kept for the predictive model, reports and audit
        assertThat(v.getGroupSize()).isEqualTo(3);
        assertThat(v.getSex()).isEqualTo("Male");
        assertThat(v.getAge()).isEqualTo(21);
        assertThat(v.getAffiliation()).isEqualTo("PATTS College");
        assertThat(v.getPurpose()).isEqualTo("Educational Tour");
        assertThat(v.getTimeWindow()).isEqualTo("Morning (08:00 AM - 12:00 PM)");
        assertThat(v.getVisitDate()).isEqualTo(CUTOFF.minusDays(1));
        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
        assertThat(v.getRating()).isEqualTo(5);
        assertThat(v.getFeedback()).isEqualTo("Great tour");
        assertThat(v.getReferenceId()).isEqualTo("AV-TEST-0011");
        verify(repo).save(v);
        verify(repo, never()).delete(any());
    }

    @Test
    void aVisitPurgedBeforeAnonymizationExistedIsAnonymizedNow() {
        Visitor v = new Visitor(); // files and face vector already gone, details still there
        v.setId(12L);
        v.setStatus("NO_SHOW");
        v.setVisitDate(CUTOFF.minusDays(60));
        v.setFullName("Reyes, Ana");
        v.setEmail("ana@example.com");
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(v));

        assertThat(engine.purgeExpiredFiles(TODAY, uploads)).isEqualTo(1);
        assertThat(v.getFullName()).isEqualTo("REDACTED");
        assertThat(v.getEmail()).isEqualTo("REDACTED");
        verify(repo).save(v);
    }

    @Test
    void anAlreadyAnonymizedVisitWithNothingLeftIsNotSavedAgain() {
        Visitor v = new Visitor();
        v.setId(13L);
        v.setStatus("CHECKED-OUT");
        v.setVisitDate(CUTOFF.minusDays(60));
        v.setFullName("REDACTED");
        v.setName("REDACTED");
        v.setEmail("REDACTED");
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(v));

        assertThat(engine.purgeExpiredFiles(TODAY, uploads)).isZero();
        verify(repo, never()).save(any());
    }

    @Test
    void aGateDenialHasItsPersonalDetailsAnonymizedToo() throws IOException {
        Visitor denied = visit(14, "DENIED_ENTRY", CUTOFF.minusDays(400));
        denied.setFullName("Suspect, Juan");
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(denied));

        engine.purgeExpiredFiles(TODAY, uploads);
        assertThat(denied.getFullName()).isEqualTo("REDACTED");
        assertThat(denied.getName()).isEqualTo("REDACTED");
    }

    // --- GAP 1: unreviewed requests past their visit date expire ---

    @Test
    void unreviewedRequestsPastTheirDateBecomeRejectedWithAnExpiredReason() {
        Visitor pending = new Visitor();
        pending.setStatus("PENDING");
        pending.setVisitDate(TODAY.minusDays(1));
        Visitor manualReview = new Visitor();
        manualReview.setStatus("PENDING_MANUAL_REVIEW");
        manualReview.setVisitDate(TODAY.minusDays(40));
        Visitor verified = new Visitor();
        verified.setStatus("VERIFIED");
        verified.setVisitDate(TODAY.minusDays(2));
        when(repo.findByStatusInAndVisitDateBefore(Visitor.APPROVABLE_STATUSES, TODAY))
                .thenReturn(List.of(pending, manualReview, verified));

        assertThat(engine.expireUnreviewedBookings(TODAY)).isEqualTo(3);

        for (Visitor v : List.of(pending, manualReview, verified)) {
            assertThat(v.getStatus()).isEqualTo("REJECTED");
            assertThat(v.getRejectionReason()).isEqualTo(AeroVisitAutomationEngine.EXPIRED_UNREVIEWED_REASON);
            verify(repo).save(v);
        }
        verifyNoInteractions(emailService);
    }

    @Test
    void requestsForTodayOrLaterAndDecidedBookingsAreNeverExpiredEvenIfTheQueryReturnedThem() {
        Visitor today = new Visitor();
        today.setStatus("PENDING");
        today.setVisitDate(TODAY); // the visit day itself: the Director can still approve it
        Visitor approved = new Visitor();
        approved.setStatus("APPROVED");
        approved.setVisitDate(TODAY.minusDays(3));
        Visitor noDate = new Visitor();
        noDate.setStatus("PENDING");
        noDate.setVisitDate(null);
        when(repo.findByStatusInAndVisitDateBefore(any(), any())).thenReturn(List.of(today, approved, noDate));

        assertThat(engine.expireUnreviewedBookings(TODAY)).isZero();
        assertThat(today.getStatus()).isEqualTo("PENDING");
        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        verify(repo, never()).save(any());
    }

    @Test
    void anExpiredRequestIsPurgedAndAnonymized30DaysLater() throws IOException {
        Visitor v = visit(15, "PENDING", CUTOFF.minusDays(1));
        v.setQrCodeUrl(null); // never approved
        when(repo.findByStatusInAndVisitDateBefore(any(), any())).thenReturn(List.of(v));
        engine.expireUnreviewedBookings(TODAY);
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(v));

        assertThat(engine.purgeExpiredFiles(TODAY, uploads)).isEqualTo(1);
        assertThat(v.getIdImageUrl()).isNull();
        assertThat(v.getName()).isEqualTo("REDACTED");
    }

    // --- GAP 2: expired temporary bans are lifted ---

    private com.paf.aerovisit.model.BlacklistedFace ban(long id, LocalDate expiresAt, String mugshot) throws IOException {
        com.paf.aerovisit.model.BlacklistedFace b = new com.paf.aerovisit.model.BlacklistedFace();
        b.setId(id);
        b.setFaceEncoding("[0.1,0.2]");
        b.setExpiresAt(expiresAt);
        b.setImageUrl(mugshot);
        if (mugshot != null) Files.writeString(uploads.resolve(mugshot), "mugshot");
        return b;
    }

    @Test
    void anExpiredTemporaryBanIsDeletedWithItsMugshot() throws IOException {
        var faceBans = mock(com.paf.aerovisit.repository.BlacklistedFaceRepository.class);
        engine.faceBans = faceBans;
        var expired = ban(1, TODAY.minusDays(1), "ban-expired.jpg");
        when(faceBans.findAll()).thenReturn(List.of(expired));

        assertThat(engine.liftExpiredBans(TODAY, uploads)).isEqualTo(1);

        verify(faceBans).delete(expired); // the face vector goes with the record
        assertThat(uploads.resolve("ban-expired.jpg")).doesNotExist();
    }

    @Test
    void permanentAndStillRunningBansAreKeptThroughTheirLastDay() throws IOException {
        var faceBans = mock(com.paf.aerovisit.repository.BlacklistedFaceRepository.class);
        engine.faceBans = faceBans;
        var permanent = ban(1, null, "ban-permanent.jpg");
        var lastDayToday = ban(2, TODAY, "ban-lastday.jpg");
        var future = ban(3, TODAY.plusMonths(6), "ban-future.jpg");
        when(faceBans.findAll()).thenReturn(List.of(permanent, lastDayToday, future));

        assertThat(engine.liftExpiredBans(TODAY, uploads)).isZero();

        verify(faceBans, never()).delete(any());
        assertThat(uploads.resolve("ban-permanent.jpg")).exists();
        assertThat(uploads.resolve("ban-lastday.jpg")).exists();
        assertThat(uploads.resolve("ban-future.jpg")).exists();
    }

    @Test
    void anExpiredBanWhoseMugshotIsAlreadyMissingOrAbsentIsStillLifted() throws IOException {
        var faceBans = mock(com.paf.aerovisit.repository.BlacklistedFaceRepository.class);
        engine.faceBans = faceBans;
        var missingFile = ban(1, TODAY.minusDays(10), null);
        missingFile.setImageUrl("ban-gone.jpg"); // recorded, but not on disk
        var noMugshot = ban(2, TODAY.minusDays(10), null);
        when(faceBans.findAll()).thenReturn(List.of(missingFile, noMugshot));

        assertThat(engine.liftExpiredBans(TODAY, uploads)).isEqualTo(2);
        verify(faceBans).delete(missingFile);
        verify(faceBans).delete(noMugshot);
    }

    @Test
    void anExpiredBanWithATamperedMugshotPathNeverDeletesAnythingOutsideUploads(@TempDir Path elsewhere) throws IOException {
        var faceBans = mock(com.paf.aerovisit.repository.BlacklistedFaceRepository.class);
        engine.faceBans = faceBans;
        Path outside = Files.writeString(elsewhere.resolve("secret.txt"), "keep me");
        var tampered = ban(1, TODAY.minusDays(1), null);
        tampered.setImageUrl(uploads.relativize(outside).toString());
        when(faceBans.findAll()).thenReturn(List.of(tampered));

        engine.liftExpiredBans(TODAY, uploads);
        assertThat(outside).exists();
    }

    @Test
    void withoutTheBanRepositoryNothingIsLifted() {
        engine.faceBans = null;
        assertThat(engine.liftExpiredBans(TODAY, uploads)).isZero();
    }

    // --- Chatbot messages (chat_logs) follow the same 30 days ---

    @Test
    void chatMessagesOlderThanTheCutoffAreDeleted() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        engine.jdbc = jdbc;
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.of(2026, 9, 18, 2, 0);
        when(jdbc.update(any(String.class), any(Object[].class))).thenReturn(7);

        assertThat(engine.purgeOldChatLogs(cutoff)).isEqualTo(7);

        verify(jdbc).update("DELETE FROM chat_logs WHERE `timestamp` < ?", java.sql.Timestamp.valueOf(cutoff));
    }

    @Test
    void aMissingChatTableNeverBreaksTheNightlyPurge() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        engine.jdbc = jdbc;
        when(jdbc.update(any(String.class), any(Object[].class)))
                .thenThrow(new org.springframework.jdbc.BadSqlGrammarException("purge", "DELETE FROM chat_logs",
                        new java.sql.SQLSyntaxErrorException("Table 'aerovisit.chat_logs' doesn't exist")));

        assertThat(engine.purgeOldChatLogs(java.time.LocalDateTime.of(2026, 9, 18, 2, 0))).isZero();
    }

    @Test
    void withoutADatabaseConnectionNoChatIsTouched() {
        engine.jdbc = null;
        assertThat(engine.purgeOldChatLogs(java.time.LocalDateTime.of(2026, 9, 18, 2, 0))).isZero();
    }

    @Test
    void theNightlyJobAlsoPurgesChatsWithA30DayCutoff() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        engine.jdbc = jdbc;
        when(repo.findByStatusInAndVisitDateBefore(any(), any())).thenReturn(List.of());
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of());
        java.time.LocalDateTime before = java.time.LocalDateTime.now().minusDays(30);

        engine.executeDataRetentionPurge();

        var captured = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(jdbc).update(eq("DELETE FROM chat_logs WHERE `timestamp` < ?"), captured.capture());
        java.time.LocalDateTime cutoff = ((java.sql.Timestamp) captured.getValue()).toLocalDateTime();
        assertThat(cutoff).isBetween(before.minusSeconds(5), java.time.LocalDateTime.now().minusDays(30));
    }
}
