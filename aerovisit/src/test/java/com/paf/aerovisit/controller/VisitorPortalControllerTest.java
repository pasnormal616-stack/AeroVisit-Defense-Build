package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.WebDataBinder;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Public visitor pages: booking lookup, feedback links, mass-assignment guard and the email-OTP gate on /save. No database. */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
class VisitorPortalControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private VisitorPortalController controller;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor visitor(String status, String referenceId) {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setStatus(status);
        v.setReferenceId(referenceId);
        v.setVisitDate(LocalDate.now());
        return v;
    }

    // --- /check-status (Manage Booking) ---

    @Test
    void checkStatusWithoutReferenceIdIsRefused() throws Exception {
        mvc.perform(get("/check-status").param("email", "juan@example.com"))
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", "Please enter both your email and Booking Reference ID."));
        verify(repo, never()).findByEmailIgnoreCase(anyString());
    }

    @Test
    void checkStatusWithMatchingEmailAndReferenceIdShowsBooking() throws Exception {
        Visitor v = visitor("PENDING", "PAF-A7X92K");
        when(repo.findByEmailIgnoreCase("juan@example.com")).thenReturn(List.of(v));

        mvc.perform(get("/check-status").param("email", " juan@example.com ").param("referenceId", "paf-a7x92k"))
            .andExpect(view().name("success"))
            .andExpect(model().attribute("visitor", v));
        verify(repo, never()).findAll();
    }

    @Test
    void checkStatusWithWrongReferenceIdFindsNothing() throws Exception {
        when(repo.findByEmailIgnoreCase("juan@example.com")).thenReturn(List.of(visitor("PENDING", "PAF-A7X92K")));

        mvc.perform(get("/check-status").param("email", "juan@example.com").param("referenceId", "PAF-ZZZZZZ"))
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", "No booking found for this email and Reference ID."));
    }

    @Test
    void checkStatusNeverMatchesLegacyBookingOnEmailAlone() throws Exception {
        when(repo.findByEmailIgnoreCase("juan@example.com")).thenReturn(List.of(visitor("PENDING", null)));

        mvc.perform(get("/check-status").param("email", "juan@example.com").param("referenceId", "PAF-A7X92K"))
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", org.hamcrest.Matchers.startsWith("This booking was made before Booking Reference IDs")));
    }

    // --- /leave-feedback ---

    @Test
    void feedbackPageRejectsMissingOrUnknownToken() throws Exception {
        mvc.perform(get("/leave-feedback")).andExpect(view().name("feedback-error"));
        when(repo.findBySecureToken("nope")).thenReturn(Optional.empty());
        mvc.perform(get("/leave-feedback").param("token", "nope")).andExpect(view().name("feedback-error"));
    }

    @Test
    void feedbackPageRejectsVisitThatIsNotFinished() throws Exception {
        when(repo.findBySecureToken("tok")).thenReturn(Optional.of(visitor("CHECKED-IN", "PAF-A7X92K")));
        mvc.perform(get("/leave-feedback").param("token", "tok")).andExpect(view().name("feedback-error"));
    }

    @Test
    void feedbackPageNoLongerAcceptsNumericId() throws Exception {
        mvc.perform(get("/leave-feedback").param("id", "7")).andExpect(view().name("feedback-error"));
        verify(repo, never()).findById(any());
    }

    // --- /submit-feedback ---

    @Test
    void submitFeedbackRejectsUnknownToken() throws Exception {
        when(repo.findBySecureToken("nope")).thenReturn(Optional.empty());
        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "nope").param("rating", "5").param("feedback", "great"))
            .andExpect(view().name("feedback-error"));
        verify(repo, never()).save(any());
    }

    @Test
    void submitFeedbackRejectsVisitThatIsNotFinished() throws Exception {
        when(repo.findBySecureToken("tok")).thenReturn(Optional.of(visitor("APPROVED", "PAF-A7X92K")));
        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "5").param("feedback", "great"))
            .andExpect(view().name("feedback-error"));
        verify(repo, never()).save(any());
    }

    @Test
    void submitFeedbackRejectsSecondSubmission() throws Exception {
        Visitor v = visitor("CHECKED-OUT", "PAF-A7X92K");
        v.setRating(4);
        when(repo.findBySecureToken("tok")).thenReturn(Optional.of(v));
        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "1").param("feedback", "again"))
            .andExpect(view().name("feedback-error"));
        verify(repo, never()).save(any());
    }

    @Test
    void submitFeedbackRejectsRatingOutsideOneToFive() throws Exception {
        when(repo.findBySecureToken("tok")).thenReturn(Optional.of(visitor("CHECKED-OUT", "PAF-A7X92K")));
        for (String rating : new String[] {"0", "6"}) {
            mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", rating).param("feedback", "x"))
                .andExpect(view().name("feedback-error"));
        }
        verify(repo, never()).save(any());
    }

    @Test
    void submitFeedbackSavesOnceAndTruncatesTo500Characters() throws Exception {
        Visitor v = visitor("CHECKED-OUT", "PAF-A7X92K");
        when(repo.findBySecureToken("tok")).thenReturn(Optional.of(v));

        mvc.perform(post("/submit-feedback").with(csrf()).param("token", "tok").param("rating", "5").param("feedback", "a".repeat(600)))
            .andExpect(view().name("feedback-success"));

        ArgumentCaptor<Visitor> saved = ArgumentCaptor.forClass(Visitor.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getRating()).isEqualTo(5);
        assertThat(saved.getValue().getFeedback()).hasSize(500);
    }

    // --- Mass-assignment guard on the registration form ---

    @Test
    void registrationFormCannotSetServerControlledFields() {
        Visitor target = new Visitor();
        String originalToken = target.getSecureToken();
        String defaultStatus = target.getStatus();
        WebDataBinder binder = new WebDataBinder(target, "visitor");
        controller.restrictVisitorFields(binder);

        binder.bind(new MutablePropertyValues(Map.of(
            "fullName", "Juan Dela Cruz",
            "email", "juan@example.com",
            "status", "APPROVED",
            "id", "1",
            "secureToken", "forged-token",
            "referenceId", "PAF-FORGED",
            "qrCodeUrl", "QR_1.png")));

        assertThat(target.getFullName()).isEqualTo("Juan Dela Cruz");
        assertThat(target.getEmail()).isEqualTo("juan@example.com");
        assertThat(target.getStatus()).isEqualTo(defaultStatus).isNotEqualTo("APPROVED");
        assertThat(target.getId()).isNull();
        assertThat(target.getSecureToken()).isEqualTo(originalToken);
        assertThat(target.getReferenceId()).isNull();
        assertThat(target.getQrCodeUrl()).isNull();
    }

    // --- Email OTP fails closed (replaced reCAPTCHA) ---

    @Test
    void withoutAnOtpServiceEverySubmissionIsRefused() throws Exception {
        // This slice has no OtpService bean, like a broken wiring would: /save must refuse, not wave bookings through
        assertThat(ReflectionTestUtils.getField(controller, "otpService")).isNull();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/save")
                .file(new org.springframework.mock.web.MockMultipartFile("highResIdImage", new byte[0]))
                .param("fullName", "Juan Dela Cruz").param("email", "juan@example.com")
                .param("visitDate", LocalDate.now().plusDays(2).toString()).param("timeWindow", "Morning (08:00 AM - 12:00 PM)").param("termsAccepted", "true").with(csrf()).with(com.paf.aerovisit.controller.KycNonceSupport.kycNonce()))
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", "Security Error: Please verify your email address with the 6-digit code we sent before submitting."));

        verify(repo, never()).save(any());
    }

    // --- /manage-booking (visitor self-service portal) ---

    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";

    private static MockHttpSession signedIn(Long visitorId) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("manageVisitorId", visitorId);
        session.setAttribute("manageFormToken", "tok-123");
        return session;
    }

    private static Visitor upcoming(String status) {
        Visitor v = visitor(status, "PAF-A7X92K");
        v.setVisitDate(LocalDate.now().plusDays(5));
        v.setTimeWindow(WINDOW);
        v.setGroupSize(2);
        return v;
    }

    private static BookingSlot slot(LocalDate date, int max) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(WINDOW);
        s.setMaxCapacity(max);
        return s;
    }

    @Test
    void manageBookingPageIsPublicAndShowsSignIn() throws Exception {
        mvc.perform(get("/manage-booking"))
            .andExpect(view().name("manage-booking"))
            .andExpect(model().attributeDoesNotExist("booking"));
    }

    @Test
    void manageLoginNeedsMatchingEmailAndReferenceId() throws Exception {
        when(repo.findByEmailIgnoreCaseAndReferenceIdIgnoreCase("juan@example.com", "PAF-ZZZZZZ")).thenReturn(Optional.empty());
        mvc.perform(post("/manage-booking/login").with(csrf()).param("email", "juan@example.com").param("referenceId", "PAF-ZZZZZZ"))
            .andExpect(redirectedUrl("/manage-booking"))
            .andExpect(flash().attribute("error", "No booking found for this email and Reference ID."));
    }

    @Test
    void manageLoginStoresBookingInSessionAndDashboardShowsIt() throws Exception {
        Visitor v = upcoming("APPROVED");
        when(repo.findByEmailIgnoreCaseAndReferenceIdIgnoreCase("juan@example.com", "PAF-A7X92K")).thenReturn(Optional.of(v));
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/manage-booking/login").with(csrf()).session(session).param("email", " juan@example.com ").param("referenceId", "PAF-A7X92K"))
            .andExpect(redirectedUrl("/manage-booking"));
        assertThat(session.getAttribute("manageVisitorId")).isEqualTo(7L);
        assertThat(session.getAttribute("manageFormToken")).isNotNull();

        when(repo.findById(7L)).thenReturn(Optional.of(v));
        mvc.perform(get("/manage-booking").session(session))
            .andExpect(model().attribute("booking", v))
            .andExpect(model().attribute("statusLabel", "Approved"))
            .andExpect(model().attribute("reschedulable", true))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("id=\"mbCountdown\""),
                org.hamcrest.Matchers.containsString("Reschedule Your Visit"),
                org.hamcrest.Matchers.containsString("name=\"formToken\""))));
    }

    @Test
    void manageFormsWithoutTheSessionTokenAreRefused() throws Exception {
        when(repo.findById(7L)).thenReturn(Optional.of(upcoming("APPROVED")));
        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(7L)).param("formToken", "forged"))
            .andExpect(flash().attribute("error", "Your session has expired. Please sign in again."));
        mvc.perform(post("/manage-booking/reschedule").with(csrf()).param("formToken", "tok-123"))
            .andExpect(flash().attribute("error", "Your session has expired. Please sign in again."));
        verify(repo, never()).save(any());
    }

    @Test
    void rescheduleRefusesFinishedVisit() throws Exception {
        when(repo.findById(7L)).thenReturn(Optional.of(upcoming("CHECKED-OUT")));
        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(7L)).param("formToken", "tok-123")
                .param("visitDate", LocalDate.now().plusDays(9).toString()).param("timeWindow", WINDOW))
            .andExpect(flash().attribute("error", "This booking can no longer be changed online."));
        verify(repo, never()).save(any());
    }

    @Test
    void rescheduleOfApprovedBookingChecksCapacityAndGoesBackToPending() throws Exception {
        Visitor v = upcoming("APPROVED");
        v.setQrCodeUrl("QR_7.png");
        v.setReminderSent(true);
        LocalDate newDate = LocalDate.now().plusDays(9);
        Visitor other = upcoming("APPROVED");
        other.setId(8L);
        other.setVisitDate(newDate);
        other.setGroupSize(7);
        Visitor cancelled = upcoming("CANCELLED_BY_VISITOR"); // must not hold seats
        cancelled.setId(9L);
        cancelled.setVisitDate(newDate);
        cancelled.setGroupSize(50);
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        when(bookingRepo.findAll()).thenReturn(List.of(slot(newDate, 10)));
        when(repo.findByVisitDate(newDate)).thenReturn(List.of(other, cancelled));
        when(repo.findByEmailAndVisitDate("juan@example.com", newDate)).thenReturn(new java.util.ArrayList<>());

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(7L)).param("formToken", "tok-123")
                .param("visitDate", newDate.toString()).param("timeWindow", WINDOW))
            .andExpect(redirectedUrl("/manage-booking"))
            .andExpect(flash().attribute("success", org.hamcrest.Matchers.startsWith("Booking updated. Your new schedule")));
        assertThat(v.getVisitDate()).isEqualTo(newDate);
        assertThat(v.getGroupSize()).isEqualTo(2); // the group stays as it is
        assertThat(v.getStatus()).isEqualTo("PENDING");
        assertThat(v.getQrCodeUrl()).isNull();
        assertThat(v.isReminderSent()).isFalse();
    }

    @Test
    void rescheduleIsRefusedWhenTheSlotIsFull() throws Exception {
        Visitor v = upcoming("PENDING");
        LocalDate newDate = LocalDate.now().plusDays(9);
        Visitor other = upcoming("APPROVED");
        other.setId(8L);
        other.setVisitDate(newDate);
        other.setGroupSize(9);
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        when(bookingRepo.findAll()).thenReturn(List.of(slot(newDate, 10)));
        when(repo.findByVisitDate(newDate)).thenReturn(List.of(other));

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(7L)).param("formToken", "tok-123")
                .param("visitDate", newDate.toString()).param("timeWindow", WINDOW))
            .andExpect(flash().attribute("error", "Not enough seats: only 1 left in that time slot."));
        verify(repo, never()).save(any());
    }

    @Test
    void pendingRequestsHoldSeatsSoAFullSlotRefusesTheReschedule() throws Exception {
        // 💳 Pay first: a pending request holds its seats, so another pending group of 9 leaves 1 of 10 seats
        Visitor v = upcoming("PENDING");
        LocalDate oldDate = v.getVisitDate();
        LocalDate newDate = LocalDate.now().plusDays(9);
        Visitor otherPending = upcoming("PENDING");
        otherPending.setId(8L);
        otherPending.setVisitDate(newDate);
        otherPending.setGroupSize(9);
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        when(bookingRepo.findAll()).thenReturn(List.of(slot(newDate, 10)));
        when(repo.findByVisitDate(newDate)).thenReturn(List.of(otherPending));
        when(repo.findByEmailAndVisitDate("juan@example.com", newDate)).thenReturn(new java.util.ArrayList<>());

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(7L)).param("formToken", "tok-123")
                .param("visitDate", newDate.toString()).param("timeWindow", WINDOW))
            .andExpect(flash().attribute("error", "Not enough seats: only 1 left in that time slot."));
        assertThat(v.getVisitDate()).isEqualTo(oldDate);

        otherPending.setGroupSize(8); // now the group of 2 fits exactly
        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(7L)).param("formToken", "tok-123")
                .param("visitDate", newDate.toString()).param("timeWindow", WINDOW))
            .andExpect(flash().attribute("success", "Booking updated."));
        assertThat(v.getVisitDate()).isEqualTo(newDate);
    }

    @Test
    void rescheduleAcceptsSeparateDateAndTimeWindowFields() throws Exception {
        Visitor v = upcoming("PENDING");
        LocalDate newDate = LocalDate.now().plusDays(9);
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        when(bookingRepo.findAll()).thenReturn(List.of(slot(newDate, 10)));
        when(repo.findByVisitDate(newDate)).thenReturn(List.of());
        when(repo.findByEmailAndVisitDate("juan@example.com", newDate)).thenReturn(new java.util.ArrayList<>());

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(7L)).param("formToken", "tok-123")
                .param("visitDate", newDate.toString()).param("timeWindow", WINDOW))
            .andExpect(flash().attribute("success", "Booking updated."));
        assertThat(v.getVisitDate()).isEqualTo(newDate);
        assertThat(v.getTimeWindow()).isEqualTo(WINDOW);
    }

    @Test
    void rescheduleWithDateButNoTimeWindowIsRefused() throws Exception {
        Visitor v = upcoming("PENDING");
        when(repo.findById(7L)).thenReturn(Optional.of(v));

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(7L)).param("formToken", "tok-123")
                .param("visitDate", LocalDate.now().plusDays(9).toString()).param("timeWindow", ""))
            .andExpect(flash().attribute("error", "Please choose a time window for your selected date."));
        verify(repo, never()).save(any());
    }

    @Test
    void dashboardOffersDatePickerAndTimeWindowDropdown() throws Exception {
        Visitor v = upcoming("APPROVED");
        LocalDate other = LocalDate.now().plusDays(9);
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        when(bookingRepo.findAll()).thenReturn(List.of(slot(other, 10)));
        when(repo.findByVisitDate(other)).thenReturn(List.of());

        mvc.perform(get("/manage-booking").session(signedIn(7L)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("type=\"date\" id=\"mbDate\" name=\"visitDate\""),
                org.hamcrest.Matchers.containsString("id=\"mbWindow\" name=\"timeWindow\""),
                org.hamcrest.Matchers.containsString("class=\"vp-mcal\" id=\"mbCal\""),
                org.hamcrest.Matchers.containsString("\"date\":\"" + other + "\""))));
    }

    // --- Group bookings: only the group size, no guest names ---

    @Test
    void registrationFormBindsOnlyTheThreeCountsNeverNamesPricesTotalsOrTheConsentRecord() {
        Visitor target = new Visitor();
        WebDataBinder binder = new WebDataBinder(target, "visitor");
        controller.restrictVisitorFields(binder);
        binder.bind(new MutablePropertyValues(Map.ofEntries(Map.entry("companionNames", "Santos, Maria L."),
                Map.entry("eventJustification", "School field trip"), Map.entry("groupSize", "5"),
                Map.entry("discountedCount", "2"), Map.entry("regularCount", "3"), Map.entry("freeCount", "1"),
                Map.entry("admissionTotalCentavos", "1"), Map.entry("regularPriceCentavos", "1"), Map.entry("discountedPriceCentavos", "1"),
                Map.entry("termsAcceptedAt", "2020-01-01T00:00"), Map.entry("termsVersion", "forged"))));
        assertThat(target.getCompanionNames()).isNull();
        assertThat(target.getEventJustification()).isNull();
        assertThat(target.getGroupSize()).isEqualTo(1); // set by the server from the counts, never from the form
        assertThat(target.getDiscountedCount()).isEqualTo(2);
        assertThat(target.getRegularCount()).isEqualTo(3);
        assertThat(target.getFreeCount()).isEqualTo(1);
        assertThat(target.getAdmissionTotalCentavos()).isNull();
        assertThat(target.getRegularPriceCentavos()).isNull();
        assertThat(target.getDiscountedPriceCentavos()).isNull();
        assertThat(target.getTermsAcceptedAt()).isNull();
        assertThat(target.getTermsVersion()).isNull();
    }

    @Test
    void parseCompanionNamesTrimsAndDropsBlankLines() {
        assertThat(Visitor.parseCompanionNames(" Santos, Maria L. \r\n\n  \nReyes, Pedro")).containsExactly("Santos, Maria L.", "Reyes, Pedro");
        assertThat(Visitor.parseCompanionNames(null)).isEmpty();
    }

    @Test
    void manageDashboardShowsStoredGuestNamesReadOnlyAndHasNoGuestListField() throws Exception {
        Visitor v = upcoming("APPROVED");
        v.setCompanionNames("Santos, Maria L.\nReyes, Pedro");
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        when(bookingRepo.findAll()).thenReturn(List.of());
        mvc.perform(get("/manage-booking").session(signedIn(7L)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("<dt>Guest List</dt>"),
                org.hamcrest.Matchers.containsString("<span>Santos, Maria L.</span><br><span>Reyes, Pedro</span>"),
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("name=\"companionNames\"")))));

        v.setCompanionNames(null); // a new group booking: no Guest List row at all
        mvc.perform(get("/manage-booking").session(signedIn(7L)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<dt>Guest List</dt>"))));
    }

    @Test
    void deniedEntryBookingIsShownAsDeniedAndIsLockedForTheVisitor() throws Exception {
        Visitor v = upcoming("DENIED_ENTRY");
        v.setVisitDate(LocalDate.now());
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        mvc.perform(get("/manage-booking").session(signedIn(7L)))
            .andExpect(model().attribute("statusLabel", "Entry Denied"))
            .andExpect(model().attribute("reschedulable", false))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                org.hamcrest.Matchers.containsString("Entry was denied at the gate")));

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(7L)).param("formToken", "tok-123")
                .param("visitDate", LocalDate.now().plusDays(9).toString()).param("timeWindow", WINDOW))
            .andExpect(flash().attribute("error", "This booking can no longer be changed online."));
        verify(repo, never()).save(any());
    }

    // --- Silent security: a booking revoked by a ban looks like any other "Not Approved" booking ---

    private static final List<String> BAN_WORDS = List.of("ban", "blacklist", "biometric", "revoked", "restricted", "violation", "Security Threat");

    private static Visitor revokedByBan() {
        Visitor v = upcoming("REJECTED");
        v.setRejectionReason("Access revoked: visitor banned (Security Threat)"); // what ManagementController stores
        return v;
    }

    // The booking card only: the footer's Privacy Policy rightly mentions biometric data
    private static String bookingCard(String html) {
        String card = html.substring(html.indexOf("vp-status-tag"));
        return card.substring(0, card.indexOf("vp-footer"));
    }

    private static void assertNoBanWording(String text) {
        for (String word : BAN_WORDS) assertThat(text.toLowerCase()).as(word).doesNotContain(word.toLowerCase());
    }

    @Test
    void aBookingRevokedByABanShowsOnlyGenericTextAndIsLocked() throws Exception {
        Visitor v = revokedByBan();
        when(repo.findById(7L)).thenReturn(Optional.of(v));

        String html = mvc.perform(get("/manage-booking").session(signedIn(7L)))
            .andExpect(model().attribute("statusLabel", "Not Approved"))
            .andExpect(model().attribute("reschedulable", false))
            .andReturn().getResponse().getContentAsString();
        String card = bookingCard(html);
        assertThat(card).contains("Not Approved", "We were unable to approve this request.").doesNotContain("QR_");
        assertNoBanWording(card);

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(7L)).param("formToken", "tok-123")
                .param("visitDate", LocalDate.now().plusDays(9).toString()).param("timeWindow", WINDOW))
            .andExpect(flash().attribute("error", "This booking can no longer be changed online."));
        verify(repo, never()).save(any());
    }

    @Test
    void checkStatusShowsTheFriendlyLabelAndNoQrPromiseForABookingThatIsNoLongerPending() throws Exception {
        when(repo.findByEmailIgnoreCase("juan@example.com")).thenReturn(List.of(revokedByBan()));

        String html = mvc.perform(get("/check-status").param("email", "juan@example.com").param("referenceId", "PAF-A7X92K"))
            .andExpect(view().name("success"))
            .andExpect(model().attribute("statusLabel", "Not Approved"))
            .andReturn().getResponse().getContentAsString();
        String card = bookingCard(html);
        assertThat(card).contains(">Not Approved<", "This booking is no longer pending.", "href=\"/manage-booking\"")
            .doesNotContain(">REJECTED<", "Entry QR Code", "currently <span class=\"vp-status-tag\">PENDING");
        assertNoBanWording(card);
    }

    @Test
    void checkStatusOnAPendingBookingStillShowsTheNextStepsNote() throws Exception {
        when(repo.findByEmailIgnoreCase("juan@example.com")).thenReturn(List.of(visitor("PENDING_MANUAL_REVIEW", "PAF-A7X92K")));

        String html = mvc.perform(get("/check-status").param("email", "juan@example.com").param("referenceId", "PAF-A7X92K"))
            .andReturn().getResponse().getContentAsString();
        assertThat(bookingCard(html)).contains(">Pending Review<", "Next Steps:", "Entry QR Code")
            .doesNotContain("PENDING_MANUAL_REVIEW", "no longer pending");
    }

    @Test
    void theRefusalForABanIsCompletelyGeneric() {
        assertThat(com.paf.aerovisit.service.IdentityBans.BANNED_MESSAGE).isEqualTo(
            "Booking Failed. We are unable to process your request at this time. Please try again later or contact the reception desk for assistance.");
        assertNoBanWording(com.paf.aerovisit.service.IdentityBans.BANNED_MESSAGE);
        assertThat(com.paf.aerovisit.service.IdentityBans.BANNED_MESSAGE.toLowerCase()).doesNotContain("face", "account", "identity");
    }

    @Test
    void anOlderMasterBookingCanBeRescheduledLikeAnyBooking() throws Exception {
        Visitor v = upcoming("PENDING");
        v.setPurpose("Educational Tour (Master Booking Request)");
        LocalDate newDate = LocalDate.now().plusDays(9);
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        when(bookingRepo.findAll()).thenReturn(List.of(slot(newDate, 10)));
        when(repo.findByVisitDate(newDate)).thenReturn(List.of());
        when(repo.findByEmailAndVisitDate("juan@example.com", newDate)).thenReturn(new java.util.ArrayList<>());
        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn(7L)).param("formToken", "tok-123")
                .param("visitDate", newDate.toString()).param("timeWindow", WINDOW))
            .andExpect(flash().attribute("success", "Booking updated."));
        assertThat(v.getVisitDate()).isEqualTo(newDate);
        assertThat(v.getStatus()).isEqualTo("PENDING");
    }
}
