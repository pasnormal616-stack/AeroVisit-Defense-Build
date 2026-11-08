package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.MuseumZone;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumZoneRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.AdmissionPricing;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.IdentityBans;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PredictiveAdvisorService;
import com.paf.aerovisit.service.SeatReservationService;
import com.paf.aerovisit.util.ReferenceIdGenerator;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Controller
public class VisitorPortalController {

    private final VisitorRepository repo;
    private final BookingSlotRepository bookingRepo;
    private final EmailService emailService;
    private final PredictiveAdvisorService aiService;

    // Feedback zones (Root Cause Matrix); optional so controller test slices without this mock still start
    @Autowired(required = false)
    private MuseumZoneRepository zoneRepo;

    // Header bell notifications (requests, changes, cancellations, banned faces, negative feedback). Optional, like zoneRepo, so web-layer test slices still start.
    @Autowired(required = false)
    private NotificationService notificationService;

    // Race-safe capacity save. Optional, like the two above, so web-layer test slices still start; the real app always has it.
    @Autowired(required = false)
    private SeatReservationService seatReservation;

    // 📧 Email OTP (replaced reCAPTCHA). Optional so web-layer test slices still start; when missing, /save fails closed.
    @Autowired(required = false)
    private OtpService otpService;

    // 🪪 Ban list (IdentityBans: email, phone and name; no face data). Optional, so web-layer test slices without it still
    // start.
    @Autowired(required = false)
    private com.paf.aerovisit.repository.BlacklistedFaceRepository faceBanRepo;

    // 📮 The "booking received" email and the critical-feedback alert go out in the background, so Submit answers at once
    // instead of waiting for the mail server. Optional: without it (test slices) they are sent right away (MailOutbox.send).
    @Autowired(required = false)
    private com.paf.aerovisit.service.MailOutbox mailOutbox;

    // ☁️ Copies the ID photos to S3 (off locally). Optional, so test slices without it still start.
    @Autowired(required = false)
    private com.paf.aerovisit.service.UploadMirror uploadMirror;

    // 💱 Admission prices (Settings page). Optional, so test slices without it still start; without it the defaults apply.
    @Autowired(required = false)
    private AdmissionPricing admissionPricing;

    // 🎌 Holidays & Closed Days (Slots & Staff): not bookable, no walk-ins, no moves onto them. Optional, so test slices
    // without a database still start (then no holidays are listed).
    @Autowired(required = false)
    private com.paf.aerovisit.service.Holidays holidays;

    // The listed holiday on that date, or null
    private com.paf.aerovisit.model.Holiday holidayOn(LocalDate date) {
        return holidays == null || date == null ? null : holidays.on(date).orElse(null);
    }

    // 🚶 Walk-in (today) rules and the walk-in payment step. Optional, so test slices without them still start; without
    // them walk-ins are refused and advance bookings work as before.
    @Autowired(required = false)
    private com.paf.aerovisit.service.WalkInService walkInService;

    @Autowired(required = false)
    private com.paf.aerovisit.service.PaymentWorkflow paymentWorkflow;

    // 📅 Advance bookings: minimum days ahead (Settings, default 1 = tomorrow; today is Walk-in only). Optional, so test
    // slices without it still start; without it the default rule applies.
    @Autowired(required = false)
    private com.paf.aerovisit.service.AdvanceBookingRules advanceBookingRules;

    // ⏱️ "Today" and "now" for bookings and Manage Booking (ClockConfig; null in test slices = the system clock)
    @Autowired(required = false)
    java.time.Clock clock;

    private java.time.LocalDateTime now() {
        return java.time.LocalDateTime.now(com.paf.aerovisit.config.ClockConfig.or(clock));
    }

    private LocalDate today() {
        return now().toLocalDate();
    }

    // 📝 Version of the Terms and Conditions popup (fragments/visitor :: bookingTerms). Bump it whenever the terms text
    // changes; each booking records the version its visitor agreed to.
    public static final String TERMS_VERSION = "2026-11d";
    static final String TERMS_REQUIRED_MESSAGE = "Please read and agree to the Terms and Conditions before booking.";

    // 🚶 The booking form's bookingType for a walk-in (anything else is an Advance Booking)
    static final String WALK_IN = "WALK_IN";
    static final String WALK_IN_TODAY_ONLY = "Walk-in bookings are for today only. For another day, please choose Advance Booking.";
    static final String WALK_IN_UNAVAILABLE = "Walk-in booking is not available right now. Please book in advance or ask the museum staff.";
    static final String PHOTOS_REQUIRED = "Please take or upload a photo of your valid ID.";
    static final String ID_PHOTO_NOT_IMAGE = "Security Alert: Please upload a JPG, PNG or WEBP photo of your valid ID.";
    static final String ID_PHOTO_HEIC = "HEIC photos aren't supported. Please choose a JPG, PNG or WEBP photo of your ID"
            + " (on iPhone: Settings > Camera > Formats > Most Compatible).";
    // The same limit and wording as the upload-size handler (CmsUploadLimitAdvice), checked here too
    static final long ID_PHOTO_MAX_BYTES = 15L * 1024 * 1024;
    static final String ID_PHOTO_TOO_LARGE = "Your ID photo is larger than 15 MB. Please upload a smaller JPG, PNG or WEBP (a screenshot or a lower camera resolution works).";

    // Inbox for critical-feedback alerts (aerovisit.alerts.email: ALERT_EMAIL, else the system's sending address)
    @Value("${aerovisit.alerts.email:}")
    private String alertEmail;

    public VisitorPortalController(VisitorRepository repo, BookingSlotRepository bookingRepo, EmailService emailService, PredictiveAdvisorService aiService) {
        this.repo = repo;
        this.bookingRepo = bookingRepo;
        this.emailService = emailService;
        this.aiService = aiService;
    }

    // 🔒 MASS-ASSIGNMENT GUARD: only the fields on the public registration form may be bound from the request.
    // Server-controlled fields (id, status, secureToken, referenceId, qrCodeUrl, check-in data, etc.) are ignored,
    // so a crafted POST cannot self-approve, overwrite another booking, or forge a QR token. Companion names and an
    // event justification are no longer collected, so a request that still sends them has them ignored. groupSize is
    // not bound either: the server sets it from the three admission counts, and prices and totals are never bound.
    @InitBinder("visitor")
    public void restrictVisitorFields(WebDataBinder binder) {
        binder.setAllowedFields(
            "visitDate", "timeWindow", "visitorType", "fullName", "sex", "age",
            "address", "affiliation", "email", "contactNumber", "specialConsideration",
            "discountedCount", "regularCount", "freeCount", "purpose"
        );
    }

    // 🔑 ONE-TIME KYC NONCE: a fresh random value is put in the visitor's session (and in the booking form) every time
    // the booking page is rendered. /save only accepts a submission that echoes it back, and burns it on the first
    // attempt, so a booking can't be scripted straight at /save without loading the page (and its camera checks) in a
    // real session, and a captured submission can't be replayed. It is also /save's CSRF token (see SecurityConfig).
    static final String KYC_NONCE = "kycNonce";
    static final String KYC_NONCE_MESSAGE = "Security Error: Your booking session expired or this form was already submitted. Please fill in the form and take your photos again.";

