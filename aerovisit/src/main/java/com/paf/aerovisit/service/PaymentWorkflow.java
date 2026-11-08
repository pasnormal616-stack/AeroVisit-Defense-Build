package com.paf.aerovisit.service;

import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 💳 Payment rules for advance bookings. Visitors pay outside AeroVisit (Landbank Link.BizPortal) using a billing
 * number, then upload a receipt screenshot that the Director verifies. The amount is always the total stored on the
 * booking when it was made (Visitor.amountDueCentavos), never recalculated at today's prices.
 *
 * At approval (approve and Force Approve): a total of 0 needs no payment and gets its QR as before; otherwise the
 * booking keeps its seats and waits for payment (AWAITING_PAYMENT) until the deadline: the EARLIER of approval + the
 * Director's window (Settings, default 48 hours) and the start of the booked time window.
 */
@Service
public class PaymentWorkflow {

    // What approval does next for a booking
    public enum Next { ISSUE_QR, AWAIT_PAYMENT, AWAIT_VERIFICATION }

    // A rejected receipt gives the visitor at least this long to upload another (never past the start of the visit)
    public static final int REUPLOAD_HOURS = 24;
    public static final int MAX_WINDOW_HOURS = 720;

    public static final DateTimeFormatter DEADLINE = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy 'at' h:mm a", Locale.US);

    private final BillingNumberService billingNumbers;

    // Optional, so test slices without a database still start; without it the defaults apply
    @Autowired(required = false)
    PortalSettingsRepository settingsRepo;

    public PaymentWorkflow(BillingNumberService billingNumbers) {
        this.billingNumbers = billingNumbers;
    }

    // --- Settings (PortalSettings, Settings page) ---

    private PortalSettings settings() {
        if (settingsRepo == null) return new PortalSettings();
        try {
            return settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Payment settings could not be read; using the defaults. " + e.getMessage());
            return new PortalSettings();
        }
    }

    public int windowHours() { return settings().paymentWindow(); }

    // The Landbank payment page, or null when the Director cleared it (the button is hidden then)
    public String landbankUrl() {
        String url = settings().landbankLink();
        return url == null || url.isBlank() ? null : url;
    }

    // The museum contact email from Website Content, or null
    public String contactEmail() {
        String email = settings().getContactEmail();
        return email == null || email.isBlank() ? null : email;
    }

    // Saves the Director's payment settings (already validated)
    public void saveSettings(int windowHours, String landbankUrl) {
        PortalSettings s = settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
        s.setPaymentWindowHours(windowHours);
        s.setLandbankUrl(landbankUrl);
        settingsRepo.save(s);
    }

    // Null when the link is acceptable: empty (cleared) or an https:// address with a host, at most 300 characters
    public static String landbankUrlError(String url) {
        if (url == null || url.isBlank()) return null;
        if (url.length() > 300) return "The Landbank link is too long (max 300 characters).";
        try {
            java.net.URI uri = new java.net.URI(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
                return "The Landbank link must start with https:// and include the website address.";
            }
        } catch (java.net.URISyntaxException e) {
            return "The Landbank link must start with https:// and include the website address.";
        }
        return null;
    }

    // --- Approval ---

    // Sets the booking's payment state for an approval (the caller holds the seats, saves and emails). Never changes a
    // billing number that was already assigned.
    public Next onApproval(Visitor v, LocalDateTime now) {
        if (!v.paymentRequired()) {
            if (v.admissionCounted()) v.setPaymentStatus(Visitor.PAY_NOT_REQUIRED); // older bookings stay blank
            return Next.ISSUE_QR;
        }
        if (Visitor.PAY_PAID.equals(v.getPaymentStatus())) return Next.ISSUE_QR;            // re-approval after a reschedule
        if (Visitor.PAY_PROOF_SUBMITTED.equals(v.getPaymentStatus())) return Next.AWAIT_VERIFICATION;
        if (v.getBillingNumber() == null) v.setBillingNumber(billingNumbers.next());
        if (v.getPaymentToken() == null) v.setPaymentToken(UUID.randomUUID().toString());
        v.setPaymentStatus(Visitor.PAY_AWAITING);
        v.setPaymentRequestedAt(now);
        v.setPaymentDueAt(deadline(v, now, windowHours()));
        v.setPaymentRejectionReason(null);
        return Next.AWAIT_PAYMENT;
    }

