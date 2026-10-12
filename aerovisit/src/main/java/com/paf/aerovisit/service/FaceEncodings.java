package com.paf.aerovisit.service;

import org.json.JSONArray;

import com.paf.aerovisit.model.BlacklistedFace;

// 🧬 Face vectors from the AI service (OpenCV SFace): 128 numbers, stored as a JSON array string.
// Two faces are the same person when their cosine similarity is at least SAME_PERSON_COSINE, the threshold OpenCV
// publishes for SFace (kyc.py uses the same number for the ID-vs-selfie match).
public final class FaceEncodings {

    public static final int DIMENSIONS = 128;
    public static final double SAME_PERSON_COSINE = 0.363;

    private FaceEncodings() {}

    // The vector, or null when the text is missing, not a JSON array, the wrong length, or holds a non-number
    public static double[] parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JSONArray array = new JSONArray(json);
            return fromArray(array);
        } catch (Exception e) {
            return null;
        }
    }

    // Same checks for the array inside the AI service's answer
    public static double[] fromArray(JSONArray array) {
        if (array == null || array.length() != DIMENSIONS) return null;
        double[] vector = new double[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            Object value = array.opt(i);
            if (!(value instanceof Number)) return null;
            vector[i] = ((Number) value).doubleValue();
            if (!Double.isFinite(vector[i])) return null;
        }
        return vector;
    }

    public static String format(double[] vector) {
        return new JSONArray(vector).toString();
    }

    // Cosine similarity, -1..1; 0 for a zero-length vector
    public static double cosine(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return normA == 0 || normB == 0 ? 0 : dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public static boolean samePerson(double[] a, double[] b) {
        return a != null && b != null && cosine(a, b) >= SAME_PERSON_COSINE;
    }

    // True when the face matches a ban in force today (expired temporary bans and unreadable rows are skipped).
    // Every check goes through here: booking (KycService), approval and check-in (GuardOperationController).
    public static boolean isBanned(double[] face, Iterable<BlacklistedFace> bans) {
        return activeBan(face, bans, java.time.LocalDate.now()) != null;
    }

    // The ban in force on that day that matches the face, or null
    public static BlacklistedFace activeBan(double[] face, Iterable<BlacklistedFace> bans, java.time.LocalDate day) {
        if (face == null || bans == null) return null;
        for (BlacklistedFace ban : bans) {
            if (ban.activeOn(day) && samePerson(face, parse(ban.getFaceEncoding()))) return ban;
        }
        return null;
    }
}