    @GetMapping("/")
    public String showForm(Model model, HttpSession session) {
        if (!model.containsAttribute("visitor")) {
            model.addAttribute("visitor", new Visitor());
        }
        String kycNonce = java.util.UUID.randomUUID().toString();
        session.setAttribute(KYC_NONCE, kycNonce);
        model.addAttribute(KYC_NONCE, kycNonce);
        model.addAttribute("admission", currentPrices());

        // ⚡ Only today's and later slots, and the seats already held on them, totalled by the database. This page is the
        // one everybody opens, so it must not load every visitor ever saved (face data included) on each view: that
        // grew heavier with every booking and could run the server out of memory when a crowd opened the site at once.
        LocalDate today = today();
        List<BookingSlot> allSlots = bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(today);
        Map<String, Integer> seatsHeld = new HashMap<>(); // "2026-11-02|Full Day (09:00 AM - 05:00 PM)" -> seats
        for (Object[] row : repo.sumSeatsHeldFrom(today, Visitor.SEAT_HOLDING_STATUSES)) { // pay-first: pending bookings hold seats too
            if (row[0] != null && row[1] != null) seatsHeld.put(row[0] + "|" + row[1], ((Number) row[2]).intValue());
        }
        List<Map<String, Object>> slotDataList = new ArrayList<>();
        Set<LocalDate> closureDates = new HashSet<>(repo.findClosureDates());
        // 🎌 Holidays from today to the last slot date: not pickable, shown on the calendar as "Closed: <name>"
        Map<String, String> closedDays = new java.util.TreeMap<>();
        if (holidays != null) {
            LocalDate lastSlot = allSlots.stream().map(BookingSlot::getSlotDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(today);
            holidays.between(today, lastSlot.isAfter(today) ? lastSlot : today.plusMonths(1))
                    .forEach((d, h) -> closedDays.put(d.toString(), com.paf.aerovisit.service.Holidays.closedReason(h)));
        }

        for (BookingSlot slot : allSlots) {
            if (slot.getSlotDate() != null && !slot.getSlotDate().isBefore(today) && !closedDays.containsKey(slot.getSlotDate().toString())) {

                int bookedCount = seatsHeld.getOrDefault(slot.getSlotDate() + "|" + slot.getTimeWindow(), 0);

                int remaining = slot.getMaxCapacity() - bookedCount;
                if (remaining < 0) remaining = 0;
                if (closureDates.contains(slot.getSlotDate())) remaining = 0; // 🚨 Emergency closure: museum closed that day
                
                Map<String, Object> map = new HashMap<>();
                map.put("id", slot.getId());
                map.put("date", slot.getSlotDate().toString());
                map.put("timeWindow", slot.getTimeWindow());
                map.put("maxCapacity", slot.getMaxCapacity());
                map.put("booked", bookedCount);
                map.put("remaining", remaining);
                slotDataList.add(map);
            }
        }
        
        model.addAttribute("calendarSlots", slotDataList);
        model.addAttribute("closedDays", closedDays);
        // 🚶 Walk-in (today): the windows a walk-in may pick now, or why there are none
        model.addAttribute("walkIn", walkInService == null
                ? new com.paf.aerovisit.service.WalkInService.Availability(List.of(), WALK_IN_UNAVAILABLE)
                : walkInService.today(now()));
        model.addAttribute("walkInMaxPax", walkInService == null ? 0 : walkInService.maxPax());
        model.addAttribute("walkInToday", today);
        // 📅 Advance mode: the calendar greys out every day before this one (today is Walk-in only)
        model.addAttribute("advanceEarliest", advanceEarliest().toString());
        return "register";
    }

    @PostMapping("/save")
    public String saveVisitor(@ModelAttribute Visitor visitor,
                              @RequestParam(name="highResIdImage", required=false) MultipartFile idCardFile,
                              @RequestParam(name=KYC_NONCE, required=false) String kycNonce,
                              @RequestParam(name="termsAccepted", required=false) String termsAccepted,
                              @RequestParam(name="bookingType", required=false) String bookingType,
                              HttpServletRequest request,
                              RedirectAttributes redirectAttributes) {

        // 🔑 ONE-TIME KYC NONCE first: checked against the session's value and burned whatever the outcome
        if (!consumeKycNonce(request.getSession(false), kycNonce)) {
            redirectAttributes.addFlashAttribute("error", KYC_NONCE_MESSAGE);
            return "redirect:/";
        }

        // 📝 TERMS AND CONDITIONS: the popup's one consent sets termsAccepted; the server refuses a booking without it
        if (!"true".equals(termsAccepted)) {
            redirectAttributes.addFlashAttribute("error", TERMS_REQUIRED_MESSAGE);
            return "redirect:/";
        }
        visitor.setTermsAcceptedAt(now());
        visitor.setTermsVersion(TERMS_VERSION);

        // 🚶 WALK-IN (TODAY): the date is today, set here; another date sent by the browser is refused
        boolean walkIn = WALK_IN.equals(bookingType);
        java.time.LocalDateTime now = now();
        if (walkIn) {
            if (walkInService == null) {
                redirectAttributes.addFlashAttribute("error", WALK_IN_UNAVAILABLE);
                return "redirect:/";
            }
            if (visitor.getVisitDate() != null && !now.toLocalDate().equals(visitor.getVisitDate())) {
                redirectAttributes.addFlashAttribute("error", WALK_IN_TODAY_ONLY);
                return "redirect:/";
            }
            visitor.setVisitDate(now.toLocalDate());
            visitor.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        } else if (visitor.getVisitDate() != null && visitor.getVisitDate().isBefore(advanceEarliest())) {
            // 📅 ADVANCE: at least the Director's minimum days ahead (Manila time); today is Walk-in only. The calendar
            // greys these days out, but this is the check that counts.
            redirectAttributes.addFlashAttribute("error", advanceTooSoonMessage());
            return "redirect:/";
        }

        // Bridge the new PAF 'fullName' requirement with legacy 'name' methods
        if (visitor.getName() == null || visitor.getName().isEmpty()) {
            visitor.setName(visitor.getFullName()); 
        }

        // Generate Official Base Access Reference Number
        if (visitor.getReferenceNumber() == null) {
            visitor.setReferenceNumber("PAF-" + (int)(Math.random() * 90000 + 10000));
        }

        // Ensure token is generated if not present
        if (visitor.getSecureToken() == null) {
            visitor.setSecureToken(java.util.UUID.randomUUID().toString());
        }

        // 🚨 Emergency closure: nobody may book a date the museum has closed
        if (visitor.getVisitDate() != null && repo.findClosureDates().contains(visitor.getVisitDate())) {
            redirectAttributes.addFlashAttribute("error", "The museum is closed on this date due to an emergency. Please choose another date.");
            return "redirect:/";
        }
        // 🎌 A listed holiday: no advance booking and no walk-in
        com.paf.aerovisit.model.Holiday holiday = holidayOn(visitor.getVisitDate());
        if (holiday != null) {
            redirectAttributes.addFlashAttribute("error", com.paf.aerovisit.service.Holidays.closedReason(holiday)
                    + (walkIn ? ". Please book a visit for another day." : ". Please choose another date."));
            return "redirect:/";
        }

        // 📧 EMAIL OTP: the booking email must have been verified with its 6-digit code (POST /otp/verify). Fails closed.
        if (otpService == null || !otpService.isVerified(visitor.getEmail())) {
            redirectAttributes.addFlashAttribute("error", "Security Error: Please verify your email address with the 6-digit code we sent before submitting.");
            return "redirect:/";
        }

        // 🪪 IDENTITY BAN: the same email or phone number as an active ban is refused with a neutral message. A new email
        // beats this check, so a banned visitor is also FLAGGED (never refused) by name, for a person to compare.
        List<com.paf.aerovisit.model.BlacklistedFace> bans = faceBanRepo == null ? List.of() : faceBanRepo.findAll();
        if (IdentityBans.contactBan(visitor, bans, today()) != null) {
            if (notificationService != null) notificationService.bannedContactAttempt(visitor);
            redirectAttributes.addFlashAttribute("error", IdentityBans.BANNED_MESSAGE);
            return "redirect:/";
        }

        List<Visitor> existingBookings = repo.findByEmailAndVisitDate(visitor.getEmail(), visitor.getVisitDate());
        // Visitors whose booking the museum cancelled (an emergency closure, once reopened, or an older cancellation) or who
        // cancelled themselves may re-book the same date
        existingBookings.removeIf(b -> "CANCELLED_OVERRIDE".equals(b.getStatus()) || "CANCELLED_BY_VISITOR".equals(b.getStatus()) || "CANCELLED".equals(b.getStatus())
                || Visitor.STATUS_PAYMENT_EXPIRED.equals(b.getStatus()));
        // ⏳ A REJECTED application no longer blocks the date for good: the visitor may re-apply after the cooldown (below)
        List<Visitor> rejectedBookings = existingBookings.stream().filter(b -> "REJECTED".equals(b.getStatus())).toList();
        existingBookings.removeIf(b -> "REJECTED".equals(b.getStatus()));
        if (!existingBookings.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Registration Denied: Duplicate booking detected for this date.");
            return "redirect:/";
        }
        if (inRejectedCooldown(rejectedBookings, now())) {
            redirectAttributes.addFlashAttribute("error", REJECTED_COOLDOWN_MESSAGE);
            return "redirect:/";
        }

        // 💱 ADMISSION: groupSize is the three counts added up, and the total is priced here at today's prices, before the
        // capacity check below runs on that sum. A total or groupSize sent by the browser is never read.
        int discounted = orZero(visitor.getDiscountedCount()), regular = orZero(visitor.getRegularCount()), free = orZero(visitor.getFreeCount());
        String countsError = AdmissionPricing.countsError(discounted, regular, free);
        if (countsError != null) {
            redirectAttributes.addFlashAttribute("error", countsError);
            return "redirect:/";
        }
        applyAdmission(visitor, AdmissionPricing.quote(discounted, regular, free, currentPrices()));

        // 🚶 A walk-in picks one of today's windows with at least 20 minutes left, and a group of at most the Director's
        // maximum (Settings, default 20)
        if (walkIn) {
            String windowError = walkInService.windowError(visitor.getTimeWindow(), now);
            if (windowError != null) {
                redirectAttributes.addFlashAttribute("error", windowError);
                return "redirect:/";
            }
            int maxPax = walkInService.maxPax();
            if (visitor.getGroupSize() > maxPax) {
                redirectAttributes.addFlashAttribute("error", com.paf.aerovisit.service.WalkInService.tooManyMessage(maxPax));
                return "redirect:/";
            }
            if (visitor.paymentRequired() && paymentWorkflow == null) {
                redirectAttributes.addFlashAttribute("error", WALK_IN_UNAVAILABLE);
                return "redirect:/";
            }
        }

        List<Visitor> visitorsOnDate = repo.findByVisitDate(visitor.getVisitDate());
        int currentBooked = 0;
        for(Visitor v : visitorsOnDate) {
             if (v.holdsSeat() && v.getTimeWindow() != null && v.getTimeWindow().equals(visitor.getTimeWindow())) {
                 currentBooked += Math.max(1, v.getGroupSize());
             }
        }
        
        int actualMaxCapacity = bookableSlotCapacity(visitor.getVisitDate(), visitor.getTimeWindow());
        // 👻 GHOST SLOTS: no default capacity. A date/time window with no slot (never created, deleted, already past or
        // made up in a hand-made request) is refused.
        if (actualMaxCapacity <= 0) {
            redirectAttributes.addFlashAttribute("error", "Invalid or unavailable time slot. Please choose an open date and time from the calendar.");
            return "redirect:/";
        }

        int incomingGroupSize = Math.max(1, visitor.getGroupSize());
        
        if (currentBooked + incomingGroupSize > actualMaxCapacity) {
            redirectAttributes.addFlashAttribute("error", "Insufficient slots available for this specific shift! Please choose another slot.");
            return "redirect:/";
        }

        // 🪖 GROUP BOOKING: the representative gives only the group size; only their own details and ID are collected
        visitor.setCompanionNames(null);
        visitor.setEventJustification(null);

        if (visitor.getStatus() == null) visitor.setStatus("PENDING");

        // 🪪 ONE PHOTO OF THE VALID ID (taken or uploaded), required for advance bookings and walk-ins alike. No selfie.
        // 🔒 The ID photo: the real file type is read from the bytes (signature + full decode, ReceiptStorage), never from
        // the name or the browser's declared type, so a renamed document or script is refused. HEIC (an iPhone setting) is
        // refused with its own message; the booking page converts photos to JPEG before upload whenever the browser can.
        String photoError = idPhotoError(idCardFile);
        if (photoError != null) {
            redirectAttributes.addFlashAttribute("error", photoError);
            return "redirect:/";
        }

        if (walkIn) {
            // 🚶 No Director approval: the seats are held at once (APPROVED, saved under the seat lock below)
            visitor.setStatus("APPROVED");
            visitor.setCheckInDueAt(com.paf.aerovisit.service.WalkInService.checkInDeadline(visitor, now, walkInService.minutes()));
        } else if (visitor.paymentRequired()) {
            // 💳 PAY FIRST: a paid advance booking holds its seats from now (saved under the seat lock below) and waits
            // for its receipt; it reaches the Pending Approval tab once the receipt is uploaded. Unpaid by the deadline =
            // the 15-minute sweep expires it and frees the seats.
            if (paymentWorkflow == null) {
                redirectAttributes.addFlashAttribute("error", "Online booking is not available right now. Please try again later or contact the museum.");
                return "redirect:/";
            }
            visitor.setStatus(Visitor.STATUS_AWAITING_RECEIPT);
        } else {
            // A free advance booking holds its seats at once and goes straight to Pending Approval
            visitor.setStatus("PENDING");
            visitor.setPaymentStatus(visitor.admissionCounted() ? Visitor.PAY_NOT_REQUIRED : null);
        }
        try {
            Path uploadPath = Paths.get("uploads");
            if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);
            // 🔒 PRIVACY: anonymous UUID names, so the visitor's name never appears in the file system or /uploads URLs.
            // The ID photo goes in idCardImageUrl; idImageUrl (the old liveness selfie) stays empty on new bookings.
            String cardName = anonymousIdPhotoName(idCardFile.getOriginalFilename(), idCardFile.getContentType());
            Files.copy(idCardFile.getInputStream(), uploadPath.resolve(cardName), StandardCopyOption.REPLACE_EXISTING);
            visitor.setIdCardImageUrl(cardName);
            if (uploadMirror != null) uploadMirror.pushUpload(cardName);
            // 🪪 No automated identity check: the ID photo is only stored for staff to look at (Pending Approval, the
            // guard's Walk-ins tab), and purged with the other files.
        } catch (Exception e) {
            System.out.println("ID photo could not be saved: " + e.getMessage());
            redirectAttributes.addFlashAttribute("error", "Your ID photo could not be saved. Please try again.");
            return "redirect:/";
        }

        // 🪪 NAME FLAG: the representative's name resembles a banned name. Never refused (people share names): the
        // booking is tagged with the ban, for the Director (Pending Approval) or the guard (walk-in) to compare.
        com.paf.aerovisit.model.BlacklistedFace nameBan = IdentityBans.nameBan(IdentityBans.namesOf(visitor), bans, today());
        if (nameBan != null) {
            // 🚶 A walk-in keeps its seats: the guard sees the ban's name and must tick "ID checked against the ban"
            String match = "Ban #" + nameBan.getId() + ": " + nameBan.getName();
            visitor.setPossibleBanMatch(match.length() > 300 ? match.substring(0, 300) : match);
            visitor.setPossibleBanId(nameBan.getId());
        }

        // Generate a unique Booking Reference ID (always server-side, never trusted from the form)
        String referenceId;
        do {
            referenceId = ReferenceIdGenerator.generate();
        } while (repo.existsByReferenceId(referenceId));
        visitor.setReferenceId(referenceId);

        Visitor savedVisitor;
        try {
            savedVisitor = saveWithinCapacity(visitor);
        } catch (SeatReservationService.CapacityFullException full) {
            // 🔒 Another booking took the last seats between the check above and this save
            discardIdPhoto(visitor.getIdCardImageUrl());
            redirectAttributes.addFlashAttribute("error", "Insufficient slots available for this specific shift! Please choose another slot.");
            return "redirect:/";
        } catch (org.springframework.dao.PessimisticLockingFailureException busy) {
            discardIdPhoto(visitor.getIdCardImageUrl());
            redirectAttributes.addFlashAttribute("error", "This time slot is being booked by other visitors right now. Please try again in a moment.");
            return "redirect:/";
        }
        otpService.consume(visitor.getEmail()); // one verified code = one booking
        if (walkIn) return walkInBooked(savedVisitor, now, request, redirectAttributes);
        if (savedVisitor.awaitsReceipt()) return advanceAwaitingPayment(savedVisitor, now, request);
        redirectAttributes.addFlashAttribute("visitor", savedVisitor);
        if (notificationService != null) {
            notificationService.bookingRequest(savedVisitor); // only bookings waiting for approval; never throws
        }

        // HUMAN-IN-THE-LOOP: every booking waits for the Director, so the visitor is told it is pending; the QR code is
        // only made and emailed at approval (GuardOperationController.approveVisitor)
        String to = savedVisitor.getEmail(), name = savedVisitor.getName(), date = savedVisitor.getVisitDate().toString(),
                ref = savedVisitor.getReferenceId();
        com.paf.aerovisit.service.MailOutbox.send(mailOutbox, "booking-received email for " + ref,
                () -> emailService.sendPendingNotification(to, name, date, ref), null);

        return "redirect:/success-page"; 
    }

