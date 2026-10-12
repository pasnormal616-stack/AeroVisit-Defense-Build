package com.paf.aerovisit.util;

import java.security.SecureRandom;

public final class ReferenceIdGenerator {

    // Leaves out look-alike characters (0/O, 1/I) so visitors can read the code back without mistakes
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 6;
    private static final String PREFIX = "PAF-";

    private static final SecureRandom RANDOM = new SecureRandom();

    private ReferenceIdGenerator() {}

    // Produces a Booking Reference ID such as PAF-A7X92K
    public static String generate() {
        StringBuilder sb = new StringBuilder(PREFIX);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
