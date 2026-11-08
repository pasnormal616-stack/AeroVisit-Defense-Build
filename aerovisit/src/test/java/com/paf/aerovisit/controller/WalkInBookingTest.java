package com.paf.aerovisit.controller;

import com.paf.aerovisit.FixedClock;
import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BlacklistedFace;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.AdmissionPricing;
import com.paf.aerovisit.service.BillingNumberService;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.IdentityBans;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.PredictiveAdvisorService;
import com.paf.aerovisit.service.SeatReservationService;
import com.paf.aerovisit.service.WalkInService;

/**
 * 🚶 Walk-in (today) bookings on /save: today only, a window with 20+ minutes left, at most the Director's maximum, no
 * photos, the same Terms, email code and bans, seats held at once through SeatReservationService (no Director approval),
 * and payment straight away when the total is above 0. No database: the repositories are mocked. The time windows are
 * built around a fixed noon (FixedClock), so every check runs at any hour.
 */
@WebMvcTest(VisitorPortalController.class)
@Import({FixedClock.class, SecurityConfig.class, WalkInService.class, PaymentWorkflow.class})
class WalkInBookingTest {

    private static final String EMAIL = "juan@example.com";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private OtpService otpService;
    @MockitoBean private AdmissionPricing admissionPricing;
    @MockitoBean private SeatReservationService seatReservation;
    @MockitoBean private BillingNumberService billingNumbers;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;

    private LocalDate today;
    private String openWindow;

    // "Test (08:00 AM - 11:00 AM)": the label format the gate and the booking page read
    private static String label(String name, LocalTime start, LocalTime end) {
        return name + " (" + CLOCK.format(start) + " - " + CLOCK.format(end) + ")";
    }

