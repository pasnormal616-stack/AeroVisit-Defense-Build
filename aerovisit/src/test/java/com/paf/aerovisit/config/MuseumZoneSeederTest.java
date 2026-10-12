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

import com.paf.aerovisit.model.MuseumZone;
import com.paf.aerovisit.repository.MuseumZoneRepository;

class MuseumZoneSeederTest {

    @Test
    @SuppressWarnings("unchecked")
    void seedsTheFiveDefaultZonesOnFirstStartOnly() {
        MuseumZoneRepository repo = mock(MuseumZoneRepository.class);
        when(repo.count()).thenReturn(0L);
        new MuseumZoneSeeder(repo).run(null);

        ArgumentCaptor<Iterable<MuseumZone>> saved = ArgumentCaptor.forClass(Iterable.class);
        verify(repo).saveAll(saved.capture());
        List<MuseumZone> zones = (List<MuseumZone>) saved.getValue();
        assertThat(zones).extracting(MuseumZone::getName).containsExactly(
            "General Experience", "Indoor Main Gallery", "Aircraft Park", "Science Discovery Area", "Restrooms/Facilities");
        assertThat(zones).allMatch(MuseumZone::isActive);

        MuseumZoneRepository seeded = mock(MuseumZoneRepository.class);
        when(seeded.count()).thenReturn(5L);
        new MuseumZoneSeeder(seeded).run(null);
        verify(seeded, never()).saveAll(any());
    }
}
