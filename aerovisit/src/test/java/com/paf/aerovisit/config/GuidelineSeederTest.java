package com.paf.aerovisit.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.Guideline;
import com.paf.aerovisit.repository.GuidelineRepository;

/** First start: the Visitor Guidelines tab gets the two original rules; later starts leave the Director's list alone. */
class GuidelineSeederTest {

    @Test
    void seedsTheOriginalRulesWhenTheTableIsEmpty() {
        GuidelineRepository repo = mock(GuidelineRepository.class);
        when(repo.count()).thenReturn(0L);
        new GuidelineSeeder(repo).run(null);
        verify(repo).saveAll(org.mockito.ArgumentMatchers.<Iterable<Guideline>>argThat(list -> {
            var titles = new java.util.ArrayList<String>();
            list.forEach(g -> titles.add(g.getTitle()));
            return titles.equals(java.util.List.of("Identification", "Dress Code"));
        }));
    }

    @Test
    void leavesExistingGuidelinesAlone() {
        GuidelineRepository repo = mock(GuidelineRepository.class);
        when(repo.count()).thenReturn(3L);
        new GuidelineSeeder(repo).run(null);
        verify(repo, never()).saveAll(any());
    }
}
