package com.paf.aerovisit.controller;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.MuseumZone;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumZoneRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.FaceEncodings;
import com.paf.aerovisit.service.KycService;
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

    // 🪪 Selfie-with-ID check (Python OpenCV + existing Tesseract OCR). Optional; when missing, bookings go to manual review.
    @Autowired(required = false)
    private KycService kycService;

    // 🪪 Ban list for the identity checks that work without the AI service (IdentityBans). Optional, so web-layer test
    // slices without it still start; the face check itself runs in KycService.
    @Autowired(required = false)
    private com.paf.aerovisit.repository.BlacklistedFaceRepository faceBanRepo;

    // 📮 The "booking received" email and the critical-feedback alert go out in the background, so Submit answers at once
    // instead of waiting for the mail server. Optional: without it (test slices) they are sent right away (MailOutbox.send).
    @Autowired(required = false)
    private com.paf.aerovisit.service.MailOutbox mailOutbox;

    // ☁️ Copies the ID photos to S3 (off locally). Optional, so test slices without it still start.
    @Autowired(required = false)
    private com.paf.aerovisit.service.UploadMirror uploadMirror;

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
    // so a crafted POST cannot self-approve, overwrite another booking, or forge a QR token.
    @InitBinder("visitor")
    public void restrictVisitorFields(WebDataBinder binder) {
        binder.setAllowedFields(
            "visitDate", "timeWindow", "visitorType", "fullName", "sex", "age",
            "address", "affiliation", "email", "contactNumber", "specialConsideration",
            "groupSize", "purpose", "eventJustification", "companionNames"
        );
    }

    // 🪖 GROUP MANIFEST: null when the list names exactly everyone besides the lead booker, otherwise the error to show.
    // Letters, digits and . , ' ( ) / - only, so "Last Name, First Name MI" and AFP "Rank/Serial" formats both fit.
    private static String manifestError(List<String> companions, int groupSize) {
        int expected = Math.max(1, groupSize) - 1;
        if (companions.size() != expected) {
            return "Group Manifest: list exactly " + expected + " companion name(s), one per line (your group of "
                    + (expected + 1) + " includes you).";
        }
        for (String companion : companions) {
            if (!isValidCompanionName(companion)) {
                return "Group Manifest: names may only contain letters, numbers, spaces and . , ' ( ) / - (max "
                        + Visitor.MAX_COMPANION_NAME_LENGTH + " characters each).";
            }
        }
        return null;
    }

    // One guest name: letters, numbers, spaces and . , ' ( ) / - only, at most MAX_COMPANION_NAME_LENGTH characters.
    // Shared by manifestError (every booking) and the CSV import's row-by-row check, so the two can never disagree.
    static boolean isValidCompanionName(String name) {
        return name.length() <= Visitor.MAX_COMPANION_NAME_LENGTH && name.matches("[\\p{L}\\p{N} .,'()/-]+");
    }

    // Largest guest-list file accepted (a 2,000-name list is well under 100 KB)
    static final int MAX_MANIFEST_CSV_BYTES = 256 * 1024;
    // Header row of the downloadable template (static/downloads/guest-list-template.csv): one column per name part.
    // Detected by its first two cells; Middle Name may be missing or blank. register.html's csv check mirrors it.
    static final String NAME_PARTS_LAST = "last name", NAME_PARTS_FIRST = "first name";
    // Header of the previous single-column template; files saved from it still import (first column = whole name).
    // register.html's MANIFEST_HEADERS lists it too
    static final String MANIFEST_TEMPLATE_HEADER = "guest name (last, first mi)";
    private static final Set<String> MANIFEST_CSV_HEADERS = Set.of("name", "names", "full name", "fullname", "companion",
            "companion name", "companion names", "guest", "guest name", "guest names", MANIFEST_TEMPLATE_HEADER);

    // 🚌 MASTER BOOKING manifest: adds the Manual-row names and the names read from the CSV upload to `names`.
    // Returns an error to show, or null. The usual manifest check (exact count, allowed characters) runs afterwards.
    // Plain Java, no CSV library: one guest per row. A .csv whose first row is the template's Last Name / First Name /
    // Middle Name header is read as name parts, joined as "Last, First Middle". Any other .csv uses its first column
    // (Excel puts "Dela Cruz, Juan P." in quotes because of the comma); a .txt file uses the whole line. A first row
    // that is a header ("Name") is skipped.
    static final String EXCEL_WORKBOOK_MESSAGE = "Guest list upload: please choose a .csv file. Excel workbooks (.xlsx / .xls) are not read directly:"
            + " in Excel use File > Save As > CSV UTF-8, or start from the Download Template link.";

    static String addMasterManifestNames(List<String> names, List<String> manual, MultipartFile csv) {
        if (manual != null) {
            manual.stream().map(String::trim).filter(n -> !n.isEmpty()).forEach(names::add);
        }
        if (csv == null || csv.isEmpty()) return null;

        String fileName = csv.getOriginalFilename() == null ? "" : csv.getOriginalFilename().toLowerCase(java.util.Locale.ROOT);
        boolean isCsv = fileName.endsWith(".csv");
        if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
            return EXCEL_WORKBOOK_MESSAGE;
        }
        if (!isCsv && !fileName.endsWith(".txt")) {
            return "Guest list upload: please choose a .csv (or .txt) file with one name per row.";
        }
        if (csv.getSize() > MAX_MANIFEST_CSV_BYTES) {
            return "Guest list upload: the file is too large (max 256 KB).";
        }
        try {
            byte[] bytes = csv.getBytes();
            // An Excel workbook renamed to .csv: .xlsx is a ZIP ("PK"), .xls an OLE2 file. Refused before any row is read.
            if (bytes.length >= 4 && ((bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4)
                    || (bytes[0] == (byte) 0xD0 && bytes[1] == (byte) 0xCF && bytes[2] == 0x11 && bytes[3] == (byte) 0xE0))) {
                return EXCEL_WORKBOOK_MESSAGE;
            }
            // Row by row, all or nothing: the first bad row stops the import with its row number (as Excel numbers it,
            // header and blank rows included), and nothing from the file is kept
            List<String> fileNames = new ArrayList<>();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.StringReader(decodeManifest(bytes)));
            String line;
            int row = 0;
            boolean nameParts = false; // row 1 was the template's Last Name / First Name / Middle Name header
            while ((line = reader.readLine()) != null) {
                row++;
                if (row == 1 && isCsv) {
                    List<String> header = csvFields(line);
                    if (part(header, 0).equalsIgnoreCase(NAME_PARTS_LAST) && part(header, 1).equalsIgnoreCase(NAME_PARTS_FIRST)) {
                        nameParts = true;
                        continue;
                    }
                }
                String name;
                if (nameParts) {
                    List<String> parts = csvFields(line);
                    String last = part(parts, 0), first = part(parts, 1), middle = part(parts, 2); // missing cell = ""
                    if (last.isEmpty() && first.isEmpty() && middle.isEmpty()) continue;          // blank row (Excel saves ",,")
                    if (last.isEmpty() || first.isEmpty()) {
                        return "Guest list upload, row " + row + ": Last Name and First Name are both required (Middle Name may be left blank).";
                    }
                    name = last + ", " + first + (middle.isEmpty() ? "" : " " + middle);
                } else {
                    name = isCsv ? firstCsvField(line) : line.trim();
                    if (row == 1 && MANIFEST_CSV_HEADERS.contains(name.toLowerCase(java.util.Locale.ROOT))) continue;
                }
                if (name.isEmpty()) continue;
                if (name.length() > Visitor.MAX_COMPANION_NAME_LENGTH) {
                    return "Guest list upload, row " + row + ": the name is longer than " + Visitor.MAX_COMPANION_NAME_LENGTH + " characters.";
                }
                if (!isValidCompanionName(name)) {
                    return "Guest list upload, row " + row + ": \"" + name + "\" may only contain letters, numbers, spaces and . , ' ( ) / -";
                }
                fileNames.add(name);
            }
            if (fileNames.isEmpty()) {
                return "Guest list upload: no guest names were found in the file (one name per row, first column).";
            }
            names.addAll(fileNames);
        } catch (java.io.IOException e) {
            return "Guest list upload: the file could not be read. Please save it as CSV and try again.";
        }
        return null;
    }

    // UTF-8 (with or without Excel's byte-order mark); Excel's default "CSV" on Windows is Windows-1252, so names
    // like "Peña" are read correctly from either
    private static String decodeManifest(byte[] bytes) {
        String text;
        try {
            text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException notUtf8) {
            text = new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    // First field of one CSV row: "quoted, with commas" (doubled "" = one quote) or everything up to the first comma
    private static String firstCsvField(String line) {
        String row = line.trim();
        if (row.startsWith("\"")) {
            StringBuilder field = new StringBuilder();
            for (int i = 1; i < row.length(); i++) {
                char c = row.charAt(i);
                if (c == '"') {
                    if (i + 1 < row.length() && row.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else break;
                } else {
                    field.append(c);
                }
            }
            return field.toString().trim();
        }
        int comma = row.indexOf(',');
        return (comma < 0 ? row : row.substring(0, comma)).trim();
    }

    // Every field of one CSV row: commas separate fields; "quoted, with commas" keeps them (doubled "" = one quote)
    private static List<String> csvFields(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c != '"') field.append(c);
                else if (i + 1 < line.length() && line.charAt(i + 1) == '"') { field.append('"'); i++; }
                else quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == ',') { fields.add(field.toString().trim()); field.setLength(0); }
            else field.append(c);
        }
        fields.add(field.toString().trim());
        return fields;
    }

    // Cell i of a row, "" when the row is shorter (e.g. no Middle Name cell at all)
    private static String part(List<String> parts, int i) { return i < parts.size() ? parts.get(i) : ""; }

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

        List<BookingSlot> allSlots = bookingRepo.findAll();
        List<Visitor> allVisitors = repo.findAll();
        List<Map<String, Object>> slotDataList = new ArrayList<>();
        LocalDate today = LocalDate.now();
        Set<LocalDate> buyoutDates = new HashSet<>(repo.findBuyoutDates());
        Set<LocalDate> closureDates = new HashSet<>(repo.findClosureDates());
        
        for (BookingSlot slot : allSlots) {
            if (slot.getSlotDate() != null && !slot.getSlotDate().isBefore(today)) {
                
                int bookedCount = 0;
                for (Visitor v : allVisitors) {
                    if (v.getVisitDate() != null && v.getVisitDate().equals(slot.getSlotDate()) 
                        && v.getTimeWindow() != null && v.getTimeWindow().equals(slot.getTimeWindow())
                        && v.holdsSeat()) { // PENDING requests are on the waitlist and hold no seats
                        bookedCount += Math.max(1, v.getGroupSize());
                    }
                }
                
                int remaining = slot.getMaxCapacity() - bookedCount;
                if (remaining < 0) remaining = 0;
                if (buyoutDates.contains(slot.getSlotDate())) remaining = 0; // 🔒 VIP buyout: reserved for an approved Master Booking
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
        return "register";
    }

    @PostMapping("/save")
    public String saveVisitor(@ModelAttribute Visitor visitor,
                              @RequestParam("livenessImage") MultipartFile file,
                              @RequestParam("highResIdImage") MultipartFile idCardFile,
                              @RequestParam(name="isMasterBooking", defaultValue="false") boolean isMasterBooking,
                              @RequestParam(name="companionCsv", required=false) MultipartFile companionCsv,
                              @RequestParam(name=KYC_NONCE, required=false) String kycNonce,
                              HttpServletRequest request,
                              RedirectAttributes redirectAttributes) {

        // 🔑 ONE-TIME KYC NONCE first: checked against the session's value and burned whatever the outcome
        if (!consumeKycNonce(request.getSession(false), kycNonce)) {
            redirectAttributes.addFlashAttribute("error", KYC_NONCE_MESSAGE);
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

        // 🚨 Emergency closure: nobody (Master Bookings included) may book a date the museum has closed
        if (visitor.getVisitDate() != null && repo.findClosureDates().contains(visitor.getVisitDate())) {
            redirectAttributes.addFlashAttribute("error", "The museum is closed on this date due to an emergency. Please choose another date.");
            return "redirect:/";
        }

        // 📧 EMAIL OTP: the booking email must have been verified with its 6-digit code (POST /otp/verify). Fails closed.
        if (otpService == null || !otpService.isVerified(visitor.getEmail())) {
            redirectAttributes.addFlashAttribute("error", "Security Error: Please verify your email address with the 6-digit code we sent before submitting.");
            return "redirect:/";
        }

        // 🪪 IDENTITY BAN (works with AI features off): the same email or phone number as an active ban is refused with
        // the same neutral message as a banned face. A new email beats this check, so banned visitors are also stopped by
        // their face in the two-step KYC below (AI on) and flagged by name (after the KYC).
        List<com.paf.aerovisit.model.BlacklistedFace> bans = faceBanRepo == null ? List.of() : faceBanRepo.findAll();
        if (com.paf.aerovisit.service.IdentityBans.contactBan(visitor, bans, LocalDate.now()) != null) {
            if (notificationService != null) notificationService.bannedContactAttempt(visitor);
            redirectAttributes.addFlashAttribute("error", KycService.FACE_BANNED);
            return "redirect:/";
        }

        List<Visitor> existingBookings = repo.findByEmailAndVisitDate(visitor.getEmail(), visitor.getVisitDate());
        // Visitors displaced by a VIP buyout or an emergency closure (once reopened), or who cancelled themselves, may re-book the same date
        existingBookings.removeIf(b -> "CANCELLED_OVERRIDE".equals(b.getStatus()) || "CANCELLED_BY_VISITOR".equals(b.getStatus()) || "CANCELLED".equals(b.getStatus()));
        // ⏳ A REJECTED application no longer blocks the date for good: the visitor may re-apply after the cooldown (below)
        List<Visitor> rejectedBookings = existingBookings.stream().filter(b -> "REJECTED".equals(b.getStatus())).toList();
        existingBookings.removeIf(b -> "REJECTED".equals(b.getStatus()));
        if (!existingBookings.isEmpty()) {
            redirectAttributes.addFlashAttribute("error", "Registration Denied: Duplicate booking detected for this date.");
            return "redirect:/";
        }
        if (inRejectedCooldown(rejectedBookings, java.time.LocalDateTime.now())) {
            redirectAttributes.addFlashAttribute("error", REJECTED_COOLDOWN_MESSAGE);
            return "redirect:/";
        }

        // 🔒 VIP buyout: dates reserved by an approved Master Booking are closed to regular bookings
        if (!isMasterBooking && repo.findBuyoutDates().contains(visitor.getVisitDate())) {
            redirectAttributes.addFlashAttribute("error", "This date has been reserved for a private group tour. Please choose another date.");
            return "redirect:/";
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
        // made up in a hand-made request) is refused. Master Bookings may take a day with no public slots (VIP buyout).
        if (!isMasterBooking && actualMaxCapacity <= 0) {
            redirectAttributes.addFlashAttribute("error", "Invalid or unavailable time slot. Please choose an open date and time from the calendar.");
            return "redirect:/";
        }

        int incomingGroupSize = Math.max(1, visitor.getGroupSize());
        
        if (!isMasterBooking && (currentBooked + incomingGroupSize > actualMaxCapacity)) {
            redirectAttributes.addFlashAttribute("error", "Insufficient slots available for this specific shift! Please choose another slot.");
            return "redirect:/";
        }

        if (isMasterBooking) {
            if (visitor.getEventJustification() == null || visitor.getEventJustification().isBlank()) {
                redirectAttributes.addFlashAttribute("error", "Master Booking requests require an Official Event Purpose / Justification.");
                return "redirect:/";
            }
            visitor.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        } else if (Visitor.MASTER_BOOKING_PURPOSE.equals(visitor.getPurpose())) {
            // 🔒 Only the Master Booking flow may carry this purpose, otherwise a regular booking could lock a whole date
            visitor.setPurpose("Educational Tour");
        }

        // 🪖 GROUP MANIFEST: every guest besides the lead booker must be named so the gate can check each ID
        if (Math.max(1, visitor.getGroupSize()) == 1) {
            visitor.setCompanionNames(null); // leftover text from the hidden field is ignored
        } else {
            List<String> companions = Visitor.parseCompanionNames(visitor.getCompanionNames());
            if (isMasterBooking) {
                // 🚌 MASTER BOOKING ONLY: names from the Manual rows and the CSV upload join the pasted list.
                // A standard booking never reads these two inputs.
                companions = new ArrayList<>(companions);
                // Raw values, not @RequestParam List<String>: Spring would split a single "Dela Cruz, Juan P." at the comma
                String[] manualRows = request.getParameterValues("companionManual");
                String importError = addMasterManifestNames(companions, manualRows == null ? null : List.of(manualRows), companionCsv);
                if (importError != null) {
                    redirectAttributes.addFlashAttribute("error", importError);
                    return "redirect:/";
                }
            }
            String manifestError = manifestError(companions, visitor.getGroupSize());
            if (manifestError != null) {
                redirectAttributes.addFlashAttribute("error", manifestError);
                return "redirect:/";
            }
            visitor.setCompanionNames(String.join("\n", companions)); // store the cleaned list
        }

        if (visitor.getStatus() == null) visitor.setStatus("PENDING");

        if (!file.isEmpty() || !idCardFile.isEmpty()) {
            for (MultipartFile photo : List.of(file, idCardFile)) {
                String type = photo.getContentType();
                if (!photo.isEmpty() && (type == null || (!type.equals("image/jpeg") && !type.equals("image/png")))) {
                    redirectAttributes.addFlashAttribute("error", "Security Alert: System only accepts standard .JPG or .PNG formats for ID verification.");
                    return "redirect:/";
                }
            }

            try {
                Path uploadPath = Paths.get("uploads");
                if (!Files.exists(uploadPath)) Files.createDirectories(uploadPath);
                // 🔒 PRIVACY: anonymous UUID names, so the visitor's name never appears in the file system or /uploads URLs.
                // The liveness selfie stays in idImageUrl (admin "View Document", gate scanner); the ID close-up is separate.
                if (!file.isEmpty()) {
                    String fileName = anonymousIdPhotoName(file.getOriginalFilename(), file.getContentType());
                    Files.copy(file.getInputStream(), uploadPath.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                    visitor.setIdImageUrl(fileName);
                    if (uploadMirror != null) uploadMirror.pushUpload(fileName);
                }
                if (!idCardFile.isEmpty()) {
                    String cardName = anonymousIdPhotoName(idCardFile.getOriginalFilename(), idCardFile.getContentType());
                    Files.copy(idCardFile.getInputStream(), uploadPath.resolve(cardName), StandardCopyOption.REPLACE_EXISTING);
                    visitor.setIdCardImageUrl(cardName);
                    if (uploadMirror != null) uploadMirror.pushUpload(cardName);
                }

                // 🪪 TWO-STEP KYC (ZERO TRUST: Master Bookings included, their lead booker is screened like anyone else):
                // ID portrait vs live face (1:1), the biometric blacklist, then the OCR name match on the ID close-up.
                // Both photos are needed; a missing one, the AI service being offline or a name mismatch =
                // PENDING_MANUAL_REVIEW (so is a blurry photo or a score just under the match line). A banned face is
                // refused outright; a clear face mismatch is stored as REJECTED (saveRejectedAttempt).
                KycService.KycResult kyc = (kycService == null || file.isEmpty() || idCardFile.isEmpty())
                        ? new KycService.KycResult(KycService.MANUAL_REVIEW, "INCOMPLETE")
                        : kycService.screen(idCardFile.getBytes(), file.getBytes(), visitor.getFirstName(), visitor.getLastName());
                if (kyc.refused()) {
                    // Nothing is saved and the email code stays valid, so the visitor can retake the photos and resubmit
                    if (notificationService != null && "FACE_BANNED".equals(kyc.reason())) notificationService.bannedFaceAttempt(visitor);
                    discardIdPhoto(visitor.getIdImageUrl());
                    discardIdPhoto(visitor.getIdCardImageUrl());
                    redirectAttributes.addFlashAttribute("error", kyc.message());
                    return "redirect:/";
                }
                if (kyc.rejected()) {
                    return saveRejectedAttempt(visitor, kyc, redirectAttributes);
                }
                // 🧬 ONE PERSON, ONE BOOKING PER DATE: the same face under another email. A near-certain match is refused
                // (nothing saved, the email code stays valid); a borderline one is saved for the Director, tagged with the
                // other booking, so a look-alike (sibling, parent and child) is never turned away automatically.
                DuplicateFace duplicate = duplicateFace(FaceEncodings.parse(kyc.faceEncoding()), repo.findByVisitDate(visitor.getVisitDate()));
                if (duplicate != null && duplicate.refuse()) {
                    discardIdPhoto(visitor.getIdImageUrl());
                    discardIdPhoto(visitor.getIdCardImageUrl());
                    redirectAttributes.addFlashAttribute("error", DUPLICATE_FACE_MESSAGE);
                    return "redirect:/";
                }
                visitor.setStatus(kyc.status());
                visitor.setFaceEncoding(kyc.faceEncoding());
                visitor.setOcrNameMatch(kyc.ocrNameMatch());
                if (duplicate != null) {
                    visitor.setStatus(KycService.MANUAL_REVIEW);
                    visitor.setPossibleDuplicateOf(duplicate.referenceId());
                }
            } catch (Exception e) {
                System.out.println("File or OCR Processing failed: " + e.getMessage());
                visitor.setStatus("PENDING_MANUAL_REVIEW");
            }
        } else {
            // Zero trust: a request with no photos at all (only possible by bypassing the form) never looks like a passed check
            visitor.setStatus(KycService.MANUAL_REVIEW);
        }

        // 🪪 NAME FLAG (works with AI features off): the lead booker's or a companion's name resembles a banned name.
        // Never refused (people share names): the booking waits for staff, tagged with the ban to compare photos against.
        com.paf.aerovisit.model.BlacklistedFace nameBan = com.paf.aerovisit.service.IdentityBans.nameBan(
                com.paf.aerovisit.service.IdentityBans.namesOf(visitor), bans, LocalDate.now());
        if (nameBan != null) {
            visitor.setStatus(KycService.MANUAL_REVIEW);
            String match = "Ban #" + nameBan.getId() + ": " + nameBan.getName();
            visitor.setPossibleBanMatch(match.length() > 300 ? match.substring(0, 300) : match);
        }

        // Generate a unique Booking Reference ID (always server-side, never trusted from the form)
        String referenceId;
        do {
            referenceId = ReferenceIdGenerator.generate();
        } while (repo.existsByReferenceId(referenceId));
        visitor.setReferenceId(referenceId);

        Visitor savedVisitor;
        try {
            savedVisitor = saveWithinCapacity(visitor, isMasterBooking);
        } catch (SeatReservationService.CapacityFullException full) {
            // 🔒 Another booking took the last seats between the check above and this save
            discardIdPhoto(visitor.getIdImageUrl());
            discardIdPhoto(visitor.getIdCardImageUrl());
            redirectAttributes.addFlashAttribute("error", "Insufficient slots available for this specific shift! Please choose another slot.");
            return "redirect:/";
        } catch (org.springframework.dao.PessimisticLockingFailureException busy) {
            discardIdPhoto(visitor.getIdImageUrl());
            discardIdPhoto(visitor.getIdCardImageUrl());
            redirectAttributes.addFlashAttribute("error", "This time slot is being booked by other visitors right now. Please try again in a moment.");
            return "redirect:/";
        }
        otpService.consume(visitor.getEmail()); // one verified code = one booking
        redirectAttributes.addFlashAttribute("visitor", savedVisitor);
        if (notificationService != null) {
            notificationService.bookingRequest(savedVisitor); // only bookings waiting for approval; never throws
        }

        // HUMAN-IN-THE-LOOP: every booking waits for the Director (KycService never approves), so the visitor is told
        // it is pending; the QR code is only made and emailed at approval (GuardOperationController.approveVisitor)
        String to = savedVisitor.getEmail(), name = savedVisitor.getName(), date = savedVisitor.getVisitDate().toString(),
                ref = savedVisitor.getReferenceId();
        boolean master = isMasterBooking;
        com.paf.aerovisit.service.MailOutbox.send(mailOutbox, "booking-received email for " + ref,
                () -> emailService.sendPendingNotification(to, name, date, master, ref), null);

        return "redirect:/success-page"; 
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
    private static final String MANAGE_VISITOR_ID = "manageVisitorId";
    private static final String MANAGE_FORM_TOKEN = "manageFormToken";

    // Statuses that no longer hold a seat (kept in step with the capacity sums in showForm and saveVisitor)
    private static final Set<String> SEAT_FREE_STATUSES = Set.of("REJECTED", "CANCELLED_OVERRIDE", "CANCELLED_BY_VISITOR", "CANCELLED");

    // 🛑 CLEAR FACE MISMATCH (likely a spoof): stored as REJECTED so the Director sees the attempt with its photos and
    // face vector in the Entire Database (and can ban that face). No seat, no "pending" email, no success page. The
    // email code is used up, and the usual re-apply cooldown (below) for this email and date applies.
    private String saveRejectedAttempt(Visitor visitor, KycService.KycResult kyc, RedirectAttributes redirectAttributes) {
        String referenceId;
        do {
            referenceId = ReferenceIdGenerator.generate();
        } while (repo.existsByReferenceId(referenceId));
        visitor.setReferenceId(referenceId);
        visitor.setStatus("REJECTED");
        visitor.setRejectionReason("Automatic KYC: live face does not match the ID portrait");
        visitor.setFaceEncoding(kyc.faceEncoding());
        visitor.setOcrNameMatch(false);
        repo.save(visitor);
        otpService.consume(visitor.getEmail());
        redirectAttributes.addFlashAttribute("error", kyc.message());
        return "redirect:/";
    }

    // Re-applying for a date after a rejection: allowed once this many hours have passed since the newest REJECTED
    // application for that email + date (timed from its createdAt, i.e. when it was submitted)
    // 🧬 Same-face duplicate check (one person, one booking per date, whatever the email). At or above REFUSE the two faces
    // are near-certainly one person; from FaceEncodings.SAME_PERSON_COSINE (0.363, the 1:1 ID-vs-selfie line) up to it they
    // may only look alike, and comparing against a whole day of bookings makes such look-alikes likelier, so the Director decides.
    static final double DUPLICATE_FACE_REFUSE_COSINE = 0.55;
    static final String DUPLICATE_FACE_MESSAGE = "You already have a booking for this date. Please manage it with your Booking Reference ID instead of booking again.";
    // Bookings that don't hold the date for their face, the same ones the email duplicate rule lets a visitor re-book over
    static final Set<String> FACE_FREE_STATUSES = Set.of("CANCELLED_OVERRIDE", "CANCELLED_BY_VISITOR", "CANCELLED", "REJECTED");

    record DuplicateFace(String referenceId, double score, boolean refuse) {}

    // The closest booking on the same date whose face is at least SAME_PERSON_COSINE alike, or null. No face for the new
    // booking (AI service down, no face found) or none on file for the others = no match; those go to manual review anyway.
    static DuplicateFace duplicateFace(double[] face, List<Visitor> sameDay) {
        if (face == null || sameDay == null) return null;
        Visitor closest = null;
        double best = -1;
        for (Visitor other : sameDay) {
            if (other.getStatus() == null || FACE_FREE_STATUSES.contains(other.getStatus())) continue;
            double[] otherFace = FaceEncodings.parse(other.getFaceEncoding());
            if (otherFace == null) continue;
            double score = FaceEncodings.cosine(face, otherFace);
            if (score > best) {
                best = score;
                closest = other;
            }
        }
        if (closest == null || best < FaceEncodings.SAME_PERSON_COSINE) return null;
        System.out.println("🧬 Same-face booking on this date: similarity=" + String.format(java.util.Locale.ROOT, "%.3f", best)
                + " with " + closest.getReferenceId() + (best >= DUPLICATE_FACE_REFUSE_COSINE ? " -> refused" : " -> manual review"));
        return new DuplicateFace(closest.getReferenceId(), best, best >= DUPLICATE_FACE_REFUSE_COSINE);
    }

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

        boolean upcoming = v.getVisitDate() != null && !v.getVisitDate().isBefore(LocalDate.now());
        model.addAttribute("booking", v);
        model.addAttribute("formToken", session.getAttribute(MANAGE_FORM_TOKEN));
        model.addAttribute("statusLabel", visitorStatusLabel(v.getStatus()));
        model.addAttribute("statusTone", visitorStatusTone(v.getStatus()));
        model.addAttribute("editable", upcoming && isEditableByVisitor(v));
        model.addAttribute("cancellable", upcoming && isCancellableByVisitor(v));
        model.addAttribute("showCountdown", upcoming && isCancellableByVisitor(v));
        if (upcoming && isEditableByVisitor(v)) {
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
        request.getSession(true);
        request.changeSessionId();
        HttpSession session = request.getSession(false);
        session.setAttribute(MANAGE_VISITOR_ID, v.getId());
        session.setAttribute(MANAGE_FORM_TOKEN, java.util.UUID.randomUUID().toString());
        return "redirect:/manage-booking";
    }

    @PostMapping("/manage-booking/update")
    public String updateManagedBooking(@RequestParam(name = "formToken", required = false) String formToken,
                                       @RequestParam(name = "slot", required = false) String slot,
                                       @RequestParam(name = "visitDate", required = false) String visitDate,
                                       @RequestParam(name = "timeWindow", required = false) String timeWindow,
                                       @RequestParam(name = "groupSize", required = false) Integer groupSize,
                                       @RequestParam(name = "contactNumber", required = false) String contactNumber,
                                       @RequestParam(name = "companionNames", required = false) String companionNames,
                                       HttpSession session, RedirectAttributes redirectAttributes) {
        Visitor v = manageSessionVisitor(session, formToken);
        if (v == null) {
            redirectAttributes.addFlashAttribute("error", "Your session has expired. Please sign in again.");
            return "redirect:/manage-booking";
        }
        if (v.getVisitDate() == null || v.getVisitDate().isBefore(LocalDate.now()) || !isEditableByVisitor(v)) {
            redirectAttributes.addFlashAttribute("error", "This booking can no longer be changed online.");
            return "redirect:/manage-booking";
        }

        // Two-part form (date picker + time window dropdown); the single "date|window" slot value still works
        if ((slot == null || slot.isBlank()) && visitDate != null && !visitDate.isBlank()) {
            if (timeWindow == null || timeWindow.isBlank()) {
                redirectAttributes.addFlashAttribute("error", "Please choose a time window for your selected date.");
                return "redirect:/manage-booking";
            }
            slot = visitDate.trim() + "|" + timeWindow;
        }

        // Blank slot = keep the current date and time window
        LocalDate newDate = v.getVisitDate();
        String newWindow = v.getTimeWindow();
        if (slot != null && !slot.isBlank()) {
            int sep = slot.indexOf('|');
            try {
                if (sep < 0) throw new DateTimeParseException("missing separator", slot, 0);
                newDate = LocalDate.parse(slot.substring(0, sep));
                newWindow = slot.substring(sep + 1);
            } catch (DateTimeParseException e) {
                redirectAttributes.addFlashAttribute("error", "Please choose a valid date and time from the list.");
                return "redirect:/manage-booking";
            }
        }
        int newSize = (groupSize != null) ? groupSize : v.getGroupSize();
        if (newSize < 1) {
            redirectAttributes.addFlashAttribute("error", "Total visitors must be at least 1.");
            return "redirect:/manage-booking";
        }
        String newContact = (contactNumber == null || contactNumber.isBlank()) ? v.getContactNumber() : contactNumber.trim();
        if (newContact != null && newContact.length() > 30) {
            redirectAttributes.addFlashAttribute("error", "Contact number is too long.");
            return "redirect:/manage-booking";
        }
        // 🪖 GROUP MANIFEST: a bigger party (or an edited guest list) must still name every guest besides the lead booker,
        // so a solo booking cannot be grown into an unregistered group after the fact
        String newCompanions = v.getCompanionNames();
        if (newSize == 1) {
            newCompanions = null; // leftover text from the hidden field is ignored
        } else if (companionNames != null || newSize != v.getGroupSize()) {
            List<String> companions = Visitor.parseCompanionNames(companionNames != null ? companionNames : v.getCompanionNames());
            String manifestError = manifestError(companions, newSize);
            if (manifestError != null) {
                redirectAttributes.addFlashAttribute("error", manifestError);
                return "redirect:/manage-booking";
            }
            newCompanions = String.join("\n", companions);
        }

        boolean scheduleChanged = !Objects.equals(newDate, v.getVisitDate())
                || !Objects.equals(newWindow, v.getTimeWindow())
                || newSize != v.getGroupSize();
        boolean contactChanged = !Objects.equals(newContact, v.getContactNumber());
        boolean manifestChanged = !Objects.equals(newCompanions, v.getCompanionNames());
        if (!scheduleChanged && !contactChanged && !manifestChanged) {
            redirectAttributes.addFlashAttribute("success", "No changes to save.");
            return "redirect:/manage-booking";
        }

        if (scheduleChanged) {
            final LocalDate date = newDate;
            final String window = newWindow;
            if (date.isBefore(LocalDate.now())) {
                redirectAttributes.addFlashAttribute("error", "Please choose a date that has not passed.");
                return "redirect:/manage-booking";
            }
            // 🔒 VIP buyout: dates reserved by an approved Master Booking are closed to regular bookings
            if (repo.findBuyoutDates().contains(date)) {
                redirectAttributes.addFlashAttribute("error", "This date has been reserved for a private group tour. Please choose another date.");
                return "redirect:/manage-booking";
            }
            // 🚨 Emergency closure: the museum is closed that day
            if (repo.findClosureDates().contains(date)) {
                redirectAttributes.addFlashAttribute("error", "The museum is closed on this date due to an emergency. Please choose another date.");
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
            // Own seats are left out, so keeping the same slot and changing only the group size works
            int taken = seatsTaken(repo.findByVisitDate(date), window, v.getId());
            if (taken + newSize > capacity) {
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
        }

        boolean wasApproved = "APPROVED".equals(v.getStatus());
        v.setVisitDate(newDate);
        v.setTimeWindow(newWindow);
        v.setGroupSize(newSize);
        v.setContactNumber(newContact);
        v.setCompanionNames(newCompanions);
        if ((scheduleChanged || manifestChanged) && wasApproved) {
            // The admin re-reviews the new schedule or guest list; the old QR stops working until it is approved again
            v.setStatus("PENDING");
            v.setQrCodeUrl(null);
            v.setReminderSent(false);
        }
        repo.save(v);
        if (notificationService != null && (scheduleChanged || manifestChanged) && v.awaitsApproval()) {
            notificationService.bookingRescheduled(v); // never throws
        }

        String successMessage = "Booking updated.";
        if (scheduleChanged && wasApproved) {
            successMessage = "Booking updated. Your new schedule has been sent back to the museum for approval, and your previous QR code is no longer valid.";
        } else if (manifestChanged && wasApproved) {
            successMessage = "Booking updated. Your new guest list has been sent back to the museum for approval, and your previous QR code is no longer valid.";
        }
        redirectAttributes.addFlashAttribute("success", successMessage);
        return "redirect:/manage-booking";
    }

    @PostMapping("/manage-booking/cancel")
    public String cancelManagedBooking(@RequestParam(name = "formToken", required = false) String formToken,
                                       HttpSession session, RedirectAttributes redirectAttributes) {
        Visitor v = manageSessionVisitor(session, formToken);
        if (v == null) {
            redirectAttributes.addFlashAttribute("error", "Your session has expired. Please sign in again.");
            return "redirect:/manage-booking";
        }
        if (v.getVisitDate() == null || v.getVisitDate().isBefore(LocalDate.now()) || !isCancellableByVisitor(v)) {
            redirectAttributes.addFlashAttribute("error", "This booking can no longer be cancelled online.");
            return "redirect:/manage-booking";
        }
        v.setStatus("CANCELLED_BY_VISITOR");
        v.setQrCodeUrl(null);
        repo.save(v);
        if (notificationService != null) notificationService.bookingCancelled(v); // never throws
        redirectAttributes.addFlashAttribute("success", "Your visit has been cancelled and your seats have been released. You are welcome to book again anytime.");
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

    // Visitors may change date, time, group size and contact number while PENDING or APPROVED.
    // Master Bookings are arranged with the museum directly, so they can only be cancelled here.
    private static boolean isEditableByVisitor(Visitor v) {
        return ("PENDING".equals(v.getStatus()) || "APPROVED".equals(v.getStatus())) && !v.isMasterBooking();
    }

    private static boolean isCancellableByVisitor(Visitor v) {
        return Set.of("PENDING", "PENDING_MANUAL_REVIEW", "VERIFIED", "APPROVED").contains(v.getStatus());
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
            case "CANCELLED", "DENIED_ENTRY" -> "warn";
            default -> "pending";
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

    // Upcoming public slots with enough seats for this visitor's group, for the edit form's dropdown
    private List<Map<String, Object>> openSlotsFor(Visitor v) {
        LocalDate today = LocalDate.now();
        Set<LocalDate> buyoutDates = new HashSet<>(repo.findBuyoutDates());
        Set<LocalDate> closureDates = new HashSet<>(repo.findClosureDates());
        Map<String, Integer> capacityBySlot = new java.util.TreeMap<>(); // "date|window", sorted by date
        for (BookingSlot s : bookingRepo.findAll()) {
            if (s.getSlotDate() != null && s.getTimeWindow() != null && !s.getSlotDate().isBefore(today) && !buyoutDates.contains(s.getSlotDate()) && !closureDates.contains(s.getSlotDate())) {
                capacityBySlot.merge(s.getSlotDate() + "|" + s.getTimeWindow(), s.getMaxCapacity(), Integer::sum);
            }
        }
        Map<LocalDate, List<Visitor>> bookingsByDate = new HashMap<>();
        List<Map<String, Object>> open = new ArrayList<>();
        for (Map.Entry<String, Integer> e : capacityBySlot.entrySet()) {
            int sep = e.getKey().indexOf('|');
            LocalDate date = LocalDate.parse(e.getKey().substring(0, sep));
            String window = e.getKey().substring(sep + 1);
            if (date.equals(v.getVisitDate()) && window.equals(v.getTimeWindow())) continue; // offered as "keep current"
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

    @GetMapping("/leave-feedback")
    public String showFeedbackPage(@RequestParam(name = "token", required = false) String token, Model model) {
        Visitor v = (token == null || token.isBlank()) ? null : repo.findBySecureToken(token).orElse(null);
        if (v == null || !"CHECKED-OUT".equals(v.getStatus())) {
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
        if (v == null || !"CHECKED-OUT".equals(v.getStatus())) {
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

    // 🔒 RACE-SAFE SAVE: a standard booking is saved under a row lock on its slot with the seat count re-read inside
    // the lock (SeatReservationService), so two visitors can't both take the last seats. Master Bookings are exempt
    // (VIP buyout). Web-layer test slices have no SeatReservationService and save directly, as before.
    private Visitor saveWithinCapacity(Visitor visitor, boolean isMasterBooking) {
        if (isMasterBooking || seatReservation == null) return repo.save(visitor);
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

    // ID photo file name: a random UUID plus the uploaded file's own extension (.jpg, .jpeg or .png only, so nothing
    // from the browser can steer the path). An unknown extension falls back to the checked content type.
    static String anonymousIdPhotoName(String originalFilename, String contentType) {
        String ext = "image/png".equals(contentType) ? "png" : "jpg";
        if (originalFilename != null) {
            int dot = originalFilename.lastIndexOf('.');
            String given = dot >= 0 ? originalFilename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
            if (given.equals("jpg") || given.equals("jpeg") || given.equals("png")) ext = given;
        }
        return java.util.UUID.randomUUID() + "." + ext;
    }

    // Seats configured for a public booking on this date and time window; 0 when the slot does not exist or the date has passed
    private int bookableSlotCapacity(LocalDate date, String timeWindow) {
        if (date == null || timeWindow == null || date.isBefore(LocalDate.now())) return 0;
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
