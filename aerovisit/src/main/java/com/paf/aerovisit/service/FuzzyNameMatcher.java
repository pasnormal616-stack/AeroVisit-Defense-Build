package com.paf.aerovisit.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 🔤 FUZZY NAME MATCH for OCR text (KycService). Tesseract misreads a letter or two ("N1CHOLAS"), so each name is
// compared with the words on the ID by Levenshtein distance instead of exact text. The allowed edits grow with the
// word's length so short names stay strict: 1-3 letters exact, 4-7 letters 1 edit, 8+ letters 2 edits.
// No library: a plain two-row Levenshtein that stops early once a word can no longer match.
final class FuzzyNameMatcher {

    private FuzzyNameMatcher() {}

    static int allowedEdits(int length) {
        return length <= 3 ? 0 : length <= 7 ? 1 : 2;
    }

    // True when the first AND last name both appear on the ID (within tolerance), on different words of it,
    // so one OCR word can't stand in for both ("MARCOS" alone doesn't approve "Marco Marcos")
    static boolean matches(String ocrText, String firstName, String lastName) {
        List<String> words = words(ocrText);
        List<String> first = words(firstName);
        List<String> last = words(lastName);
        if (words.isEmpty() || first.isEmpty() || last.isEmpty()) return false;

        List<int[]> lastSpans = spans(words, last);
        if (lastSpans.isEmpty()) return false;
        for (int[] f : spans(words, first)) {
            for (int[] l : lastSpans) {
                if (f[1] <= l[0] || l[1] <= f[0]) return true; // [start, end) ranges don't overlap
            }
        }
        return false;
    }

    // Uppercase, accents removed (PEÑA = PENA), split on anything that isn't a letter or digit.
    // Digits stay inside words because OCR turns letters into look-alike digits (N1CHOLAS, 0LIVER).
    static List<String> words(String text) {
        if (text == null) return List.of();
        String plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String w : plain.split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty()) out.add(w);
        }
        return out;
    }

    // [start, end) runs of OCR words that match the name. A name of k words is compared with every run of k-1..k+1
    // neighbouring OCR words, spaces removed, so "DELA CRUZ" also matches "DELACRUZ" and "DE LA CRUZ".
    // The edit budget is the sum of each name word's own allowance.
    private static List<int[]> spans(List<String> words, List<String> name) {
        String target = String.join("", name);
        int budget = 0;
        for (String part : name) budget += allowedEdits(part.length());
        int minRun = Math.max(1, name.size() - 1);
        int maxRun = name.size() + 1;

        List<int[]> hits = new ArrayList<>();
        for (int start = 0; start < words.size(); start++) {
            StringBuilder run = new StringBuilder();
            for (int len = 1; len <= maxRun && start + len <= words.size(); len++) {
                run.append(words.get(start + len - 1));
                if (run.length() > target.length() + budget) break; // longer runs only get further away
                if (len >= minRun && levenshtein(run, target, budget) <= budget) {
                    hits.add(new int[] {start, start + len});
                }
            }
        }
        return hits;
    }

    // Edit distance (insertions, deletions, substitutions). Gives up as soon as the answer must exceed max and returns
    // max + 1, so scanning a whole ID's worth of OCR words stays cheap.
    static int levenshtein(CharSequence a, CharSequence b, int max) {
        if (Math.abs(a.length() - b.length()) > max) return max + 1;
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;

        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                curr[j] = Math.min(substitution, Math.min(prev[j] + 1, curr[j - 1] + 1));
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > max) return max + 1; // every path is already too expensive
            int[] swap = prev;
            prev = curr;
            curr = swap;
        }
        return Math.min(prev[b.length()], max + 1);
    }
}
