package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Fuzzy (Levenshtein) name match on Tesseract output: forgives OCR slips, never a different person. Pure Java. */
class FuzzyNameMatcherTest {

    private static final int BIG = 100;

    private static boolean match(String ocr, String first, String last) {
        return FuzzyNameMatcher.matches(ocr, first, last);
    }

    // Full-card OCR text, headers, address and numbers included: the noise a real ID close-up gives Tesseract.
    // A score-based matcher (FuzzySearch.tokenSetRatio) gave "JUAM" and "PEDRO" the same 78 on these, so they stay here.
    private static String philSysCard(String last, String given, String middle) {
        return "REPUBLIKA NG PILIPINAS\nRepublic of the Philippines\nPAMBANSANG PAGKAKAKILANLAN\n"
                + "Philippine Identification Card\n1234-5678-9101-1121\nApelyido/Last Name\n" + last
                + "\nMga Pangalan/Given Names\n" + given + "\nGitnang Apelyido/Middle Name\n" + middle
                + "\nPetsa ng Kapanganakan/Date of Birth\nJANUARY 01, 1990\nTirahan/Address\n"
                + "123 RIZAL ST, BRGY SAN ANTONIO, QUEZON CITY, METRO MANILA";
    }

    private static String driversLicense(String name) {
        return "REPUBLIC OF THE PHILIPPINES\nDEPARTMENT OF TRANSPORTATION\nLAND TRANSPORTATION OFFICE\n"
                + "DRIVER'S LICENSE\nLast Name. First Name. Middle Name\n" + name + "\nNationality Sex Date of Birth "
                + "Weight (kg) Height(m)\nPHL M 1990/01/01 70 1.70\nAddress\n123 RIZAL ST BRGY SAN ANTONIO QUEZON CITY\n"
                + "License No. N01-23-456789 Expiration Date 2030/01/01 Agency Code N01\nBlood Type O+ Eyes Color BLACK";
    }

    // --- the distance itself ---

    @Test
    void levenshteinCountsInsertionsDeletionsAndSubstitutions() {
        assertThat(FuzzyNameMatcher.levenshtein("KITTEN", "SITTING", BIG)).isEqualTo(3);
        assertThat(FuzzyNameMatcher.levenshtein("NICHOLAS", "N1CHOLAS", BIG)).isEqualTo(1); // substitution
        assertThat(FuzzyNameMatcher.levenshtein("NICHOLAS", "NICHOLA", BIG)).isEqualTo(1);  // deletion
        assertThat(FuzzyNameMatcher.levenshtein("NICHOLAS", "NICHOLLAS", BIG)).isEqualTo(1); // insertion
        assertThat(FuzzyNameMatcher.levenshtein("", "ABC", BIG)).isEqualTo(3);
        assertThat(FuzzyNameMatcher.levenshtein("SAME", "SAME", BIG)).isZero();
    }

    @Test
    void levenshteinStopsEarlyPastTheLimit() {
        assertThat(FuzzyNameMatcher.levenshtein("KITTEN", "SITTING", 1)).isEqualTo(2);       // "more than 1"
        assertThat(FuzzyNameMatcher.levenshtein("A", "ABCDEFGHIJ", 2)).isEqualTo(3);         // length gap alone
        assertThat(FuzzyNameMatcher.levenshtein("KITTEN", "SITTING", 3)).isEqualTo(3);       // exact when within
    }

    @Test
    void toleranceGrowsWithNameLength() {
        assertThat(FuzzyNameMatcher.allowedEdits(2)).isZero();
        assertThat(FuzzyNameMatcher.allowedEdits(3)).isZero();
        assertThat(FuzzyNameMatcher.allowedEdits(4)).isEqualTo(1);
        assertThat(FuzzyNameMatcher.allowedEdits(7)).isEqualTo(1);
        assertThat(FuzzyNameMatcher.allowedEdits(8)).isEqualTo(2);
    }

    // --- OCR slips that should now pass ---

    @Test
    void digitMisreadInsideANamePasses() {
        assertThat(match("REPUBLIC OF THE PHILIPPINES\nN1CHOLAS SANT0S", "Nicholas", "Santos")).isTrue();
    }

    @Test
    void twoSlipsInALongNamePass() {
        assertThat(match("CHR1STOPHFR REYES", "Christopher", "Reyes")).isTrue();
    }

    @Test
    void middleNamesAndIdLayoutDoNotMatter() {
        assertThat(match("Last Name: DELA CRUZ\nGiven Names: JUAN PABLO\nMiddle Name: SANTOS", "Juan", "Dela Cruz")).isTrue();
        assertThat(match("JUAN PABLO S. DELA CRUZ", "Juan", "Dela Cruz")).isTrue();
    }