    // ✅ Records a verified payment: who and when, with an optional note. Shared by Approve (pay first: approving a
    // booking with a submitted receipt also marks it paid) and Mark as Paid. The caller saves.
    public static void recordPaid(Visitor v, String verifiedBy, LocalDateTime at, String note) {
        v.setPaymentStatus(Visitor.PAY_PAID);
        v.setPaymentVerifiedBy(verifiedBy);
        v.setPaymentVerifiedAt(at);
        v.setPaymentVerificationNote(note == null || note.isBlank() ? null : note.trim());
    }

    // 💳 PAY FIRST: a paid advance booking waits for payment from the moment it is booked (its seats already held):
    // billing number, payment link, and the Director's payment window (default 48 hours), never past the visit start.
    // Never changes a billing number that was already assigned. The caller saves and emails.
    public void onAdvanceBooking(Visitor v, LocalDateTime now) {
        if (!v.paymentRequired()) {
            v.setPaymentStatus(Visitor.PAY_NOT_REQUIRED);
            return;
        }
        if (v.getBillingNumber() == null) v.setBillingNumber(billingNumbers.next());
        if (v.getPaymentToken() == null) v.setPaymentToken(UUID.randomUUID().toString());
        v.setPaymentStatus(Visitor.PAY_AWAITING);
        v.setPaymentRequestedAt(now);
        v.setPaymentDueAt(deadline(v, now, windowHours()));
        v.setPaymentRejectionReason(null);
    }

    // 🚶 A walk-in with a total above 0 waits for payment from the moment it is booked (no approval step): billing number,
    // payment link, and minutes to pay (WalkInService.paymentDeadline). The caller saves and emails.
    public void onWalkIn(Visitor v, LocalDateTime now, int minutes) {
        if (!v.paymentRequired()) {
            v.setPaymentStatus(Visitor.PAY_NOT_REQUIRED);
            return;
        }
        if (v.getBillingNumber() == null) v.setBillingNumber(billingNumbers.next());
        if (v.getPaymentToken() == null) v.setPaymentToken(UUID.randomUUID().toString());
        v.setPaymentStatus(Visitor.PAY_AWAITING);
        v.setPaymentRequestedAt(now);
        v.setPaymentDueAt(WalkInService.paymentDeadline(v, now, minutes));
    }

    // --- Deadlines ---

    // When the booked visit starts: the date plus the window's start time; an unreadable window starts at midnight
    public static LocalDateTime visitStart(Visitor v) {
        if (v.getVisitDate() == null) return null;
        LocalTime[] window = com.paf.aerovisit.controller.GuardOperationController.parseTimeWindow(v.getTimeWindow());
        return v.getVisitDate().atTime(window != null ? window[0] : LocalTime.MIDNIGHT);
    }

    // The EARLIER of now + the payment window and the start of the visit
    public static LocalDateTime deadline(Visitor v, LocalDateTime now, int windowHours) {
        LocalDateTime byWindow = now.plusHours(windowHours);
        LocalDateTime start = visitStart(v);
        return (start != null && start.isBefore(byWindow)) ? start : byWindow;
    }

    // After a rejected receipt: at least now + 24 hours to upload a new one, but never past the start of the visit
    public static LocalDateTime deadlineAfterRejection(Visitor v, LocalDateTime now) {
        LocalDateTime extended = now.plusHours(REUPLOAD_HOURS);
        if (v.getPaymentDueAt() != null && v.getPaymentDueAt().isAfter(extended)) extended = v.getPaymentDueAt();
        LocalDateTime start = visitStart(v);
        return (start != null && start.isBefore(extended)) ? start : extended;
    }

    public static String deadlineText(LocalDateTime due) {
        return due == null ? "" : due.format(DEADLINE);
    }

    // --- Receipt upload ---

    // Why this booking can't take a receipt now, or null when it can
    public static String uploadBlocker(Visitor v, LocalDateTime now) {
        if (!v.paymentRequired() || v.getPaymentStatus() == null) return "This booking does not need a payment.";
        if (Visitor.PAY_PAID.equals(v.getPaymentStatus())) return "Your payment has already been verified. No further upload is needed.";
        if (!"APPROVED".equals(v.getStatus()) && !v.awaitsApproval() && !v.awaitsReceipt()) return "This booking can no longer take a payment.";
        // A receipt already under review may be replaced; otherwise the deadline applies even before the sweep runs
        if (!Visitor.PAY_PROOF_SUBMITTED.equals(v.getPaymentStatus()) && v.getPaymentDueAt() != null && now.isAfter(v.getPaymentDueAt())) {
            return "The payment deadline has passed. Please contact the museum.";
        }
        return null;
    }
}
