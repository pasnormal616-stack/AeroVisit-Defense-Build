package com.paf.aerovisit.controller;

import com.paf.aerovisit.FixedClock;
import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.AdmissionPricing;
import com.paf.aerovisit.service.AdvanceBookingRules;
import com.paf.aerovisit.service.BillingNumberService;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.PredictiveAdvisorService;
import com.paf.aerovisit.service.SeatReservationService;
import com.paf.aerovisit.service.WalkInService;

/**
 * 📅 Advance bookings must be at least the Director's minimum days ahead (default 1): today is Walk-in only. Checked in
 * /save and Manage Booking; the booking calendar greys out the days before. Walk-ins for today are unchanged.
 * No database: the repositories are mocked.
 */
@WebMvcTest({VisitorPortalController.class, AdvanceBookingSettingsController.class})
@Import({FixedClock.class, SecurityConfig.class, WalkInService.class, PaymentWorkflow.class, AdvanceBookingRules.class})
class AdvanceBookingMinDaysTest {

    private static final String EMAIL = "juan@example.com";
    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);
    private static final RequestPostProcessor DIRECTOR = user("director").roles("DIRECTOR");
    private static final RequestPostProcessor GUARD = user("guard").roles("GUARD");
    private static final byte[] ID_PHOTO = com.paf.aerovisit.TestImages.jpeg(45);

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
    private final List<BookingSlot> allSlots = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // The server's "today" is Manila's; a run in the last minutes before midnight could straddle the change
        today = FixedClock.TODAY;
        when(admissionPricing.current()).thenReturn(new AdmissionPricing.Prices(10_000L, 15_000L));
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(seatReservation.saveIfSeatsRemain(any(Visitor.class))).thenAnswer(inv -> {
            Visitor v = inv.getArgument(0);
            if (v.getId() == null) v.setId(51L);
            return v;
        });
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepo.findAll()).thenReturn(allSlots);
        for (int d = 0; d <= 6; d++) slot(today.plusDays(d), MORNING);
    }

    @AfterEach
    void removeTestUploads() throws Exception {
        ArgumentCaptor<Visitor> all = ArgumentCaptor.forClass(Visitor.class);
        verify(seatReservation, org.mockito.Mockito.atLeast(0)).saveIfSeatsRemain(all.capture());
        for (Visitor v : all.getAllValues()) {
            if (v.getIdCardImageUrl() != null) java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("uploads").resolve(v.getIdCardImageUrl()));
        }
    }

    private void slot(LocalDate date, String window) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(window);
        s.setMaxCapacity(50);
        allSlots.add(s);
        when(bookingRepo.findBySlotDateAndTimeWindow(date, window)).thenReturn(List.of(s));
    }

    private void minDays(int days) {
        PortalSettings settings = new PortalSettings();
        settings.setAdvanceMinDaysAhead(days);
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
    }

    private static MockMultipartHttpServletRequestBuilder advance(LocalDate date) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", ID_PHOTO));
        req.param("termsAccepted", "true").param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL)
           .param("contactNumber", "0917-555-0101").param("visitDate", date.toString()).param("timeWindow", MORNING)
           .param("freeCount", "1");
        return req;
    }

    // --- /save ---

    @Test
    void anAdvanceBookingForTodayIsRefused() throws Exception {
        mvc.perform(advance(today))
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", "Advance bookings must be made at least 1 day(s) ahead. For today, please choose Walk-in."));
        verify(seatReservation, never()).saveIfSeatsRemain(any());
        verify(repo, never()).save(any());
    }

    @Test
    void anAdvanceBookingForTomorrowIsAccepted() throws Exception {
        mvc.perform(advance(today.plusDays(1))).andExpect(flash().attribute("error", Matchers.nullValue()));
        ArgumentCaptor<Visitor> saved = ArgumentCaptor.forClass(Visitor.class);
        verify(seatReservation).saveIfSeatsRemain(saved.capture());
        assertThat(saved.getValue().getVisitDate()).isEqualTo(today.plusDays(1));
        assertThat(saved.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().walkIn()).isFalse();
    }

    @Test
    void theSettingChangesTheEarliestDate() throws Exception {
        minDays(3);
        mvc.perform(advance(today.plusDays(2)))
           .andExpect(flash().attribute("error", "Advance bookings must be made at least 3 day(s) ahead. For today, please choose Walk-in."));
        verify(seatReservation, never()).saveIfSeatsRemain(any());

        mvc.perform(advance(today.plusDays(3))).andExpect(flash().attribute("error", Matchers.nullValue()));
        verify(seatReservation).saveIfSeatsRemain(any());
    }

    @Test
    void aWalkInForTodayStillWorks() throws Exception {
        LocalTime now = FixedClock.TIME;
        String open = "Open (" + CLOCK.format(now.minusHours(1)) + " - " + CLOCK.format(now.plusHours(1)) + ")";
        LocalDate jvmToday = FixedClock.TODAY; // walk-ins use the server clock (pinned to Manila in the app)
        slot(jvmToday, open);
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.file(new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", ID_PHOTO));
        req.param("bookingType", "WALK_IN").param("termsAccepted", "true").param("fullName", "Dela Cruz, Juan P.")
           .param("email", EMAIL).param("contactNumber", "0917-555-0101").param("timeWindow", open).param("freeCount", "1");
        mvc.perform(req).andExpect(redirectedUrl("/success-page"));
        ArgumentCaptor<Visitor> saved = ArgumentCaptor.forClass(Visitor.class);
        verify(seatReservation).saveIfSeatsRemain(saved.capture());
        assertThat(saved.getValue().walkIn()).isTrue();
        assertThat(saved.getValue().getStatus()).isEqualTo("APPROVED");
        assertThat(saved.getValue().getVisitDate()).isEqualTo(jvmToday);
    }

    // --- Booking page ---

    @Test
    void theCalendarStartsTomorrowInAdvanceModeAndOffersTheWalkInSwitch() throws Exception {
        mvc.perform(get("/"))
           .andExpect(content().string(Matchers.allOf(
               Matchers.containsString("data-earliest=\"" + today.plusDays(1) + "\""),
               Matchers.containsString("validRange: { start: document.getElementById('advancePicker').getAttribute('data-earliest') }"),
               Matchers.not(Matchers.containsString("new Date().toISOString()")),
               Matchers.containsString("Visiting today? Choose Walk-in (Today) instead."))));

        minDays(4);
        mvc.perform(get("/")).andExpect(content().string(Matchers.containsString("data-earliest=\"" + today.plusDays(4) + "\"")));
    }

    @Test
    void theSwitchButtonIsShownOnlyWhileWalkInsAreOpenToday() throws Exception {
        // No slot today for walk-ins (closed today): the note stays, the button goes
        when(repo.findClosureDates()).thenReturn(List.of(FixedClock.TODAY));
        mvc.perform(get("/")).andExpect(content().string(Matchers.allOf(
            Matchers.containsString("Visiting today? Choose Walk-in (Today) instead."),
            Matchers.not(Matchers.containsString("Switch to Walk-in</button>")))));

        LocalTime now = FixedClock.TIME;
        when(repo.findClosureDates()).thenReturn(List.of());
        LocalDate jvmToday = FixedClock.TODAY;
        BookingSlot s = new BookingSlot();
        s.setSlotDate(jvmToday);
        s.setTimeWindow("Open (" + CLOCK.format(now.minusHours(1)) + " - " + CLOCK.format(now.plusHours(1)) + ")");
        s.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(jvmToday)).thenReturn(List.of(s));
        mvc.perform(get("/")).andExpect(content().string(Matchers.containsString("Switch to Walk-in</button>")));
    }

    // --- Manage Booking ---

    private MockHttpSession signedIn(Visitor v) {
        when(repo.findById(v.getId())).thenReturn(Optional.of(v));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(VisitorPortalController.MANAGE_VISITOR_ID, v.getId());
        session.setAttribute(VisitorPortalController.MANAGE_FORM_TOKEN, "tok");
        return session;
    }

    private static Visitor booking(long id, LocalDate date) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setEmail(EMAIL);
        v.setStatus("PENDING");
        v.setVisitDate(date);
        v.setTimeWindow(MORNING);
        v.setGroupSize(1);
        return v;
    }

    @Test
    void manageBookingCannotMoveAnAdvanceBookingToTodayAndDoesNotOfferToday() throws Exception {
        Visitor v = booking(61L, today.plusDays(5));
        MockHttpSession session = signedIn(v);

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(session).param("formToken", "tok")
                .param("visitDate", today.toString()).param("timeWindow", MORNING))
           .andExpect(redirectedUrl("/manage-booking"))
           .andExpect(flash().attribute("error", "Advance bookings must be made at least 1 day(s) ahead. For today, please choose Walk-in."));
        assertThat(v.getVisitDate()).isEqualTo(today.plusDays(5));
        verify(repo, never()).save(any());

        // The reschedule list starts tomorrow
        mvc.perform(get("/manage-booking").session(session))
           .andExpect(content().string(Matchers.allOf(
               Matchers.not(Matchers.containsString("\"date\":\"" + today + "\"")),
               Matchers.containsString("\"date\":\"" + today.plusDays(1) + "\""))));

        // ...and tomorrow is fine
        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(session).param("formToken", "tok")
                .param("visitDate", today.plusDays(1).toString()).param("timeWindow", MORNING))
           .andExpect(flash().attribute("success", Matchers.startsWith("Booking updated.")));
        assertThat(v.getVisitDate()).isEqualTo(today.plusDays(1));
    }

    @Test
    void aBookingDatedTodayCannotMoveToAnotherWindowToday() throws Exception {
        Visitor v = booking(62L, today);
        MockHttpSession session = signedIn(v);
        // 📅 A reschedule follows the rules of a new advance booking: same-day is never a target
        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(session).param("formToken", "tok")
                .param("visitDate", today.toString()).param("timeWindow", "Afternoon (01:00 PM - 05:00 PM)"))
           .andExpect(flash().attribute("error", "Advance bookings must be made at least 1 day(s) ahead. For today, please choose Walk-in."));
        assertThat(v.getVisitDate()).isEqualTo(today);
        assertThat(v.getTimeWindow()).isEqualTo(MORNING);
        verify(repo, never()).save(any());
    }

    // --- Settings (Director only) ---

    @Test
    void onlyTheDirectorSavesTheSettingWithinOneTo30Days() throws Exception {
        mvc.perform(post("/admin/settings/advance-booking").with(csrf()).with(GUARD).param("advanceMinDaysAhead", "3"))
           .andExpect(status().isForbidden());
        mvc.perform(post("/admin/settings/advance-booking").with(csrf()).with(DIRECTOR).param("advanceMinDaysAhead", "0"))
           .andExpect(flash().attribute("error", Matchers.startsWith("The minimum days ahead must be")));
        mvc.perform(post("/admin/settings/advance-booking").with(csrf()).with(DIRECTOR).param("advanceMinDaysAhead", "31"))
           .andExpect(flash().attribute("error", Matchers.startsWith("The minimum days ahead must be")));
        verify(settingsRepo, never()).save(any());

        mvc.perform(post("/admin/settings/advance-booking").with(csrf()).with(DIRECTOR).param("advanceMinDaysAhead", "3"))
           .andExpect(redirectedUrl("/admin/settings#advanceBookingSettings"))
           .andExpect(flash().attribute("success", Matchers.containsString("3 day(s)")));
        ArgumentCaptor<PortalSettings> saved = ArgumentCaptor.forClass(PortalSettings.class);
        verify(settingsRepo).save(saved.capture());
        assertThat(saved.getValue().advanceMinDays()).isEqualTo(3);
    }
}
