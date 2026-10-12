package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.BlacklistedFace;

/** SFace vectors: parsing what the AI service and the database hold, and the cosine >= 0.363 same-person rule. */
class FaceEncodingsTest {

    private static double[] withCosine(double cosine) {
        double[] v = new double[FaceEncodings.DIMENSIONS];
        v[0] = cosine;
        v[1] = Math.sqrt(1 - cosine * cosine);
        return v;
    }

    private static final double[] REFERENCE = withCosine(1.0);

    @Test
    void roundTripsThroughText() {
        double[] v = withCosine(0.6);
        assertThat(FaceEncodings.parse(FaceEncodings.format(v))).containsExactly(v);
    }

    @Test
    void anythingButExactly128NumbersIsUnreadable() {
        assertThat(FaceEncodings.parse(null)).isNull();
        assertThat(FaceEncodings.parse("")).isNull();
        assertThat(FaceEncodings.parse("not json")).isNull();
        assertThat(FaceEncodings.parse("[1,2,3]")).isNull();
        String withText = FaceEncodings.format(REFERENCE).replaceFirst("1\\.0|1", "\"x\"");
        assertThat(FaceEncodings.parse(withText)).isNull();
    }

    @Test
    void theSamePersonThresholdIsCosine0_363() {
        assertThat(FaceEncodings.samePerson(REFERENCE, withCosine(0.364))).isTrue();
        assertThat(FaceEncodings.samePerson(REFERENCE, withCosine(0.362))).isFalse();
        assertThat(FaceEncodings.cosine(REFERENCE, withCosine(0.5))).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(FaceEncodings.samePerson(REFERENCE, null)).isFalse();
        assertThat(FaceEncodings.cosine(REFERENCE, new double[FaceEncodings.DIMENSIONS])).isZero();
    }

    @Test
    void isBannedSkipsUnreadableRows() {
        BlacklistedFace broken = new BlacklistedFace();
        broken.setFaceEncoding("garbage");
        BlacklistedFace other = new BlacklistedFace();
        other.setFaceEncoding(FaceEncodings.format(withCosine(0.1)));
        BlacklistedFace same = new BlacklistedFace();
        same.setFaceEncoding(FaceEncodings.format(withCosine(0.9)));

        assertThat(FaceEncodings.isBanned(REFERENCE, List.of(broken, other))).isFalse();
        assertThat(FaceEncodings.isBanned(REFERENCE, List.of(broken, other, same))).isTrue();
        assertThat(FaceEncodings.isBanned(null, List.of(same))).isFalse();
    }

    @Test
    void anExpiredTemporaryBanIsIgnoredButItsLastDayStillCounts() {
        java.time.LocalDate today = java.time.LocalDate.now();
        BlacklistedFace ban = new BlacklistedFace();
        ban.setFaceEncoding(FaceEncodings.format(withCosine(0.9)));

        ban.setExpiresAt(today.minusDays(1));
        assertThat(FaceEncodings.isBanned(REFERENCE, List.of(ban))).isFalse();
        assertThat(FaceEncodings.activeBan(REFERENCE, List.of(ban), today)).isNull();

        ban.setExpiresAt(today); // the lift happens the day AFTER the expiry date, like the old email block
        assertThat(FaceEncodings.isBanned(REFERENCE, List.of(ban))).isTrue();

        ban.setExpiresAt(null);  // permanent
        assertThat(FaceEncodings.activeBan(REFERENCE, List.of(ban), today.plusYears(50))).isSameAs(ban);
    }
}