    // 🚶 A saved walk-in. Free: "go to the gate" with the reference ID. To pay: a billing number (assigned only now, after
    // the seats were held, so a full window never uses one up), the payment link by email, and the payment page at once
    // through a Manage Booking sign-in, so the payment token never appears in a redirect URL.
    private String walkInBooked(Visitor v, java.time.LocalDateTime now, HttpServletRequest request, RedirectAttributes redirectAttributes) {
        if (!v.paymentRequired()) {
            v.setPaymentStatus(Visitor.PAY_NOT_REQUIRED);
            Visitor saved = repo.save(v);
            redirectAttributes.addFlashAttribute("visitor", saved);
            redirectAttributes.addFlashAttribute("walkInGate", true);
            // 🚶 The gate QR on the "go to the gate" screen, and by email so it isn't lost if the page is closed
            redirectAttributes.addFlashAttribute("walkInQr", PaymentController.walkInQr(saved, now.toLocalDate()));
            String to = saved.getEmail(), name = saved.getName(), ref = saved.getReferenceId(), window = saved.getTimeWindow(),
                    token = saved.getSecureToken();
            com.paf.aerovisit.service.MailOutbox.send(mailOutbox, "walk-in pass email for " + ref,
                    () -> emailService.sendWalkInPass(to, name, ref, window, token), null);
            return "redirect:/success-page";
        }
        paymentWorkflow.onWalkIn(v, now, walkInService.minutes());
        Visitor saved = repo.save(v);
        com.paf.aerovisit.service.EmailService.PaymentNotice notice = com.paf.aerovisit.service.EmailService.PaymentNotice.of(saved);
        com.paf.aerovisit.service.MailOutbox.send(mailOutbox, "walk-in payment email for " + saved.getReferenceId(),
                () -> emailService.sendPaymentRequest(notice), null);
        signIntoManageBooking(request, saved.getId());
        return "redirect:/manage-booking/payment";
    }