    private void slot(String window, int capacity) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(today);
        s.setTimeWindow(window);
        s.setMaxCapacity(capacity);
        when(bookingRepo.findBySlotDateAndTimeWindow(today, window)).thenReturn(List.of(s));
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(today)).thenReturn(List.of(s));
    }

    @BeforeEach
    void todayWithAnOpenWindow() {
        LocalTime now = FixedClock.TIME;
        today = FixedClock.TODAY;
        // Within today on both ends, so a run just after midnight or just before it never wraps to the other day
        openWindow = label("Open", now.isBefore(LocalTime.of(1, 1)) ? LocalTime.of(0, 1) : now.minusHours(1).withSecond(0).withNano(0),
                now.isAfter(LocalTime.of(22, 58)) ? LocalTime.of(23, 59) : now.plusHours(1).withSecond(0).withNano(0));
        slot(openWindow, 50);
        when(admissionPricing.current()).thenReturn(new AdmissionPricing.Prices(10_000L, 15_000L));
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(billingNumbers.next()).thenReturn("000777");
        when(seatReservation.saveIfSeatsRemain(any(Visitor.class))).thenAnswer(inv -> {
            Visitor v = inv.getArgument(0);
            if (v.getId() == null) v.setId(41L);
            return v;
        });
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findById(41L)).thenAnswer(inv -> Optional.empty());
    }

    // Walk-ins now upload an ID photo too: remove what each test saved
    @org.junit.jupiter.api.AfterEach
    void removeTestUploads() throws Exception {
        try {
            ArgumentCaptor<Visitor> all = ArgumentCaptor.forClass(Visitor.class);
            verify(seatReservation, org.mockito.Mockito.atLeast(0)).saveIfSeatsRemain(all.capture());
            for (Visitor v : all.getAllValues()) {
                for (String f : new String[] {v.getIdCardImageUrl(), v.getIdFaceCropUrl()}) {
                    if (f != null) java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("uploads").resolve(f));
                }
            }
        } catch (org.mockito.exceptions.base.MockitoException ignored) {
            // the test stubbed the seat lock to throw: nothing was saved
        }
    }

    private static final byte[] ID_PHOTO = com.paf.aerovisit.TestImages.jpeg(44);

    private static MockMultipartHttpServletRequestBuilder walkIn(String window, String discounted, String regular, String free) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", ID_PHOTO)); // 🪪 required for walk-ins too
        req.param("bookingType", "WALK_IN").param("termsAccepted", "true")
           .param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL).param("contactNumber", "0917-555-0101")
           .param("timeWindow", window)
           .param("discountedCount", discounted).param("regularCount", regular).param("freeCount", free);
        return req;
    }

    private Visitor saved() {
        ArgumentCaptor<Visitor> captor = ArgumentCaptor.forClass(Visitor.class);
        verify(seatReservation).saveIfSeatsRemain(captor.capture());
        return captor.getValue();
    }

    // --- Today only, a window with 20+ minutes left ---

    @Test
    void aFreeWalkInWithOnlyAnIdPhotoHoldsItsSeatsAtOnceWithoutApprovalAndGoesToTheGate() throws Exception {
        LocalDateTime before = FixedClock.NOW;
        mvc.perform(walkIn(openWindow, "0", "0", "2"))
           .andExpect(redirectedUrl("/success-page"))
           .andExpect(flash().attribute("walkInGate", true));

        Visitor v = saved();
        assertThat(v.getStatus()).isEqualTo("APPROVED");                // seats held, saved under the seat lock
        assertThat(v.awaitsApproval()).isFalse();                       // never in the Director's pending queue
        assertThat(v.walkIn()).isTrue();
        assertThat(v.getVisitDate()).isEqualTo(today);
        assertThat(v.getGroupSize()).isEqualTo(2);
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_NOT_REQUIRED);
        assertThat(v.getBillingNumber()).isNull();
        assertThat(v.getIdImageUrl()).isNull();                         // no selfie
        assertThat(v.getIdCardImageUrl()).isNotNull();                  // the ID photo
        assertThat(v.isOcrNameMatch()).isFalse();                       // 🪪 no name check: the guard compares the ID
        assertThat(v.getFaceEncoding()).isNull();
        assertThat(v.getIdFaceCropUrl()).isNull();
        // free: must check in within the walk-in minutes (default 30), never later than the window's end
        assertThat(v.getCheckInDueAt()).isBetween(before.plusMinutes(29), FixedClock.NOW.plusMinutes(30));
        verify(otpService).consume(EMAIL);
        verify(emailService, never()).sendPendingNotification(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aWalkInForAnotherDayIsRefused() throws Exception {
        mvc.perform(walkIn(openWindow, "0", "1", "0").param("visitDate", today.plusDays(1).toString()))
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", VisitorPortalController.WALK_IN_TODAY_ONLY));
        verify(seatReservation, never()).saveIfSeatsRemain(any());
        verify(repo, never()).save(any());
    }

    @Test
    void aWindowThatHasEndedOrEndsWithin20MinutesIsRefused() throws Exception {
        LocalTime now = FixedClock.TIME;
        String ended = label("Ended", LocalTime.of(0, 1), LocalTime.of(0, 2));
        slot(ended, 50);
        mvc.perform(walkIn(ended, "0", "1", "0"))
           .andExpect(flash().attribute("error", Matchers.startsWith("That time window has already ended.")));

        String closing = label("Closing", LocalTime.of(0, 1), now.plusMinutes(10).withSecond(0).withNano(0));
        slot(closing, 50);
        mvc.perform(walkIn(closing, "0", "1", "0"))
           .andExpect(flash().attribute("error", "That time window ends in less than 20 minutes. Please choose a later window today or book in advance."));
        verify(seatReservation, never()).saveIfSeatsRemain(any());
    }

    @Test
    void aWindowThatIsNotOneOfTodaysSlotsIsRefused() throws Exception {
        mvc.perform(walkIn("Made Up (08:00 AM - 05:00 PM)", "0", "1", "0"))
           .andExpect(flash().attribute("error", "Please choose one of today's open time windows."));
        verify(seatReservation, never()).saveIfSeatsRemain(any());
    }

    // --- At most the Director's maximum ---

    @Test
    void moreThan20PersonsAreRefusedAndTheMaximumFollowsTheSetting() throws Exception {
        mvc.perform(walkIn(openWindow, "10", "10", "1"))
           .andExpect(flash().attribute("error", "Walk-in reservations must not exceed 20 persons (pax)."));
        mvc.perform(walkIn(openWindow, "10", "10", "0")).andExpect(redirectedUrl("/manage-booking/payment"));

        PortalSettings settings = new PortalSettings();
        settings.setWalkInMaxPax(5);
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
        mvc.perform(walkIn(openWindow, "0", "6", "0"))
           .andExpect(flash().attribute("error", "Walk-in reservations must not exceed 5 persons (pax)."));
    }

    // --- Photos: never for a walk-in, always for an advance booking ---

    @Test
    void aMissingIdPhotoIsRefusedForAnAdvanceBookingAndAWalkIn() throws Exception {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(today.plusDays(3));
        s.setTimeWindow("Morning (08:00 AM - 12:00 PM)");
        s.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(today.plusDays(3), "Morning (08:00 AM - 12:00 PM)")).thenReturn(List.of(s));
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.param("termsAccepted", "true").param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL)
           .param("visitDate", today.plusDays(3).toString()).param("timeWindow", "Morning (08:00 AM - 12:00 PM)").param("regularCount", "1");
        mvc.perform(req)
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", VisitorPortalController.PHOTOS_REQUIRED));
        verify(seatReservation, never()).saveIfSeatsRemain(any());

        // ...and a walk-in without one is refused the same way
        MockMultipartHttpServletRequestBuilder noPhoto = multipart("/save");
        noPhoto.with(csrf()).with(kycNonce());
        noPhoto.param("bookingType", "WALK_IN").param("termsAccepted", "true").param("fullName", "Dela Cruz, Juan P.")
               .param("email", EMAIL).param("timeWindow", openWindow).param("regularCount", "1");
        mvc.perform(noPhoto).andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", VisitorPortalController.PHOTOS_REQUIRED));
        verify(seatReservation, never()).saveIfSeatsRemain(any());
    }

    // --- Seats ---

    @Test
    void aFullWindowRefusesTheWalkInAndTheLastSeatsRaceIsLostCleanly() throws Exception {
        slot(openWindow, 3);
        Visitor holder = new Visitor();
        holder.setId(9L);
        holder.setStatus("APPROVED");
        holder.setTimeWindow(openWindow);
        holder.setGroupSize(2);
        when(repo.findByVisitDate(today)).thenReturn(List.of(holder));
        mvc.perform(walkIn(openWindow, "0", "2", "0"))
           .andExpect(flash().attribute("error", "Insufficient slots available for this specific shift! Please choose another slot."));
        verify(seatReservation, never()).saveIfSeatsRemain(any());

        // The check above passes, but another walk-in takes the last seat first: the seat lock refuses this one
        when(seatReservation.saveIfSeatsRemain(any(Visitor.class))).thenThrow(new SeatReservationService.CapacityFullException());
        mvc.perform(walkIn(openWindow, "0", "1", "0"))
           .andExpect(flash().attribute("error", "Insufficient slots available for this specific shift! Please choose another slot."));
        verify(billingNumbers, never()).next(); // a refused walk-in never uses up a billing number
    }

    // --- Bans ---

    private static BlacklistedFace ban(Long id, String name, String email, String phone) {
        BlacklistedFace b = new BlacklistedFace();
        b.setId(id);
        b.setName(name);
        b.setEmail(email);
        b.setPhone(phone);
        return b;
    }

    @Test
    void anEmailOrPhoneBanIsRefusedAndANameBanIsFlaggedForTheGuard() throws Exception {
        when(faceBanRepo.findAll()).thenReturn(List.of(ban(3L, "Someone Else", EMAIL, null)));
        mvc.perform(walkIn(openWindow, "0", "1", "0")).andExpect(flash().attribute("error", IdentityBans.BANNED_MESSAGE));
        when(faceBanRepo.findAll()).thenReturn(List.of(ban(4L, "Someone Else", null, "0917-555-0101")));
        mvc.perform(walkIn(openWindow, "0", "1", "0")).andExpect(flash().attribute("error", IdentityBans.BANNED_MESSAGE));
        verify(seatReservation, never()).saveIfSeatsRemain(any());

        when(faceBanRepo.findAll()).thenReturn(List.of(ban(12L, "Dela Cruz, Juan P.", null, null)));
        mvc.perform(walkIn(openWindow, "0", "0", "1")).andExpect(redirectedUrl("/success-page"));
        Visitor v = saved();
        assertThat(v.getStatus()).isEqualTo("APPROVED"); // keeps its seats; the guard compares the ID with the ban
        assertThat(v.getPossibleBanMatch()).isEqualTo("Ban #12: Dela Cruz, Juan P.");
        assertThat(v.getPossibleBanId()).isEqualTo(12L);
        assertThat(v.banCheckPending()).isTrue();
    }

    // --- Payment ---

    @Test
    void aPaidWalkInGetsABillingNumberThePaymentEmailAndThePaymentPageInANewSession() throws Exception {
        MockHttpSession session = new MockHttpSession(null, "planted-session-id");
        LocalDateTime before = FixedClock.NOW;
        mvc.perform(walkIn(openWindow, "1", "2", "0").session(session))
           .andExpect(redirectedUrl("/manage-booking/payment")); // no token in the address

        Visitor v = saved();
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getAdmissionTotalCentavos()).isEqualTo(40_000L);
        assertThat(v.getBillingNumber()).isEqualTo("000777");
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_AWAITING);
        assertThat(v.getPaymentToken()).isNotBlank();
        // pay within the walk-in minutes (default 30); the window ends later, so the minutes decide
        assertThat(v.getPaymentDueAt()).isBetween(before.plusMinutes(29), FixedClock.NOW.plusMinutes(30));
        // paid walk-in: released at the end of its window if not checked in
        assertThat(v.getCheckInDueAt()).isEqualTo(com.paf.aerovisit.service.WalkInService.windowEnd(v));

        // 🔒 signed into Manage Booking for this booking only, under a NEW session ID (no session fixation)
        assertThat(session.getId()).isNotEqualTo("planted-session-id");
        assertThat(session.getAttribute(VisitorPortalController.MANAGE_VISITOR_ID)).isEqualTo(41L);
        assertThat(session.getAttribute(VisitorPortalController.MANAGE_FORM_TOKEN)).isNotNull();

        ArgumentCaptor<EmailService.PaymentNotice> notice = ArgumentCaptor.forClass(EmailService.PaymentNotice.class);
        verify(emailService).sendPaymentRequest(notice.capture());
        assertThat(notice.getValue().walkIn()).isTrue();
        assertThat(notice.getValue().billingNumber()).isEqualTo("000777");
    }

    // --- Booking page ---

    @Test
    void theBookingPageOffersTodaysOpenWindowsAndTheReasonWhenThereAreNone() throws Exception {
        mvc.perform(get("/"))
           .andExpect(content().string(Matchers.allOf(
               Matchers.containsString("data-booking-type=\"WALK_IN\""),
               Matchers.containsString("data-window=\"" + openWindow + "\""),
               Matchers.containsString("data-current=\"true\""))));

        when(repo.findClosureDates()).thenReturn(List.of(today));
        mvc.perform(get("/")).andExpect(content().string(Matchers.containsString("The museum is closed today.")));
    }

    // --- Manage Booking: a walk-in is for today, so it is view only ---

    @Test
    void manageBookingCannotMoveAWalkIn() throws Exception {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setEmail(EMAIL);
        v.setStatus("APPROVED");
        v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        v.setVisitDate(today);
        v.setTimeWindow(openWindow);
        v.setGroupSize(1);
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(VisitorPortalController.MANAGE_VISITOR_ID, 7L);
        session.setAttribute(VisitorPortalController.MANAGE_FORM_TOKEN, "tok-1");

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(session).param("formToken", "tok-1")
                .param("visitDate", today.plusDays(2).toString()).param("timeWindow", openWindow))
           .andExpect(flash().attribute("error", "This booking can no longer be changed online."));
        assertThat(v.getVisitDate()).isEqualTo(today);

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        verify(repo, never()).save(any());

        mvc.perform(get("/manage-booking").session(session))
           .andExpect(content().string(Matchers.allOf(
               Matchers.containsString("This is a walk-in booking for today"),
               Matchers.containsString("If you can't come, no action is needed."),
               Matchers.not(Matchers.containsString("/manage-booking/reschedule")))));
    }

    // 🚨 The museum closed today: a paid walk-in was asked to reschedule (closure code), picks another day in Manage
    // Booking and becomes an Advance Booking for it (the Director approves; the payment is kept)
    @Test
    void aPaidWalkInMovedAfterAClosureBecomesAnAdvanceBookingAndKeepsItsPayment() throws Exception {
        Visitor v = new Visitor();
        v.setId(8L);
        v.setEmail(EMAIL);
        v.setStatus(Visitor.STATUS_RESCHEDULE_REQUIRED);
        v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        v.setVisitDate(today);
        v.setTimeWindow(openWindow);
        v.setGroupSize(1);
        v.setAdmissionTotalCentavos(15_000L);
        v.setBillingNumber("000321");
        v.setPaymentStatus(Visitor.PAY_PAID);
        v.setCheckInDueAt(FixedClock.NOW.plusHours(1));
        when(repo.findById(8L)).thenReturn(Optional.of(v));
        LocalDate later = today.plusDays(2);
        BookingSlot s = new BookingSlot();
        s.setSlotDate(later);
        s.setTimeWindow("Morning (08:00 AM - 12:00 PM)");
        s.setMaxCapacity(50);
        when(bookingRepo.findAll()).thenReturn(List.of(s));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(VisitorPortalController.MANAGE_VISITOR_ID, 8L);
        session.setAttribute(VisitorPortalController.MANAGE_FORM_TOKEN, "tok-8");

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(session).param("formToken", "tok-8")
                .param("visitDate", later.toString()).param("timeWindow", "Morning (08:00 AM - 12:00 PM)"))
           .andExpect(flash().attribute("success", Matchers.startsWith("Booking updated.")));
        assertThat(v.getVisitDate()).isEqualTo(later);
        assertThat(v.getStatus()).isEqualTo("PENDING");
        assertThat(v.walkIn()).isFalse();
        assertThat(v.getCheckInDueAt()).isNull();
        assertThat(v.getPaymentStatus()).isEqualTo(Visitor.PAY_PAID);
    }

    // --- 🚶 The walk-in gate QR (its secure token): "Show this QR to the guard at the gate" ---

    private static final String QR_LABEL = "Show this QR to the guard at the gate";

    @Test
    void aFreeWalkInGetsItsGateQrOnTheGateScreenAndByEmail() throws Exception {
        org.springframework.test.web.servlet.MvcResult booked = mvc.perform(walkIn(openWindow, "0", "0", "2"))
           .andExpect(redirectedUrl("/success-page"))
           .andReturn();
        Visitor v = saved();
        assertThat(v.getSecureToken()).isNotBlank();
        String qr = (String) booked.getFlashMap().get("walkInQr");
        assertThat(com.paf.aerovisit.QrCodes.decodeDataUri(qr)).isEqualTo(v.getSecureToken());

        // the free walk-in pass email, with the same token
        verify(emailService).sendWalkInPass(eq(EMAIL), anyString(), eq(v.getReferenceId()), eq(openWindow), eq(v.getSecureToken()));

        // the "Go to the Gate" screen shows it
        String page = mvc.perform(get("/success-page").flashAttrs(booked.getFlashMap()))
           .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).contains("Go to the Gate", QR_LABEL, "class=\"vp-gate-pass\"");
        assertThat(com.paf.aerovisit.QrCodes.decodeFirstOnPage(page)).isEqualTo(v.getSecureToken());
    }

    @Test
    void aPaidWalkInsPaymentEmailCarriesItsGateQrToken() throws Exception {
        mvc.perform(walkIn(openWindow, "0", "2", "0")).andExpect(redirectedUrl("/manage-booking/payment"));
        Visitor v = saved();
        ArgumentCaptor<EmailService.PaymentNotice> notice = ArgumentCaptor.forClass(EmailService.PaymentNotice.class);
        verify(emailService).sendPaymentRequest(notice.capture());
        assertThat(notice.getValue().qrToken()).isEqualTo(v.getSecureToken()).isNotBlank();
        verify(emailService, never()).sendWalkInPass(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private Visitor manageBookingVisitor(long id, String category, String status, LocalDate date) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setEmail(EMAIL);
        v.setName("Juan");
        v.setReferenceId("PAF-MB" + id);
        v.setSecureToken("secure-token-" + id);
        v.setStatus(status);
        v.setBookingCategory(category);
        v.setVisitDate(date);
        v.setTimeWindow(openWindow);
        v.setGroupSize(1);
        when(repo.findById(id)).thenReturn(Optional.of(v));
        return v;
    }

    private String manageBookingPage(long id) throws Exception {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(VisitorPortalController.MANAGE_VISITOR_ID, id);
        session.setAttribute(VisitorPortalController.MANAGE_FORM_TOKEN, "tok-" + id);
        return mvc.perform(get("/manage-booking").session(session))
           .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void manageBookingShowsTheGateQrOnlyForTodaysWalkInNotYetInside() throws Exception {
        manageBookingVisitor(61, Visitor.CATEGORY_WALK_IN, "APPROVED", today);
        String page = manageBookingPage(61);
        assertThat(page).contains(QR_LABEL);
        assertThat(com.paf.aerovisit.QrCodes.decodeFirstOnPage(page)).isEqualTo("secure-token-61");

        manageBookingVisitor(62, null, "APPROVED", today.plusDays(2));                 // advance booking
        manageBookingVisitor(63, Visitor.CATEGORY_WALK_IN, "CHECKED-IN", today);       // already inside
        manageBookingVisitor(64, Visitor.CATEGORY_WALK_IN, "NO_SHOW", today);          // released
        manageBookingVisitor(65, Visitor.CATEGORY_WALK_IN, "APPROVED", today.minusDays(1)); // another day
        for (long id = 62; id <= 65; id++) {
            assertThat(manageBookingPage(id)).as("booking " + id).doesNotContain(QR_LABEL).doesNotContain("data:image/png;base64,");
        }
    }
}
