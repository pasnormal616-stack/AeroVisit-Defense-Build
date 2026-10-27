package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.Visitor;

/** 🪪 IdentityBans: the email / phone / name ban checks that work without the AI service. Plain unit tests. */
class IdentityBansTest {

    private static final LocalDate TODAY = LocalDate.now();

    private static BlacklistedFace ban(String name, String email, String phone) {
        BlacklistedFace b = new BlacklistedFace();
        b.setName(name);
        b.setEmail(email);
        b.setPhone(phone);
        return b;
    }

    // --- Normalising ---

    @Test
    void emailsAreTrimmedAndLowerCased() {
        assertThat(IdentityBans.email("  Juan.DelaCruz@Example.COM ")).isEqualTo("juan.delacruz@example.com");
        assertThat(IdentityBans.email("   ")).isNull();
        assertThat(IdentityBans.email("not-an-address")).isNull();
        assertThat(IdentityBans.email(null)).isNull();
    }

    @Test
    void philippineMobileNumbersInAnyFormatBecomeTheLocalForm() {
        assertThat(IdentityBans.phone("0917-123-4567")).isEqualTo("09171234567");
        assertThat(IdentityBans.phone("+63 917 123 4567")).isEqualTo("09171234567");
        assertThat(IdentityBans.phone("639171234567")).isEqualTo("09171234567");
        assertThat(IdentityBans.phone("9171234567")).isEqualTo("09171234567");
        assertThat(IdentityBans.phone("(02) 8123-4567")).isEqualTo("0281234567");
        assertThat(IdentityBans.phone("123")).isNull();
        assertThat(IdentityBans.phone(null)).isNull();
    }

    // --- Email / phone: refuse ---

    @Test
    void theSameEmailOrPhoneMatchesAnActiveBan() {
        BlacklistedFace b = ban("Juan Dela Cruz", "juan@example.com", "09171234567");
        assertThat(IdentityBans.contactBan("JUAN@example.com", null, List.of(b), TODAY)).isSameAs(b);
        assertThat(IdentityBans.contactBan("other@example.com", "+63 917 123 4567", List.of(b), TODAY)).isSameAs(b);
        assertThat(IdentityBans.contactBan("other@example.com", "09998887777", List.of(b), TODAY)).isNull();
        assertThat(IdentityBans.contactBan(null, null, List.of(b), TODAY)).isNull();
    }

    @Test
    void anExpiredBanIsIgnoredButOneOnItsLastDayStillCounts() {
        BlacklistedFace ended = ban(null, "juan@example.com", null);
        ended.setExpiresAt(TODAY.minusDays(1));
        BlacklistedFace lastDay = ban(null, "ana@example.com", null);
        lastDay.setExpiresAt(TODAY);
        assertThat(IdentityBans.contactBan("juan@example.com", null, List.of(ended), TODAY)).isNull();
        assertThat(IdentityBans.contactBan("ana@example.com", null, List.of(lastDay), TODAY)).isSameAs(lastDay);
    }

    @Test
    void aFaceBanWithoutContactDetailsNeverMatchesByEmail() {
        BlacklistedFace faceOnly = new BlacklistedFace(); // made before the email / phone columns existed
        faceOnly.setFaceEncoding(FaceEncodings.format(new double[FaceEncodings.DIMENSIONS]));
        assertThat(IdentityBans.contactBan("juan@example.com", "09171234567", List.of(faceOnly), TODAY)).isNull();
    }

    @Test
    void aBookingIsBannedByItsContactNumberOrItsLegacyPhoneField() {
        BlacklistedFace b = ban(null, null, "09171234567");
        Visitor viaContact = new Visitor();
        viaContact.setContactNumber("0917 123 4567");
        Visitor viaLegacyPhone = new Visitor();
        viaLegacyPhone.setPhone("+639171234567");
        assertThat(IdentityBans.activeBan(viaContact, List.of(b), TODAY)).isSameAs(b);
        assertThat(IdentityBans.activeBan(viaLegacyPhone, List.of(b), TODAY)).isSameAs(b);
    }

    @Test
    void aBookingWithTheBannedFaceIsBannedWhateverItsEmail() {
        double[] face = new double[FaceEncodings.DIMENSIONS];
        face[3] = 1;
        BlacklistedFace b = new BlacklistedFace();
        b.setFaceEncoding(FaceEncodings.format(face));
        Visitor v = new Visitor();
        v.setEmail("brand.new@example.com");
        v.setFaceEncoding(FaceEncodings.format(face));
        assertThat(IdentityBans.activeBan(v, List.of(b), TODAY)).isSameAs(b);
    }

    // --- Name: flag only ---

    @Test
    void namesInEitherOrderAndWithMiddleInitialsMatchTheBannedName() {
        BlacklistedFace b = ban("Juan Dela Cruz", null, null);
        assertThat(IdentityBans.nameBan(List.of("Dela Cruz, Juan P."), List.of(b), TODAY)).isSameAs(b);
        assertThat(IdentityBans.nameBan(List.of("Juan P. Dela Cruz"), List.of(b), TODAY)).isSameAs(b);
        assertThat(IdentityBans.nameBan(List.of("JUAN DELACRUZ"), List.of(b), TODAY)).isSameAs(b);
        assertThat(IdentityBans.nameBan(List.of("Juan Dela Kruz"), List.of(b), TODAY)).isSameAs(b); // one letter off
    }

    @Test
    void otherNamesAndOneWordNamesDoNotMatch() {
        BlacklistedFace b = ban("Juan Dela Cruz", null, null);
        assertThat(IdentityBans.nameBan(List.of("Maria Santos"), List.of(b), TODAY)).isNull();
        assertThat(IdentityBans.nameBan(List.of("Juan"), List.of(b), TODAY)).isNull();
        assertThat(IdentityBans.nameBan(List.of("Pedro Dela Cruz"), List.of(b), TODAY)).isNull();
    }

    @Test
    void aBanWithoutANameOrAnExpiredBanNeverFlags() {
        BlacklistedFace noName = ban(null, "x@example.com", null);
        BlacklistedFace ended = ban("Juan Dela Cruz", null, null);
        ended.setExpiresAt(TODAY.minusDays(1));
        assertThat(IdentityBans.nameBan(List.of("Juan Dela Cruz"), List.of(noName, ended), TODAY)).isNull();
    }

    @Test
    void theLeadBookerAndEveryCompanionAreChecked() {
        Visitor v = new Visitor();
        v.setFullName("Reyes, Maria");
        v.setCompanionNames("Santos, Pedro\nAna Lim");
        assertThat(IdentityBans.namesOf(v)).containsExactly("Reyes, Maria", "Santos, Pedro", "Ana Lim");
    }

    // --- Banned Visitors page ---

    @Test
    void contactDetailsAreMaskedForTheGallery() {
        BlacklistedFace b = ban(null, "juan.delacruz@example.com", "09171234567");
        assertThat(b.maskedEmail()).isEqualTo("ju***@example.com");
        assertThat(b.maskedPhone()).isEqualTo("0917***4567");
        assertThat(new BlacklistedFace().maskedEmail()).isNull();
        assertThat(new BlacklistedFace().maskedPhone()).isNull();
    }
}