    // 💳 A saved paid advance booking (its seats already held): a billing number (assigned only now, so a full window never
    // uses one up), the payment deadline (the Director's window, never past the visit start), the payment link by email,
    // and the payment page at once through a Manage Booking sign-in. No bell yet: the Director is told when the receipt
    // arrives.
    private String advanceAwaitingPayment(Visitor v, java.time.LocalDateTime now, HttpServletRequest request) {
        paymentWorkflow.onAdvanceBooking(v, now);
        Visitor saved = repo.save(v);
        com.paf.aerovisit.service.EmailService.PaymentNotice notice = com.paf.aerovisit.service.EmailService.PaymentNotice.of(saved);
        com.paf.aerovisit.service.MailOutbox.send(mailOutbox, "payment email for " + saved.getReferenceId(),
                () -> emailService.sendPaymentRequest(notice), null);
        signIntoManageBooking(request, saved.getId());
        return "redirect:/manage-booking/payment";
    }

    // 🔒 Signs this browser into Manage Booking for one booking, with a new session ID first (no session fixation)
    private static void signIntoManageBooking(HttpServletRequest request, Long visitorId) {
        request.getSession(true);
        request.changeSessionId();
        HttpSession session = request.getSession(false);
        session.setAttribute(MANAGE_VISITOR_ID, visitorId);
        session.setAttribute(MANAGE_FORM_TOKEN, java.util.UUID.randomUUID().toString());
    }

    @GetMapping("/success-page")
    public String showSuccessPage(Model model) {
        if(!model.containsAttribute("visitor")) {
            return "redirect:/";
        }
        return "success";
    }

