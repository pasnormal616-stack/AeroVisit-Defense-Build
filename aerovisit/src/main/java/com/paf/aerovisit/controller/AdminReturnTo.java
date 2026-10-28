package com.paf.aerovisit.controller;

import java.util.regex.Pattern;

// ↩️ Where an admin or guard action (Approve, Reject, Ban, Delete, Deny Entry, manual check-in/out) sends the officer
// afterwards: back to the view they acted from, so the page doesn't jump to Today's Manifest after every click. The
// Pending tab's forms carry a hidden "returnTo" built by AdminDashboardController; on every other page shell.js adds the
// current address to the form when it is submitted. Anything else, or nothing, goes to /admin exactly as before.
// Only our own two dashboards with their known parameters are accepted, each value letters/digits/dashes only, so the
// field can never redirect off the site or to another page (no "//", no scheme, no line breaks, no free text).
final class AdminReturnTo {

    private static final String VALUE = "=[A-Za-z0-9-]{1,10}";
    private static final Pattern ADMIN_VIEW = view("/admin", "filter|sort|dateFilter|capacityDate|range");
    private static final Pattern GUARD_VIEW = view("/guard/dashboard", "tab|date");

    private AdminReturnTo() {}

    static String redirect(String returnTo) {
        return isOurs(returnTo) ? "redirect:" + returnTo : "redirect:/admin";
    }

    static boolean isOurs(String returnTo) {
        return returnTo != null && (ADMIN_VIEW.matcher(returnTo).matches() || GUARD_VIEW.matcher(returnTo).matches());
    }

    // path, optionally followed by ?name=value&name=value... with only the listed names
    private static Pattern view(String path, String names) {
        String param = "(" + names + ")" + VALUE;
        return Pattern.compile("^" + Pattern.quote(path) + "(\\?" + param + "(&" + param + ")*)?$");
    }
}
