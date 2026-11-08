package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.ReceiptStorage;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

/**
 * 💳 The visitor's payment page: how to pay on Landbank Link.BizPortal, then "Upload Proof of Payment".
 *
 * Reachable ONLY by the booking's owner: the unguessable token from the "please pay" email (GET /payment?token=...)
 * or the Manage Booking sign-in (GET /manage-booking/payment, which reads the session's own booking). A billing number
 * never opens anything (it is a short running number, easy to guess). The token is never logged, and the page and
 * upload response send Referrer-Policy: no-referrer, so it can't leak to the Landbank site or elsewhere.
 */
@Controller
public class PaymentController {

    static final String INVALID_LINK = "This payment link is invalid or has expired. Please sign in to Manage Booking with your email and Booking Reference ID.";

    private final VisitorRepository repo;

    @Autowired(required = false)
    private PaymentWorkflow paymentWorkflow;

    @Autowired(required = false)
    private ReceiptStorage receiptStorage;

    @Autowired(required = false)
    private NotificationService notificationService;

    // ⏱️ "Now" (ClockConfig; null in test slices = the system clock)
    @Autowired(required = false)
    java.time.Clock clock;

    private LocalDateTime now() {
        return LocalDateTime.now(com.paf.aerovisit.config.ClockConfig.or(clock));
    }

    // 🚶 The walk-in gate QR (the booking's secure token) as an <img> data URI, or null when it can't be used now
    static String walkInQr(Visitor v, java.time.LocalDate today) {
        return v.walkInPassUsable(today) ? com.paf.aerovisit.util.QRCodeGenerator.dataUri(v.getSecureToken(), 320) : null;
    }

    public PaymentController(VisitorRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/payment")
    public String paymentByToken(@RequestParam(name = "token", required = false) String token, Model model,
                                 HttpServletResponse response) {
        noReferrer(response);
        Visitor v = byToken(token);
        if (v == null) {
            model.addAttribute("invalid", INVALID_LINK);
            return "payment";
        }
        fill(model, v);
        model.addAttribute("paymentToken", token);
        return "payment";
    }

    @GetMapping("/manage-booking/payment")
    public String paymentFromManageBooking(HttpSession session, Model model, HttpServletResponse response) {
        noReferrer(response);
        Visitor v = sessionVisitor(session);
        if (v == null) return "redirect:/manage-booking";
        fill(model, v);
        model.addAttribute("formToken", session.getAttribute(VisitorPortalController.MANAGE_FORM_TOKEN));
        return "payment";
    }

    @PostMapping("/payment/proof")
    public String uploadProof(@RequestParam(name = "token", required = false) String token,
                              @RequestParam(name = "formToken", required = false) String formToken,
                              @RequestParam(name = "receipt", required = false) MultipartFile receipt,
                              HttpSession session, HttpServletResponse response, RedirectAttributes redirectAttributes) {
        noReferrer(response);
        boolean viaToken = token != null && !token.isBlank();
        Visitor v = viaToken ? byToken(token) : sessionVisitor(session, formToken);
        if (v == null) {
            if (viaToken) {
                redirectAttributes.addFlashAttribute("error", INVALID_LINK);
                return "redirect:/manage-booking";
            }
            redirectAttributes.addFlashAttribute("error", "Your session has expired. Please sign in again.");
            return "redirect:/manage-booking";
        }
        String back = viaToken ? "redirect:/payment?token=" + token : "redirect:/manage-booking/payment";

        String blocker = PaymentWorkflow.uploadBlocker(v, now());
        if (blocker == null && (v.getBillingNumber() == null || receiptStorage == null)) {
            blocker = "Uploads are unavailable right now. Please try again later.";
        }
        if (blocker != null) {
            redirectAttributes.addFlashAttribute("error", blocker);
            return back;
        }
        try {
            String saved = receiptStorage.saveReceipt(receipt);
            v.keepEarlierReceipt(); // 🧾 the previous receipt is never deleted (proof of payment)
            v.setReceiptImageUrl(saved);
        } catch (ReceiptStorage.InvalidReceiptException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return back;
        } catch (java.io.IOException e) {
            System.err.println("❌ Receipt upload failed for visitor #" + v.getId() + ": " + e.getMessage());
            redirectAttributes.addFlashAttribute("error", "Your receipt could not be saved. Please try again.");
            return back;
        }
        v.setReceiptUploadedAt(now());
        v.setPaymentStatus(Visitor.PAY_PROOF_SUBMITTED);
        v.setPaymentRejectionReason(null);
        // 💳 PAY FIRST: the receipt is what puts an advance booking in the Pending Approval tab. Its seats have been held
        // since it was booked, so there is no new seat check here.
        boolean firstReceipt = v.awaitsReceipt();
        if (firstReceipt) v.setStatus("PENDING");
        repo.save(v);
        if (notificationService != null) {
            if (v.walkIn() || "APPROVED".equals(v.getStatus())) notificationService.paymentProofSubmitted(v); // never throws
            else notificationService.bookingRequest(v); // one request for the Director: booking + payment; never throws
        }
        redirectAttributes.addFlashAttribute("success", v.walkIn()
                ? "Thank you! Your proof of payment was submitted. Please go to the gate: the guard will check your receipt, your LANDBANK confirmation and your ID."
                : "Thank you! Your proof of payment was submitted. The museum will review your booking and payment, and email your Entry QR Code once it is approved.");
        return back;
    }

    // --- helpers ---

    private static void noReferrer(HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    // The booking behind an emailed payment link; a token that is missing or unknown opens nothing
    private Visitor byToken(String token) {
        if (token == null || token.isBlank() || token.length() > 64) return null;
        return repo.findByPaymentToken(token.trim()).filter(Visitor::paymentRequired).orElse(null);
    }

    // The Manage Booking session's own booking (the page view needs no form token; the upload does)
    private Visitor sessionVisitor(HttpSession session) {
        Object id = session.getAttribute(VisitorPortalController.MANAGE_VISITOR_ID);
        if (!(id instanceof Long visitorId)) return null;
        return repo.findById(visitorId).orElse(null);
    }

    private Visitor sessionVisitor(HttpSession session, String formToken) {
        Object expected = session.getAttribute(VisitorPortalController.MANAGE_FORM_TOKEN);
        if (expected == null || formToken == null || !expected.equals(formToken)) return null;
        return sessionVisitor(session);
    }

    private void fill(Model model, Visitor v) {
        model.addAttribute("booking", v);
        model.addAttribute("landbankUrl", paymentWorkflow == null ? null : paymentWorkflow.landbankUrl());
        model.addAttribute("contactEmail", paymentWorkflow == null ? null : paymentWorkflow.contactEmail());
        model.addAttribute("deadline", PaymentWorkflow.deadlineText(v.getPaymentDueAt()));
        model.addAttribute("walkIn", v.walkIn());
        model.addAttribute("needsPayment", v.paymentRequired() && v.getBillingNumber() != null);
        String blocker = PaymentWorkflow.uploadBlocker(v, now());
        model.addAttribute("uploadBlocker", blocker);
        model.addAttribute("walkInQr", walkInQr(v, now().toLocalDate()));
        model.addAttribute("maxReceiptMb", ReceiptStorage.MAX_RECEIPT_BYTES / (1024 * 1024));
    }
}
