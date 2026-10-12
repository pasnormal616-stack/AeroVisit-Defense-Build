package com.paf.aerovisit.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ReferenceIdGeneratorTest {

    @Test
    void idsHaveTheFormatVisitorsAreToldAbout() {
        for (int i = 0; i < 1000; i++) {
            // PAF- plus 6 characters, with look-alikes 0, O, 1 and I left out
            assertThat(ReferenceIdGenerator.generate()).matches("PAF-[A-HJ-NP-Z2-9]{6}");
        }
    }

    @Test
    void idsAreNotRepeatedInPractice() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(ReferenceIdGenerator.generate());
        }
        assertThat(seen).hasSizeGreaterThan(995);
    }
}
