package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;

/** The nightly retention purge never touches the News photos (uploads/exhibits/), even when it clears visits. Temp folder. */
class NewsRetentionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 6);

    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final AeroVisitAutomationEngine engine = new AeroVisitAutomationEngine(repo, mock(EmailService.class));

    @TempDir
    Path uploads;

    @Test
    void newsPhotosSurviveThePurge() throws IOException {
        Path exhibits = Files.createDirectories(uploads.resolve("exhibits"));
        Path newsPhoto = Files.writeString(exhibits.resolve("0b6f7c1e-news.jpg"), "news photo");

        Visitor old = new Visitor();
        old.setId(1L);
        old.setName("Old visit");
        old.setStatus("CHECKED-OUT");
        old.setVisitDate(TODAY.minusDays(60));
        old.setIdImageUrl("photo-1.jpg");
        Files.writeString(uploads.resolve("photo-1.jpg"), "id photo");
        // A tampered value pointing at a News photo is never followed either
        Visitor tampered = new Visitor();
        tampered.setId(2L);
        tampered.setName("Tampered");
        tampered.setStatus("CHECKED-OUT");
        tampered.setVisitDate(TODAY.minusDays(60));
        tampered.setQrCodeUrl("exhibits/0b6f7c1e-news.jpg");
        when(repo.findExpiredWithFiles(any(), any())).thenReturn(List.of(old, tampered));

        engine.purgeExpiredFiles(TODAY, uploads);

        assertThat(Files.exists(uploads.resolve("photo-1.jpg"))).isFalse(); // the visit's own file is purged
        assertThat(Files.exists(newsPhoto)).isTrue();                       // the News photo stays
    }
}