    @Test
    void multiWordSurnamesMatchWithOrWithoutSpaces() {
        assertThat(match("DELACRUZ, JUAN", "Juan", "Dela Cruz")).isTrue();
        assertThat(match("DE LA CRUZ, JUAN", "Juan", "Dela Cruz")).isTrue();
        assertThat(match("DELA CRUZ, JUAN", "Juan", "Delacruz")).isTrue();
    }

    @Test
    void accentsAndCaseAreIgnored() {
        assertThat(match("PENA, JOSE", "José", "Peña")).isTrue();
        assertThat(match("peña, josé", "JOSE", "PENA")).isTrue();
    }

    @Test
    void oneLetterSlipsOnAFullIdCardPass() {
        assertThat(match(philSysCard("DELA CRUZ", "JUAN", "PEREZ"), "Juan", "Dela Cruz")).isTrue();
        assertThat(match(philSysCard("DELA CRUZ", "JUAM", "PEREZ"), "Juan", "Dela Cruz")).isTrue();
        assertThat(match(philSysCard("DELA CRUS", "JUAN", "PEREZ"), "Juan", "Dela Cruz")).isTrue();
        assertThat(match(philSysCard("DELACRUZ", "JUAN", "PEREZ"), "Juan", "Dela Cruz")).isTrue();
        assertThat(match(driversLicense("DELA CRUZ, JUAN PEREZ"), "Juan", "Dela Cruz")).isTrue();
        assertThat(match(driversLicense("DELA CRUZ, JUAM PEREZ"), "Juan", "Dela Cruz")).isTrue();
        assertThat(match(driversLicense("0ELA CRUZ, JUAM PEREZ"), "Juan", "Dela Cruz")).isTrue();
    }

    // --- things that must still go to manual review ---

    @Test
    void aDifferentPersonDoesNotPass() {
        assertThat(match("SANTOS, MARIA", "Juan", "Dela Cruz")).isFalse();
        assertThat(match("JUANITO DELA CRUZ", "Juan", "Dela Cruz")).isFalse();  // 3 edits from JUAN
        assertThat(match("DELA CRUZ, JUAN", "Juan", "Reyes")).isFalse();        // first name alone isn't enough
    }

    @Test
    void aDifferentPersonOnAFullIdCardDoesNotPass() {
        assertThat(match(philSysCard("DELA CRUZ", "PEDRO", "PEREZ"), "Juan", "Dela Cruz")).isFalse();   // same surname
        assertThat(match(philSysCard("DELA CRUZ", "JUANITO", "PEREZ"), "Juan", "Dela Cruz")).isFalse();
        assertThat(match(philSysCard("SANTOS", "JUAN", "REYES"), "Juan", "Dela Cruz")).isFalse();       // same first name
        assertThat(match(philSysCard("SANTOS", "MARIA", "REYES"), "Juan", "Dela Cruz")).isFalse();
        assertThat(match(driversLicense("DELA CRUZ, PEDRO PEREZ"), "Juan", "Dela Cruz")).isFalse();
    }

    @Test
    void tooManySlipsDoNotPass() {
        assertThat(match("N1CH0LAZ SANTOS", "Nicholas", "Santos")).isFalse();   // 3 edits on an 8-letter name
        assertThat(match("RAMOS, MAR1E", "Marie", "Ramos")).isTrue();           // 1 edit on 5 letters: ok
        assertThat(match("RAMOS, MAR1F", "Marie", "Ramos")).isFalse();          // 2 edits on 5 letters: too many
    }

    @Test
    void shortNamesMustBeExact() {
        assertThat(match("LI, WEI", "Wei", "Li")).isTrue();
        assertThat(match("LE, WEI", "Wei", "Li")).isFalse();
        assertThat(match("LI, WEN", "Wei", "Li")).isFalse();
    }

    @Test
    void oneOcrWordCannotStandInForBothNames() {
        assertThat(match("PEDRO MARCOS", "Marco", "Marcos")).isFalse();  // MARCO and MARCOS would both hit "MARCOS"
        assertThat(match("MARCO MARCOS", "Marco", "Marcos")).isTrue();
    }

    @Test
    void blankOrMissingInputNeverMatches() {
        assertThat(match(null, "Juan", "Dela Cruz")).isFalse();
        assertThat(match("", "Juan", "Dela Cruz")).isFalse();
        assertThat(match("DELA CRUZ, JUAN", "", "Dela Cruz")).isFalse();
        assertThat(match("DELA CRUZ, JUAN", "Juan", null)).isFalse();
        assertThat(match("DELA CRUZ, JUAN", " , ", "Dela Cruz")).isFalse();
    }

    @Test
    void aLongPageOfOcrNoiseIsStillFast() {
        String noise = "ZQXJ KWVP 1234 REPUBLIKA NG PILIPINAS ".repeat(500) + "DELA CRUZ, JUAN";
        long start = System.nanoTime();
        assertThat(match(noise, "Juan", "Dela Cruz")).isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(500);
    }
}
