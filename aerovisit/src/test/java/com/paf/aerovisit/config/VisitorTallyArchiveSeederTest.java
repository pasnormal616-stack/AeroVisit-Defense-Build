package com.paf.aerovisit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.VisitorTallyArchive;
import com.paf.aerovisit.repository.VisitorTallyArchiveRepository;

/** The museum's paper tally sheet (2022-2025) as stored for the Visitor Tally. No database. */
class VisitorTallyArchiveSeederTest {

    private static int total(List<VisitorTallyArchive> rows, int year) {
        return rows.stream().filter(r -> r.getYear() == year).mapToInt(VisitorTallyArchive::getVisitors).sum();
    }

    private static int month(List<VisitorTallyArchive> rows, int year, int month) {
        return rows.stream().filter(r -> r.getYear() == year && r.getMonth() == month).findFirst().orElseThrow().getVisitors();
    }

    @Test
    void everyMonthOf2022To2025HasARow() {
        assertThat(VisitorTallyArchiveSeeder.paperRecords()).hasSize(48);
    }

    @Test
    void yearTotalsMatchThePaperSheet() {
        List<VisitorTallyArchive> rows = VisitorTallyArchiveSeeder.paperRecords();
        assertThat(total(rows, 2022)).isEqualTo(467);
        assertThat(total(rows, 2023)).isEqualTo(1095);
        assertThat(total(rows, 2024)).isEqualTo(724);
        assertThat(total(rows, 2025)).isEqualTo(4538);
        assertThat(rows.stream().mapToInt(VisitorTallyArchive::getVisitors).sum()).isEqualTo(6824);
    }

    @Test
    void blankMonthsOnThePaperAreStoredAsClosedZero() {
        List<VisitorTallyArchive> rows = VisitorTallyArchiveSeeder.paperRecords();
        assertThat(month(rows, 2022, 1)).isZero();
        assertThat(month(rows, 2025, 6)).isZero();
        assertThat(month(rows, 2025, 5)).isEqualTo(2536);
    }

    @Test
    void seedsOnlyAnEmptyArchive() {
        VisitorTallyArchiveRepository repo = mock(VisitorTallyArchiveRepository.class);
        when(repo.count()).thenReturn(48L);
        new VisitorTallyArchiveSeeder(repo).run(null);
        verify(repo, never()).saveAll(anyList());

        when(repo.count()).thenReturn(0L);
        new VisitorTallyArchiveSeeder(repo).run(null);
        verify(repo).saveAll(anyList());
    }
}