    @GetMapping("/check-status")
    public String checkStatus(@RequestParam("email") String email,
                              @RequestParam(name = "referenceId", required = false) String referenceId,
                              Model model, RedirectAttributes redirectAttributes) {
        // 🔒 An email alone is guessable, so the Booking Reference ID must match too
        if (referenceId == null || referenceId.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Please enter both your email and Booking Reference ID.");
            return "redirect:/";
        }
        // Only this email's bookings are loaded, not every visitor
        List<Visitor> visitors = repo.findByEmailIgnoreCase(email.trim());
        Visitor foundVisitor = null;
        boolean legacyBookingFound = false;
        for (Visitor v : visitors) {
            if (v.getReferenceId() == null) {
                // Legacy booking made before Reference IDs existed: it has no ID to match, so never match on email alone
                legacyBookingFound = true;
            } else if (referenceId.trim().equalsIgnoreCase(v.getReferenceId())) {
                foundVisitor = v;
            }
        }
        if (foundVisitor != null) {
            model.addAttribute("visitor", foundVisitor);
            // Same visitor-friendly label as Manage Booking (never the raw status, never the internal rejection reason)
            model.addAttribute("statusLabel", visitorStatusLabel(foundVisitor.getStatus()));
            return "success";
        } else if (legacyBookingFound) {
            redirectAttributes.addFlashAttribute("error", "This booking was made before Booking Reference IDs were introduced. Please check the email we sent you or contact the museum to confirm its status.");
            return "redirect:/";
        } else {
            redirectAttributes.addFlashAttribute("error", "No booking found for this email and Reference ID.");
            return "redirect:/"; 
        }
    }
    
    // ==========================================================
    // 🧾 MANAGE BOOKING PORTAL (visitor self-service)
    // ==========================================================

    // Session keys: the signed-in booking's ID and a per-session token every portal form must echo back.
    // CSRF protection is off app-wide, so the token is what stops another site from posting a cancellation.
    // Package-visible: PaymentController opens the payment page for the same signed-in booking
    static final String MANAGE_VISITOR_ID = "manageVisitorId";
    static final String MANAGE_FORM_TOKEN = "manageFormToken";

    // Statuses that no longer hold a seat (kept in step with the capacity sums in showForm and saveVisitor)
    private static final Set<String> SEAT_FREE_STATUSES = Set.of("REJECTED", "CANCELLED_OVERRIDE", "CANCELLED_BY_VISITOR", "CANCELLED",
            Visitor.STATUS_PAYMENT_EXPIRED);

    // Re-applying for a date after a rejection: allowed once this many hours have passed since the newest REJECTED
    // application for that email + date (timed from its createdAt, i.e. when it was submitted)
    static final long REJECTED_COOLDOWN_HOURS = 4;
    static final String REJECTED_COOLDOWN_MESSAGE = "Please wait " + REJECTED_COOLDOWN_HOURS + " hours before reapplying for the same date.";

    // True while any of these REJECTED applications was submitted less than REJECTED_COOLDOWN_HOURS ago. Exactly 4 hours is
    // allowed. A row with no createdAt predates the timestamp column, so it is long past any cooldown.
    static boolean inRejectedCooldown(List<Visitor> rejected, java.time.LocalDateTime now) {
        java.time.LocalDateTime cutoff = now.minusHours(REJECTED_COOLDOWN_HOURS);
        return rejected.stream().map(Visitor::getCreatedAt).filter(Objects::nonNull).anyMatch(t -> t.isAfter(cutoff));
    }

    @GetMapping("/manage-booking")
    public String showManageBooking(HttpSession session, Model model) {
        Visitor v = null;
        Object id = session.getAttribute(MANAGE_VISITOR_ID);
        if (id instanceof Long visitorId) {
            v = repo.findById(visitorId).orElse(null);
        }
        if (v == null) {
            session.removeAttribute(MANAGE_VISITOR_ID);
            session.removeAttribute(MANAGE_FORM_TOKEN);
            return "manage-booking"; // sign-in screen
        }

        boolean upcoming = v.getVisitDate() != null && !v.getVisitDate().isBefore(today());
        model.addAttribute("booking", v);
        model.addAttribute("formToken", session.getAttribute(MANAGE_FORM_TOKEN));
        model.addAttribute("statusLabel", visitorStatusLabel(v.getStatus()));
        model.addAttribute("statusTone", visitorStatusTone(v.getStatus()));
        // 📅 A RESCHEDULE_REQUIRED booking may still pick a new date after its old one, until its deadline
        boolean movable = canStillMove(v);
        model.addAttribute("reschedulable", movable && isReschedulable(v));
        model.addAttribute("rescheduleDeadline", v.rescheduleDeadline());
        // 💳 Payment: the deadline as text, and whether the receipt can be attached now (same rules as the payment page)
        model.addAttribute("paymentDeadline", com.paf.aerovisit.service.PaymentWorkflow.deadlineText(v.getPaymentDueAt()));
        model.addAttribute("canPay", v.paymentRequired() && v.getBillingNumber() != null
                && com.paf.aerovisit.service.PaymentWorkflow.uploadBlocker(v, now()) == null);
        model.addAttribute("showCountdown", upcoming && countdownShown(v));
        model.addAttribute("walkInQr", PaymentController.walkInQr(v, today())); // 🚶 the gate QR, walk-ins only
        if (movable && isReschedulable(v)) {
            model.addAttribute("openSlots", openSlotsFor(v));
        }
        return "manage-booking";
    }

    @PostMapping("/manage-booking/login")
    public String manageBookingLogin(@RequestParam("email") String email,
                                     @RequestParam("referenceId") String referenceId,
                                     HttpServletRequest request, RedirectAttributes redirectAttributes) {
        Visitor v = (email.isBlank() || referenceId.isBlank()) ? null
                : repo.findByEmailIgnoreCaseAndReferenceIdIgnoreCase(email.trim(), referenceId.trim()).orElse(null);
        if (v == null) {
            redirectAttributes.addFlashAttribute("error", "No booking found for this email and Reference ID.");
            return "redirect:/manage-booking";
        }
        // 🔒 New session ID on sign-in, so a session ID planted before login cannot be reused
        signIntoManageBooking(request, v.getId());
        return "redirect:/manage-booking";
    }

