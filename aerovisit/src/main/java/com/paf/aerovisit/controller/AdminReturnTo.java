package com.paf.aerovisit.controller;

import java.util.regex.Pattern;

// ↩️ Where Approve / Force Approve / Reject send the officer afterwards. The Pending tab's forms post a hidden "returnTo"
// (built by AdminDashboardController) so the officer stays on their filtered Pending view; anything else, or nothing,
// goes to /admin exactly as before. Only that one view with known parameters is accepted, so the field can never
// redirect off the site or to another page (no "//", no scheme, no free text).
final class AdminReturnTo {

    private static final Pattern PENDING_VIEW =
            Pattern.compile("^/admin\\?filter=pending(&(sort|dateFilter|capacityDate)=[A-Za-z0-9-]{1,10})*$");

    private AdminReturnTo() {}

    static String redirect(String returnTo) {
        return returnTo != null && PENDING_VIEW.matcher(returnTo).matches() ? "redirect:" + returnTo : "redirect:/admin";
    }
}
