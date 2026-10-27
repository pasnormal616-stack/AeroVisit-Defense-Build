package com.paf.aerovisit.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.Visitor;

// 🪪 IDENTITY BANS: the ban checks that need no AI service, so banning keeps working when AI features are switched off
// (Settings) and no face vector exists.
//   - Same email or phone number as an active ban = the same person: refused, like a face match.
//   - A name that resembles a banned name (FuzzyNameMatcher, the OCR tolerance) is NOT proof: different people share
//     names, so it only flags the booking for staff (human in the loop).
// A new email and phone number get past the first check; the name flag, the face check (AI on) and the guards' ID check
// at the gate are the backstops.
public final class IdentityBans {

    private IdentityBans() {}

    // Trimmed, lower case; null when blank or not an address
    public static String email(String raw) {
        if (raw == null) return null;
        String e = raw.trim().toLowerCase(Locale.ROOT);
        return e.isEmpty() || !e.contains("@") ? null : e;
    }

    // Digits only, Philippine mobile numbers in the local form: +63 917 123 4567, 639171234567 and 9171234567 all
    // become 09171234567. Null when there are fewer than 7 digits (nothing reliable to match on).
    public static String phone(String raw) {
        if (raw == null) return null;
        String d = raw.replaceAll("\\D", "");
        if (d.length() == 12 && d.startsWith("63")) d = "0" + d.substring(2);
        else if (d.length() == 10 && d.startsWith("9")) d = "0" + d;
        return d.length() < 7 ? null : d;
    }

    // The booking's phone number: the form's contact number, else the legacy phone field
    public static String phoneOf(Visitor v) {
        String contact = phone(v.getContactNumber());
        return contact != null ? contact : phone(v.getPhone());
    }

    // The active ban with this email or phone number, or null
    public static BlacklistedFace contactBan(String rawEmail, String rawPhone, Iterable<BlacklistedFace> bans, LocalDate day) {
        if (bans == null) return null;
        String email = email(rawEmail);
        String phone = phone(rawPhone);
        if (email == null && phone == null) return null;
        for (BlacklistedFace ban : bans) {
            if (!ban.activeOn(day)) continue;
            if (email != null && email.equals(email(ban.getEmail()))) return ban;
            if (phone != null && phone.equals(phone(ban.getPhone()))) return ban;
        }
        return null;
    }

    public static BlacklistedFace contactBan(Visitor v, Iterable<BlacklistedFace> bans, LocalDate day) {
        return v == null ? null : contactBan(v.getEmail(), phoneOf(v), bans, day);
    }

    // What refuses a booking at approval and at the gate: its face (when it has one) or its email / phone number
    public static BlacklistedFace activeBan(Visitor v, Iterable<BlacklistedFace> bans, LocalDate day) {
        if (v == null || bans == null) return null;
        BlacklistedFace byFace = FaceEncodings.activeBan(FaceEncodings.parse(v.getFaceEncoding()), bans, day);
        return byFace != null ? byFace : contactBan(v, bans, day);
    }

    // The first active ban whose recorded name matches one of these names (the lead booker, then each companion), or
    // null. Only for flagging: never refuse on a name alone.
    public static BlacklistedFace nameBan(List<String> names, Iterable<BlacklistedFace> bans, LocalDate day) {
        if (names == null || bans == null) return null;
        for (BlacklistedFace ban : bans) {
            if (!ban.activeOn(day) || ban.getName() == null || ban.getName().isBlank()) continue;
            for (String name : names) {
                if (sameName(ban.getName(), name)) return ban;
            }
        }
        return null;
    }

    // The lead booker's name and every companion's, for nameBan
    public static List<String> namesOf(Visitor v) {
        List<String> names = new ArrayList<>();
        if (v == null) return names;
        String lead = v.getFullName() != null && !v.getFullName().isBlank() ? v.getFullName() : v.getName();
        if (lead != null && !lead.isBlank()) names.add(lead);
        names.addAll(Visitor.parseCompanionNames(v.getCompanionNames()));
        return names;
    }

    // Both the first and the last name of `name` appear in the banned name, within FuzzyNameMatcher's tolerance.
    // "Dela Cruz, Juan P." and "Juan P. Dela Cruz" both match a ban on "Juan Dela Cruz"; a one-word name never matches.
    static boolean sameName(String bannedName, String name) {
        String[] parts = firstAndLast(name);
        return parts != null && FuzzyNameMatcher.matches(bannedName, parts[0], parts[1]);
    }

    // {first, last} the same way Visitor.getFirstName / getLastName read a name: "Last, First MI" or "First ... Last".
    // Null for a blank or one-word name.
    static String[] firstAndLast(String name) {
        if (name == null || name.isBlank()) return null;
        String n = name.trim();
        if (n.contains(",")) {
            String[] commaParts = n.split(",", 2);
            String last = commaParts[0].trim();
            String[] rest = commaParts[1].trim().split("\\s+");
            String first = rest.length > 0 ? rest[0] : "";
            return first.isEmpty() || last.isEmpty() ? null : new String[] {first, last};
        }
        String[] words = n.split("\\s+");
        return words.length < 2 ? null : new String[] {words[0], words[words.length - 1]};
    }
}