    // 📅 RESCHEDULE: the only change a visitor makes online (no-refund system: no cancelling, no editing the group).
    // The new date follows the rules of a new advance booking (minimum days ahead, so never today; no closure or holiday;
    // an open slot with enough seats). Payment and Terms are not asked again; the group, counts and price stay as they are.
    // An APPROVED booking goes back to the Director for approval (its QR stops working); a payment already made stays PAID.
    @PostMapping("/manage-booking/reschedule")
    public String rescheduleManagedBooking(@RequestParam(name = "formToken", required = false) String formToken,
                                           @RequestParam(name = "visitDate", required = false) String visitDate,
                                           @RequestParam(name = "timeWindow", required = false) String timeWindow,
                                           HttpSession session, RedirectAttributes redirectAttributes) {
        Visitor v = manageSessionVisitor(session, formToken);
        if (v == null) {
            redirectAttributes.addFlashAttribute("error", "Your session has expired. Please sign in again.");
            return "redirect:/manage-booking";
        }
        if (!canStillMove(v) || !isReschedulable(v)) {
            redirectAttributes.addFlashAttribute("error", "This booking can no longer be changed online.");
            return "redirect:/manage-booking";
        }
        if (visitDate == null || visitDate.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Please choose a new date and time from the list.");
            return "redirect:/manage-booking";
        }
        if (timeWindow == null || timeWindow.isBlank()) {
            redirectAttributes.addFlashAttribute("error", "Please choose a time window for your selected date.");
            return "redirect:/manage-booking";
        }
        final LocalDate date;
        try {
            date = LocalDate.parse(visitDate.trim());
        } catch (DateTimeParseException e) {
            redirectAttributes.addFlashAttribute("error", "Please choose a valid date and time from the list.");
            return "redirect:/manage-booking";
        }
        final String window = timeWindow;
        if (date.equals(v.getVisitDate()) && window.equals(v.getTimeWindow())) {
            redirectAttributes.addFlashAttribute("error", "That is your current date and time. Please choose a different one.");
            return "redirect:/manage-booking";
        }
        if (date.isBefore(today())) {
            redirectAttributes.addFlashAttribute("error", "Please choose a date that has not passed.");
            return "redirect:/manage-booking";
        }
        // 📅 Like a new advance booking: at least the minimum days ahead (today is never offered)
        if (!date.isAfter(today()) || date.isBefore(advanceEarliest())) {
            redirectAttributes.addFlashAttribute("error", advanceTooSoonMessage());
            return "redirect:/manage-booking";
        }
        // 🚨 Emergency closure: the museum is closed that day
        if (repo.findClosureDates().contains(date)) {
            redirectAttributes.addFlashAttribute("error", "The museum is closed on this date due to an emergency. Please choose another date.");
            return "redirect:/manage-booking";
        }
        // 🎌 Listed holiday or closed day
        com.paf.aerovisit.model.Holiday holiday = holidayOn(date);
        if (holiday != null) {
            redirectAttributes.addFlashAttribute("error", com.paf.aerovisit.service.Holidays.closedReason(holiday) + ". Please choose another date.");
            return "redirect:/manage-booking";
        }
        int capacity = bookingRepo.findAll().stream()
                .filter(s -> date.equals(s.getSlotDate()) && window.equals(s.getTimeWindow()))
                .mapToInt(BookingSlot::getMaxCapacity)
                .sum();
        if (capacity <= 0) {
            redirectAttributes.addFlashAttribute("error", "The museum has no open booking slot for that date and time.");
            return "redirect:/manage-booking";
        }
        int size = Math.max(1, v.getGroupSize());
        // Own seats are left out (the booking leaves its old slot)
        int taken = seatsTaken(repo.findByVisitDate(date), window, v.getId());
        if (taken + size > capacity) {
            redirectAttributes.addFlashAttribute("error", "Not enough seats: only " + Math.max(0, capacity - taken) + " left in that time slot.");
            return "redirect:/manage-booking";
        }
        if (!date.equals(v.getVisitDate())) {
            boolean duplicate = repo.findByEmailAndVisitDate(v.getEmail(), date).stream()
                    .anyMatch(o -> !o.getId().equals(v.getId()) && !SEAT_FREE_STATUSES.contains(o.getStatus()));
            if (duplicate) {
                redirectAttributes.addFlashAttribute("error", "You already have another booking on that date.");
                return "redirect:/manage-booking";
            }
        }

        // 🚨 A paid booking on a date the museum closed (RESCHEDULE_REQUIRED) moves like an approved one
        boolean mustReschedule = Visitor.STATUS_RESCHEDULE_REQUIRED.equals(v.getStatus());
        boolean wasApproved = "APPROVED".equals(v.getStatus()) || mustReschedule;
        v.setVisitDate(date);
        v.setTimeWindow(window);
        if (mustReschedule && v.walkIn()) {
            // 🚶 A walk-in moved after the museum closed on its day is an Advance Booking now (its payment is kept)
            v.setBookingCategory(null);
            v.setCheckInDueAt(null);
        }
        if (Visitor.UNREVIEWED_NOTE.equals(v.getRejectionReason())) v.setRejectionReason(null); // 📅 it has its new date now
        if (wasApproved) {
            // The Director re-reviews the new schedule; the old QR stops working until it is approved again.
            // 💳 A payment already made stays PAID: the new date is approved without a new payment.
            v.setStatus("PENDING");
            v.setQrCodeUrl(null);
            v.setReminderSent(false);
        }
        // 💳 Awaiting its receipt: the payment deadline never falls after the new visit start
        if (v.awaitsReceipt() && v.getPaymentDueAt() != null) {
            java.time.LocalDateTime start = com.paf.aerovisit.service.PaymentWorkflow.visitStart(v);
            if (start != null && start.isBefore(v.getPaymentDueAt())) v.setPaymentDueAt(start);
        }
        // 🔒 A booking that holds seats is saved under the seat lock: the new slot is held and the old one frees
        if (v.holdsSeat()) {
            try {
                saveWithinCapacity(v);
            } catch (SeatReservationService.CapacityFullException full) {
                redirectAttributes.addFlashAttribute("error", "Not enough seats left in that time slot. Please choose another.");
                return "redirect:/manage-booking";
            } catch (org.springframework.dao.PessimisticLockingFailureException busy) {
                redirectAttributes.addFlashAttribute("error", "This time slot is being booked by other visitors right now. Please try again in a moment.");
                return "redirect:/manage-booking";
            }
        } else {
            repo.save(v);
        }
        if (notificationService != null && v.awaitsApproval()) {
            notificationService.bookingRescheduled(v); // never throws
        }
        redirectAttributes.addFlashAttribute("success", wasApproved
                ? "Booking updated. Your new schedule has been sent back to the museum for approval, and your previous QR code is no longer valid."
                : "Booking updated.");
        return "redirect:/manage-booking";
    }

    @PostMapping("/manage-booking/logout")
    public String manageBookingLogout(HttpSession session, RedirectAttributes redirectAttributes) {
        session.removeAttribute(MANAGE_VISITOR_ID);
        session.removeAttribute(MANAGE_FORM_TOKEN);
        redirectAttributes.addFlashAttribute("success", "You have signed out of Manage Booking.");
        return "redirect:/manage-booking";
    }

    // The signed-in booking, or null when the session is missing or the form token does not match
    private Visitor manageSessionVisitor(HttpSession session, String formToken) {
        Object id = session.getAttribute(MANAGE_VISITOR_ID);
        Object expected = session.getAttribute(MANAGE_FORM_TOKEN);
        if (!(id instanceof Long visitorId) || expected == null || formToken == null || !expected.equals(formToken)) {
            return null;
        }
        return repo.findById(visitorId).orElse(null);
    }

    // 📅 Visitors may move their booking to another date while PENDING, APPROVED or awaiting the receipt, and must pick a
    // new date when the museum closed on a paid booking's date (RESCHEDULE_REQUIRED)
    // 🚶 A walk-in is for today and can't be moved, unless the museum closed today (then it picks a new date)
    private static boolean isReschedulable(Visitor v) {
        if (v.walkIn()) return Visitor.STATUS_RESCHEDULE_REQUIRED.equals(v.getStatus());
        return "PENDING".equals(v.getStatus()) || "APPROVED".equals(v.getStatus())
                || Visitor.STATUS_RESCHEDULE_REQUIRED.equals(v.getStatus()) || v.awaitsReceipt();
    }

