package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.Visitor;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

// 🚶 PROCESS PANEL DATA: what the walk-in Process panel (fragments/process-panel.html + /js/process-panel.js) shows.
// The Guard Dashboard writes the same values as data-px-* attributes on each row's Process button (guard.html); the
// scanner gets this map in its walk-in scan answer, so a scanned walk-in opens the very same panel over the camera.
// WalkInPanelOverlayTest checks that both say the same thing for the same walk-in.
final class WalkInPanelData {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

    private WalkInPanelData() {}

    // waiting (no receipt yet), proof (receipt uploaded), cleared (paid or free), inside, closed: as guard.html's pxState
    static String state(Visitor v) {
        if ("CHECKED-IN".equals(v.getStatus())) return "inside";
        if (!"APPROVED".equals(v.getStatus())) return "closed";
        if (v.paymentCleared()) return "cleared";
        return Visitor.PAY_PROOF_SUBMITTED.equals(v.getPaymentStatus()) ? "proof" : "waiting";
    }

    // ban = the ban this walk-in's name resembles (or null); receiptVisible = PaymentAdminController.canViewReceipt
    static Map<String, String> of(Visitor v, BlacklistedFace ban, boolean receiptVisible) {
        String state = state(v);
        Map<String, String> px = new LinkedHashMap<>();
        px.put("state", state);
        px.put("name", text(v.getName()));
        px.put("ref", text(v.getReferenceId()));
        px.put("window", text(v.getTimeWindow()));
        px.put("group", v.admissionCounted() ? v.admissionSummary() : v.getGroupSize() + " pax");
        px.put("total", text(v.amountDueText()));
        px.put("billing", text(v.getBillingNumber()));
        px.put("payment", v.paymentRequired() ? v.paymentStatusLabel() : "Free: no payment needed");
        px.put("verified", text(v.paymentNote()));
        px.put("due", v.getPaymentDueAt() == null ? "" : v.getPaymentDueAt().toString());
        px.put("due-text", v.getPaymentDueAt() == null ? "" : v.getPaymentDueAt().format(CLOCK));
        px.put("inside", v.getCheckInTime() == null ? "" : v.getCheckInTime().format(CLOCK));
        px.put("receipt", receiptVisible && v.getReceiptImageUrl() != null ? "/admin/payments/" + v.getId() + "/receipt" : "");
        px.put("photo", v.gatePhoto() != null ? "/uploads/" + v.gatePhoto() : "");
        px.put("photo-label", text(v.gatePhotoLabel()));
        px.put("ban", v.getPossibleBanMatch() == null ? ""
                : ban != null ? "Ban #" + ban.getId() + ": " + ban.getName() : v.getPossibleBanMatch());
        px.put("ban-photo", ban != null && ban.getImageUrl() != null ? "/uploads/" + ban.getImageUrl() : "");
        px.put("needs-match", String.valueOf("proof".equals(state)));
        px.put("needs-ban", String.valueOf(v.banCheckPending()));
        px.put("ids-text", text(v.eligibilityCheckText()));
        px.put("process-url", "/admin/walk-ins/" + v.getId() + "/process");
        px.put("reject-url", "/admin/payments/" + v.getId() + "/reject");
        px.put("ban-url", "/admin/biometric-ban/" + v.getId());
        px.put("id", String.valueOf(v.getId()));
        return px;
    }

    private static String text(String s) {
        return s == null ? "" : s;
    }
}