    // 📅 The booking's date hasn't passed, or it must be rescheduled and its deadline hasn't passed
    private boolean canStillMove(Visitor v) {
        if (v.getVisitDate() == null) return false;
        if (!v.getVisitDate().isBefore(today())) return true;
        LocalDate deadline = v.rescheduleDeadline();
        return deadline != null && !deadline.isBefore(today());
    }

    // ⏳ The countdown to the visit is shown while the booking is still active
    private static boolean countdownShown(Visitor v) {
        return Set.of("PENDING", "PENDING_MANUAL_REVIEW", "VERIFIED", "APPROVED", Visitor.STATUS_RESCHEDULE_REQUIRED,
                Visitor.STATUS_AWAITING_RECEIPT).contains(v.getStatus());
    }

    // 🔒 Why this ID photo is refused, or null: too large, HEIC, or bytes that aren't really a JPEG, PNG or WEBP image
    // (signature + full decode), whatever its name or the type the browser declared
    static String idPhotoError(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) return PHOTOS_REQUIRED;
        if (photo.getSize() > ID_PHOTO_MAX_BYTES) return ID_PHOTO_TOO_LARGE;
        try {
            byte[] bytes = photo.getBytes();
            if (com.paf.aerovisit.service.ReceiptStorage.isHeic(bytes)) return ID_PHOTO_HEIC;
            return com.paf.aerovisit.service.ReceiptStorage.realImageFormat(bytes,
                    com.paf.aerovisit.service.ReceiptStorage.ID_PHOTO_FORMATS) != null ? null : ID_PHOTO_NOT_IMAGE;
        } catch (java.io.IOException e) {
            return ID_PHOTO_NOT_IMAGE;
        }
    }

    private static String visitorStatusLabel(String status) {
        if (status == null) return "Pending Review";
        return switch (status) {
            case "APPROVED" -> "Approved";
            case "CHECKED-IN" -> "Checked In";
            case "CHECKED-OUT" -> "Visit Completed";
            case "REJECTED" -> "Not Approved";
            case "CANCELLED_OVERRIDE" -> "Cancelled by Museum";
            case "CANCELLED_BY_VISITOR" -> "Cancelled";
            case "CANCELLED" -> "Cancelled by Museum"; // emergency closure
            case "DENIED_ENTRY" -> "Entry Denied";
            case "NO_SHOW" -> "Missed Visit";
            case Visitor.STATUS_PAYMENT_EXPIRED -> "Payment Expired";
            case Visitor.STATUS_RESCHEDULE_REQUIRED -> "Reschedule Needed";
            case Visitor.STATUS_AWAITING_RECEIPT -> "Awaiting Payment";
            default -> "Pending Review"; // PENDING, PENDING_MANUAL_REVIEW, VERIFIED
        };
    }

    // CSS modifier for the status badge: ok (green), warn (red), muted (grey), pending (gold default)
    private static String visitorStatusTone(String status) {
        if (status == null) return "pending";
        return switch (status) {
            case "APPROVED", "CHECKED-IN" -> "ok";
            case "REJECTED", "CANCELLED_OVERRIDE" -> "warn";
            case "CHECKED-OUT", "CANCELLED_BY_VISITOR", "NO_SHOW" -> "muted";
            case "CANCELLED", "DENIED_ENTRY", Visitor.STATUS_PAYMENT_EXPIRED -> "warn";
            default -> "pending"; // includes RESCHEDULE_REQUIRED: the visitor has something to do
        };
    }

    // Seats already held (approved or admitted) in one time window, leaving out one booking (the visitor's own)
    private static int seatsTaken(List<Visitor> bookingsOnDate, String timeWindow, Long excludeVisitorId) {
        int taken = 0;
        for (Visitor o : bookingsOnDate) {
            if (!o.getId().equals(excludeVisitorId) && o.holdsSeat()
                    && o.getTimeWindow() != null && o.getTimeWindow().equals(timeWindow)) {
                taken += Math.max(1, o.getGroupSize());
            }
        }
        return taken;
    }

    // Upcoming public slots with enough seats for this visitor's group, for the reschedule picker
    // 📅 The earliest date an advance booking (or a move to a new date) may be for, Manila time
    private LocalDate advanceEarliest() {
        return advanceBookingRules == null ? com.paf.aerovisit.service.AdvanceBookingRules.fallbackEarliest() : advanceBookingRules.earliestDate();
    }

    private String advanceTooSoonMessage() {
        return com.paf.aerovisit.service.AdvanceBookingRules.tooSoonMessage(advanceBookingRules == null
                ? com.paf.aerovisit.model.PortalSettings.DEFAULT_ADVANCE_MIN_DAYS : advanceBookingRules.minDays());
    }

    private List<Map<String, Object>> openSlotsFor(Visitor v) {
        LocalDate today = today();
        LocalDate earliest = advanceEarliest(); // 📅 the minimum days ahead, like a new advance booking (never today)
        Set<LocalDate> closureDates = new HashSet<>(repo.findClosureDates());
        Map<String, Integer> capacityBySlot = new java.util.TreeMap<>(); // "date|window", sorted by date
        List<BookingSlot> slots = bookingRepo.findAll();
        // 🎌 Listed holidays are not offered
        Set<LocalDate> holidayDates = holidays == null ? Set.of() : holidays.between(today,
                slots.stream().map(BookingSlot::getSlotDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(today)).keySet();
        for (BookingSlot s : slots) {
            if (s.getSlotDate() != null && s.getTimeWindow() != null && s.getSlotDate().isAfter(today) && !closureDates.contains(s.getSlotDate())
                    && !holidayDates.contains(s.getSlotDate()) && !s.getSlotDate().isBefore(earliest)) {
                capacityBySlot.merge(s.getSlotDate() + "|" + s.getTimeWindow(), s.getMaxCapacity(), Integer::sum);
            }
        }
        Map<LocalDate, List<Visitor>> bookingsByDate = new HashMap<>();
        List<Map<String, Object>> open = new ArrayList<>();
        for (Map.Entry<String, Integer> e : capacityBySlot.entrySet()) {
            int sep = e.getKey().indexOf('|');
            LocalDate date = LocalDate.parse(e.getKey().substring(0, sep));
            String window = e.getKey().substring(sep + 1);
            if (date.equals(v.getVisitDate()) && window.equals(v.getTimeWindow())) continue; // the current slot
            int remaining = e.getValue() - seatsTaken(bookingsByDate.computeIfAbsent(date, repo::findByVisitDate), window, v.getId());
            if (remaining >= Math.max(1, v.getGroupSize())) {
                Map<String, Object> option = new HashMap<>();
                option.put("value", e.getKey());
                option.put("label", date + " · " + window + " (" + remaining + " seats left)");
                option.put("date", date.toString()); // read by the date picker + time window dropdown
                option.put("window", window);
                option.put("remaining", remaining);
                open.add(option);
            }
        }
        return open;
    }

    // 📝 Feedback is open once the visit is over: checked out, or still CHECKED-IN (they left without being checked out)
    // once their feedback email went out after the time window. Someone still inside can't review early with their QR token.
    static boolean feedbackOpen(Visitor v) {
        return "CHECKED-OUT".equals(v.getStatus())
                || ("CHECKED-IN".equals(v.getStatus()) && v.getFeedbackEmailSentAt() != null);
    }

    @GetMapping("/leave-feedback")
    public String showFeedbackPage(@RequestParam(name = "token", required = false) String token, Model model) {
        Visitor v = (token == null || token.isBlank()) ? null : repo.findBySecureToken(token).orElse(null);
        if (v == null || !feedbackOpen(v)) {
            model.addAttribute("error", "Invalid link or your visit has not been completed yet.");
            return "feedback-error"; 
        }
        if (v.getRating() != null) {
            model.addAttribute("error", "You have already submitted an After-Action Report for this visit. Thank you!");
            return "feedback-error"; 
        }
        model.addAttribute("visitor", v);
        return "feedback"; 
    }

    @PostMapping("/submit-feedback")
    public String submitFeedback(@RequestParam("token") String token, @RequestParam("rating") Integer rating, @RequestParam("feedback") String feedback,
                                 @RequestParam(name = "zone", required = false) String zone, Model model) {
        Visitor v = repo.findBySecureToken(token).orElse(null);
        // 🔒 Same rules as the form page, enforced on submit too (a direct POST can't skip them)
        if (v == null || !feedbackOpen(v)) {
            model.addAttribute("error", "Invalid link or your visit has not been completed yet.");
            return "feedback-error";
        }
        if (v.getRating() != null) {
            model.addAttribute("error", "You have already submitted an After-Action Report for this visit. Thank you!");
            return "feedback-error";
        }
        if (rating == null || rating < 1 || rating > 5) {
            model.addAttribute("error", "Invalid rating. Please choose between 1 and 5 stars.");
            return "feedback-error";
        }
        if (feedback != null && feedback.length() > 500) {
            feedback = feedback.substring(0, 500); // matches the 500-char database column
        }

        v.setRating(rating);
        v.setFeedback(feedback);
        v.setZoneName(resolveZone(zone));
        repo.save(v);

        try {
            org.json.JSONObject aiAnalysis = aiService.analyzeFeedbackSentiment(v.getFeedback());

            // Root Cause Matrix: keep the topic + sentiment label so complaints can be grouped later
            if (aiAnalysis != null && aiAnalysis.has("topic_category")) {
                v.setTopicCategory(aiAnalysis.optString("topic_category", null));
                v.setFeedbackSentiment(aiAnalysis.optString("sentiment", null));
                repo.save(v);
                if (notificationService != null) {
                    notificationService.negativeFeedback(v); // only a NEGATIVE label; never throws
                }
            }

            // 📬 Critical alerts go to the system alert inbox (aerovisit.alerts.email), never an address in the code
            if (aiAnalysis != null && aiAnalysis.getBoolean("critical_alert") && alertEmail != null && !alertEmail.isBlank()) {
                String inbox = alertEmail, name = v.getName(), text = v.getFeedback();
                double score = aiAnalysis.getDouble("score");
                com.paf.aerovisit.service.MailOutbox.send(mailOutbox, "critical-feedback alert",
                        () -> emailService.sendCriticalFeedbackAlert(inbox, name, text, score), null);
            }
        } catch (Exception e) {
            System.err.println("NLP Engine skipped: " + e.getMessage());
        }

        model.addAttribute("success", "Your After-Action Report has been securely transmitted to the Museum Director. Thank you!");
        return "feedback-success";
    }

    // 💱 The admission prices in force now (the defaults in test slices without AdmissionPricing)
    private AdmissionPricing.Prices currentPrices() {
        return admissionPricing == null ? AdmissionPricing.Prices.DEFAULT : admissionPricing.current();
    }

    // Writes a priced set of counts onto the booking: the three counts, groupSize = their sum, the unit prices used and the total
    private static void applyAdmission(Visitor v, AdmissionPricing.Quote quote) {
        v.setDiscountedCount(quote.discounted());
        v.setRegularCount(quote.regular());
        v.setFreeCount(quote.free());
        v.setGroupSize(quote.people());
        v.setDiscountedPriceCentavos(quote.prices().discounted());
        v.setRegularPriceCentavos(quote.prices().regular());
        v.setAdmissionTotalCentavos(quote.totalCentavos());
    }

    private static int orZero(Integer count) { return count == null ? 0 : count; }

    // 🔒 RACE-SAFE SAVE: every booking is saved under a row lock on its slot with the seat count re-read inside
    // the lock (SeatReservationService), so two visitors can't both take the last seats. Web-layer test slices have no
    // SeatReservationService and save directly, as before.
    private Visitor saveWithinCapacity(Visitor visitor) {
        if (seatReservation == null) return repo.save(visitor);
        return seatReservation.saveIfSeatsRemain(visitor);
    }

    // True when the submitted nonce is the one this session's booking page was given. The session's nonce is removed
    // on every attempt, right or wrong, so each page load allows exactly one submission (and no guessing).
    static boolean consumeKycNonce(HttpSession session, String submitted) {
        if (session == null) return false;
        Object expected;
        // Read and remove in one step: two near-simultaneous submits (a double-click) can't both see the nonce
        synchronized (org.springframework.web.util.WebUtils.getSessionMutex(session)) {
            expected = session.getAttribute(KYC_NONCE);
            session.removeAttribute(KYC_NONCE);
        }
        return expected instanceof String value && submitted != null
                && java.security.MessageDigest.isEqual(value.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                                       submitted.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // Removes an ID photo saved for a booking that was then refused, so no orphaned photo stays on disk
    private void discardIdPhoto(String fileName) {
        if (fileName == null) return;
        try {
            Files.deleteIfExists(Paths.get("uploads").resolve(fileName));
        } catch (Exception e) {
            System.err.println("Could not remove refused booking's ID photo: " + e.getMessage());
        }
        if (uploadMirror != null) uploadMirror.deleteUpload(fileName); // ☁️ and its S3 copy
    }

    // ID photo file name: a random UUID plus the uploaded file's own extension (.jpg, .jpeg, .png or .webp only, so nothing
    // from the browser can steer the path). An unknown extension falls back to the checked content type.
    static String anonymousIdPhotoName(String originalFilename, String contentType) {
        String ext = "image/png".equals(contentType) ? "png" : "image/webp".equals(contentType) ? "webp" : "jpg";
        if (originalFilename != null) {
            int dot = originalFilename.lastIndexOf('.');
            String given = dot >= 0 ? originalFilename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
            if (given.equals("jpg") || given.equals("jpeg") || given.equals("png") || given.equals("webp")) ext = given;
        }
        return java.util.UUID.randomUUID() + "." + ext;
    }

    // Seats configured for a public booking on this date and time window; 0 when the slot does not exist or the date has passed
    private int bookableSlotCapacity(LocalDate date, String timeWindow) {
        if (date == null || timeWindow == null || date.isBefore(today())) return 0;
        return bookingRepo.findBySlotDateAndTimeWindow(date, timeWindow).stream()
                .mapToInt(BookingSlot::getMaxCapacity)
                .sum();
    }

    // Zone picked on the feedback form; only an active zone's name is kept, so a hand-made POST can't invent zones
    private String resolveZone(String zone) {
        if (zone == null || zone.isBlank() || zoneRepo == null) return null;
        return zoneRepo.findByNameIgnoreCase(zone.trim()).filter(MuseumZone::isActive).map(MuseumZone::getName).orElse(null);
    }
}
