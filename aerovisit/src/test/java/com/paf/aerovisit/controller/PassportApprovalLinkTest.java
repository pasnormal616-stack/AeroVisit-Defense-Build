package com.paf.aerovisit.controller;

import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.test.web.servlet.MvcResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.paf.aerovisit.FixedClock;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.PassportReview;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.PassportReviewRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.AdmissionPricing;
import com.paf.aerovisit.service.BillingNumberService;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PassportStorage;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.PredictiveAdvisorService;
import com.paf.aerovisit.service.SeatReservationService;

/**
 * 🛂 Foreign advance booking, step 2: the approval link. It opens the normal booking form (Foreign + Advance, the
 * reviewed details) and from there /save books exactly like a local visitor (pay first: AWAITING_RECEIPT, payment page).
 * Opening the link never uses it up; it books one visit (the review becomes USED at /save). Reopened, it resumes: the
 * date and time picked so far, or the booking's payment page until the receipt is uploaded, then "already used". It
 * works for 14 days after approval, whatever happened to it. A Foreign advance booking without an approved review is
 * refused. No database.
 */
@WebMvcTest({VisitorPortalController.class, PassportController.class, PaymentController.class})
@Import({FixedClock.class, SecurityConfig.class, PaymentWorkflow.class, PassportStorage.class})
class PassportApprovalLinkTest {

    private static final LocalDate DAY = FixedClock.TODAY.plusDays(3);
    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final String EMAIL = "anna.schmidt@example.de";
    private static final String NAME = "Schmidt, Anna";
    private static final String TOKEN = "approval-token-5";
    private static final String FILE = "passport-11111111-2222-3333-4444-555555555555.pdf";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private OtpService otpService;
    @MockitoBean private AdmissionPricing admissionPricing;
    @MockitoBean private BillingNumberService billingNumbers;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private SeatReservationService seatReservation;
    @MockitoBean private PassportReviewRepository reviews;
    @MockitoBean private com.paf.aerovisit.service.NotificationService notificationService;

    private final List<Visitor> bookings = new ArrayList<>();
    private PassportReview review;

    @BeforeEach
    void setUp() {
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(DAY);
        slot.setTimeWindow(WINDOW);
        slot.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(DAY, WINDOW)).thenReturn(List.of(slot));
        when(admissionPricing.current()).thenReturn(new AdmissionPricing.Prices(10_000L, 15_000L));
        when(billingNumbers.next()).thenReturn("000555");
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(otpService.isVerified(anyString())).thenReturn(false); // the approval link stands in for the code
        when(seatReservation.saveIfSeatsRemain(any(Visitor.class))).thenAnswer(inv -> {
            Visitor v = inv.getArgument(0);
            if (v.getId() == null) v.setId(77L);
            bookings.add(v);
            return v;
        });
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.findById(anyLong())).thenAnswer(inv -> bookings.stream().filter(b -> b.getId().equals(inv.getArgument(0))).findFirst());
        // The calendar: DAY has one open window (the booking page's slot list)
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(any())).thenReturn(List.of(slot));

        review = new PassportReview();
        review.setId(5L);
        review.setFullName(NAME);
        review.setEmail(EMAIL);
        review.setContactNumber("+49 151 2345678");
        review.setFileName(FILE);
        review.setStatus(PassportReview.APPROVED);
        review.setToken(TOKEN);
        review.setTokenExpiresAt(FixedClock.NOW.plusDays(PassportReview.APPROVAL_LINK_DAYS));
        review.setDecidedBy("director");
        review.setDecidedAt(FixedClock.NOW);
        when(reviews.findByToken(TOKEN)).thenReturn(Optional.of(review));
        when(reviews.findById(5L)).thenReturn(Optional.of(review));
        when(reviews.save(any(PassportReview.class))).thenAnswer(inv -> inv.getArgument(0));
        // The conditional claim: only an APPROVED, unexpired review is claimed
        when(reviews.claimApproval(eq(5L), any())).thenAnswer(inv -> {
            boolean ok = PassportReview.APPROVED.equals(review.getStatus()) && review.getTokenExpiresAt().isAfter(inv.getArgument(1));
            if (ok) review.setStatus(PassportReview.USED);
            return ok ? 1 : 0;
        });
        // The draft UPDATE: only while still APPROVED
        when(reviews.saveDraft(eq(5L), any(), any())).thenAnswer(inv -> {
            if (!PassportReview.APPROVED.equals(review.getStatus())) return 0;
            review.setDraftVisitDate(inv.getArgument(1));
            review.setDraftTimeWindow(inv.getArgument(2));
            return 1;
        });
    }

    // The link opened again in a fresh browser (another device: no session from before)
    private MvcResult reopen() throws Exception {
        return mvc.perform(get("/passport/continue").param("token", TOKEN)).andReturn();
    }

    private String formFor(MockHttpSession session) throws Exception {
        return mvc.perform(get("/").session(session)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // The receipt arrived: the booking leaves "waiting for payment" (PaymentController /payment/proof does this)
    private static void receiptUploaded(Visitor v) {
        v.setPaymentStatus(Visitor.PAY_PROOF_SUBMITTED);
        v.setStatus("PENDING");
    }

    private MockHttpSession openLink() throws Exception {
        return (MockHttpSession) mvc.perform(get("/passport/continue").param("token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/"))
                .andExpect(header().doesNotExist("Location"))
                .andReturn().getRequest().getSession();
    }

    // The opening tag of the element with this id
    private static String tagWithId(String page, String id) {
        Matcher m = Pattern.compile("<[a-z]+[^>]*\\bid=\"" + id + "\"[^>]*>").matcher(page);
        assertThat(m.find()).as("element #" + id).isTrue();
        return m.group();
    }

    private static boolean hidden(String page, String id) {
        return Pattern.compile("\\bclass=\"[^\"]*\\bd-none\\b").matcher(tagWithId(page, id)).find();
    }

    private static MockMultipartHttpServletRequestBuilder foreignAdvance(MockHttpSession session) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.session(session).with(csrf()).with(kycNonce());
        req.param("nationality", "FOREIGN")
           .param("fullName", "Someone Else").param("email", "other@example.com").param("contactNumber", "000") // ignored
           .param("visitDate", DAY.toString()).param("timeWindow", WINDOW)
           .param("discountedCount", "0").param("regularCount", "2").param("freeCount", "0").param("termsAccepted", "true");
        return req;
    }

    private String linkPage() throws Exception {
        return mvc.perform(get("/passport/continue").param("token", TOKEN))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // --- Works once, within its window ---

    @Test
    void theLinkOpensTheBookingFormWithTheReviewedDetailsAndBooksOnceLikeALocalVisitor() throws Exception {
        MockHttpSession session = openLink();
        assertThat(session.getAttribute(PassportController.FOREIGN_REVIEW_ID)).isEqualTo(5L);

        String form = mvc.perform(get("/").session(session)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(form).contains("id=\"foreignApproved\"").contains("data-email=\"" + EMAIL + "\"").contains("Passport approved for");

        mvc.perform(foreignAdvance(session)).andExpect(redirectedUrl("/manage-booking/payment"));

        assertThat(bookings).hasSize(1);
        Visitor v = bookings.get(0);
        // The identical local flow from here: pay first, seats held, payment page
        assertThat(v.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
        assertThat(v.getBillingNumber()).isEqualTo("000555");
        verify(emailService).sendPaymentRequest(any());
        // The reviewed identity, not what the form sent; the passport is its ID (no ID photo)
        assertThat(v.getFullName()).isEqualTo(NAME);
        assertThat(v.getEmail()).isEqualTo(EMAIL);
        assertThat(v.getContactNumber()).isEqualTo("+49 151 2345678");
        assertThat(v.getNationality()).isEqualTo(Visitor.NATIONALITY_FOREIGN);
        assertThat(v.getPassportFileUrl()).isEqualTo(FILE);
        assertThat(v.getPassportStatus()).isEqualTo(Visitor.PASSPORT_APPROVED);
        assertThat(v.getPassportReviewedBy()).isEqualTo("director");
        assertThat(v.getIdCardImageUrl()).isNull();
        // The approval is used up and now belongs to this booking
        assertThat(review.getStatus()).isEqualTo(PassportReview.USED);
        assertThat(review.getVisitorId()).isEqualTo(77L);
        assertThat(review.getFileName()).isNull();
        assertThat(session.getAttribute(PassportController.FOREIGN_REVIEW_ID)).isNull();

        // The same link again while the booking waits for its receipt: that booking's payment page, not a second booking
        assertThat(reopen().getResponse().getForwardedUrl()).isEqualTo("/manage-booking/payment");
        // Once the receipt is uploaded: already used
        receiptUploaded(v);
        assertThat(linkPage()).contains(PassportController.LINK_USED);
        assertThat(bookings).hasSize(1);
    }

    // --- The bug: the link landed on the plain form (SameSite=Strict cookie lost on the cross-site redirect) ---

    @Test
    void theLinkIsServedInTheSameRequestSoNoCookieHasToSurviveARedirect() throws Exception {
        // A forward, not a redirect: the browser makes no second request, so the Strict session cookie isn't needed
        MockHttpSession session = openLink();
        assertThat(session.getAttribute(PassportController.FOREIGN_REVIEW_ID)).isEqualTo(5L);
        verify(reviews, never()).claimApproval(anyLong(), any()); // landing alone does not use the approval up
    }

    @Test
    void theApprovedPageSkipsNationalityContactDetailsAndPassportAndOpensOnTheDate() throws Exception {
        MockHttpSession session = openLink();
        String page = mvc.perform(get("/").session(session)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(page).contains("id=\"foreignApproved\"").contains(EMAIL);
        // Nationality and Advance/Walk-in are not asked again
        assertThat(hidden(page, "nationalityChoice")).isTrue();
        assertThat(hidden(page, "nationalityHint")).isTrue();
        assertThat(hidden(page, "bookingTypeChoice")).isTrue();
        // Name, email and phone are carried over from the review, not typed again
        assertThat(hidden(page, "nameBlock")).isTrue();
        assertThat(hidden(page, "contactBlock")).isTrue();
        assertThat(tagWithId(page, "nameInput")).contains("value=\"" + NAME + "\"");
        assertThat(tagWithId(page, "emailInput")).contains("value=\"" + EMAIL + "\"");
        assertThat(tagWithId(page, "phoneInput")).contains("value=\"+49 151 2345678\"");
        // No passport upload: the passport panel stays hidden, and the steps (date first) are there
        assertThat(hidden(page, "passportPanel")).isTrue();
        assertThat(page).contains("id=\"bookingSteps\"");
        // And the page opens on Book a Visit
        assertThat(page).contains("Opened from a passport-approval link: straight to Book a Visit");

        // Booking from that page keeps the original details, whatever the (hidden) fields carry
        mvc.perform(foreignAdvance(session)).andExpect(redirectedUrl("/manage-booking/payment"));
        assertThat(bookings).hasSize(1);
        assertThat(bookings.get(0).getFullName()).isEqualTo(NAME);
        assertThat(bookings.get(0).getEmail()).isEqualTo(EMAIL);
        assertThat(bookings.get(0).getContactNumber()).isEqualTo("+49 151 2345678");
    }

    @Test
    void withoutAnApprovalThePageStillAsksNationalityAndContactDetails() throws Exception {
        String page = mvc.perform(get("/").session(new MockHttpSession())).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).doesNotContain("id=\"foreignApproved\"")
                        .doesNotContain("Opened from a passport-approval link: straight to Book a Visit");
        assertThat(hidden(page, "nationalityChoice")).isFalse();
        assertThat(hidden(page, "bookingTypeChoice")).isFalse();
        assertThat(hidden(page, "nameBlock")).isFalse();
        assertThat(hidden(page, "contactBlock")).isFalse();
    }

    // --- Viewing never uses the link up; a booking does (item 2) ---

    @Test
    void openingTheLinkTwiceBeforeBookingWorksBothTimesPrefilled() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockHttpSession session = openLink();
            assertThat(session.getAttribute(PassportController.FOREIGN_REVIEW_ID)).as("open #" + (i + 1)).isEqualTo(5L);
            String page = formFor(session);
            assertThat(page).contains("id=\"foreignApproved\"").doesNotContain(PassportController.LINK_USED);
            assertThat(tagWithId(page, "emailInput")).contains("value=\"" + EMAIL + "\"");
        }
        assertThat(review.getStatus()).isEqualTo(PassportReview.APPROVED);
        verify(reviews, never()).claimApproval(anyLong(), any());
        verify(reviews, never()).save(any());
    }

    @Test
    void refreshingMidBookingBeforePaymentDoesNotUseTheLinkUp() throws Exception {
        MockHttpSession session = openLink();
        formFor(session);
        formFor(session);                                   // refresh of the booking page
        mvc.perform(get("/passport/continue").param("token", TOKEN).session(session))
           .andExpect(forwardedUrl("/"));                   // refresh of the link's own URL
        assertThat(review.getStatus()).isEqualTo(PassportReview.APPROVED);
        // ...and the booking still goes through with it
        MockHttpSession again = openLink();
        mvc.perform(foreignAdvance(again)).andExpect(redirectedUrl("/manage-booking/payment"));
        assertThat(review.getStatus()).isEqualTo(PassportReview.USED);
    }

    @Test
    void completingTheBookingMarksTheLinkUsedThenASecondOpenSaysAlreadyUsed() throws Exception {
        MockHttpSession session = openLink();
        mvc.perform(foreignAdvance(session)).andExpect(redirectedUrl("/manage-booking/payment"));
        assertThat(review.getStatus()).isEqualTo(PassportReview.USED);
        assertThat(review.getVisitorId()).isEqualTo(77L);

        receiptUploaded(bookings.get(0)); // booking completed
        MvcResult second = reopen();
        assertThat(second.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains(PassportController.LINK_USED).doesNotContain("id=\"bookingSteps\"");
        assertThat(second.getResponse().getForwardedUrl()).isNotEqualTo("/manage-booking/payment");
        assertThat(bookings).hasSize(1);
    }

    // --- Progress is kept on the approval itself (item 3) ---

    @Test
    void aPickedDateAndTimeIsResumedWhenTheLinkIsReopenedOnAnotherDevice() throws Exception {
        MockHttpSession session = openLink();
        mvc.perform(post("/passport/draft").session(session).with(csrf())
                    .param("visitDate", DAY.toString()).param("timeWindow", WINDOW))
           .andExpect(status().isNoContent());
        assertThat(review.getDraftVisitDate()).isEqualTo(DAY);
        assertThat(review.getDraftTimeWindow()).isEqualTo(WINDOW);
        assertThat(review.getStatus()).isEqualTo(PassportReview.APPROVED); // saving progress does not use it up

        // A new browser (no session from before) opens the same link: the page reselects that date and time
        MockHttpSession otherDevice = (MockHttpSession) reopen().getRequest().getSession();
        String page = formFor(otherDevice);
        assertThat(tagWithId(page, "foreignApproved"))
                .contains("data-resume-date=\"" + DAY + "\"")
                .contains("data-resume-window=\"" + WINDOW + "\"");
        assertThat(page).contains("function resumeDraft()");
        // ...and a refresh of that page still has it
        assertThat(tagWithId(formFor(otherDevice), "foreignApproved")).contains("data-resume-date=\"" + DAY + "\"");
    }

    @Test
    void progressCanOnlyBeSavedFromAnOpenedUnusedApproval() throws Exception {
        // no approval link opened in this browser
        mvc.perform(post("/passport/draft").with(csrf()).param("visitDate", DAY.toString()).param("timeWindow", WINDOW))
           .andExpect(status().isForbidden());
        // without the CSRF token
        MockHttpSession session = openLink();
        mvc.perform(post("/passport/draft").session(session).param("visitDate", DAY.toString()).param("timeWindow", WINDOW))
           .andExpect(status().isForbidden());
        // nonsense or a past date
        mvc.perform(post("/passport/draft").session(session).with(csrf()).param("visitDate", "not-a-date").param("timeWindow", WINDOW))
           .andExpect(status().isBadRequest());
        mvc.perform(post("/passport/draft").session(session).with(csrf())
                    .param("visitDate", FixedClock.TODAY.minusDays(1).toString()).param("timeWindow", WINDOW))
           .andExpect(status().isBadRequest());
        assertThat(review.getDraftVisitDate()).isNull();
        // a page without a saved pick shows no resume data
        assertThat(tagWithId(formFor(session), "foreignApproved")).doesNotContain("data-resume-date");
    }

    @Test
    void aBookingLeftMidPaymentIsResumedInThatSameStateOnReopeningTheLink() throws Exception {
        MockHttpSession session = openLink();
        mvc.perform(foreignAdvance(session)).andExpect(redirectedUrl("/manage-booking/payment"));
        Visitor booked = bookings.get(0);
        assertThat(booked.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);

        // Later, on another device: the link opens that booking's payment page (same request, no redirect)
        MvcResult resumed = reopen();
        assertThat(resumed.getResponse().getForwardedUrl()).isEqualTo("/manage-booking/payment");
        assertThat(resumed.getResponse().getRedirectedUrl()).isNull();
        MockHttpSession otherDevice = (MockHttpSession) resumed.getRequest().getSession();
        assertThat(otherDevice.getAttribute(VisitorPortalController.MANAGE_VISITOR_ID)).isEqualTo(77L);
        assertThat(otherDevice.getAttribute(PassportController.FOREIGN_REVIEW_ID)).isNull(); // no second booking form

        // The payment page shows the same booking, in the same state: billing number, still waiting for the receipt
        String payment = mvc.perform(get("/manage-booking/payment").session(otherDevice))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(payment).contains("000555");
        assertThat(booked.getStatus()).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
        assertThat(bookings).hasSize(1);
    }

    @Test
    void aSecondBookingWithTheSameApprovalIsRefused() throws Exception {
        MockHttpSession first = openLink();
        MockHttpSession second = openLink(); // a second tab opened before the first one booked
        mvc.perform(foreignAdvance(first)).andExpect(redirectedUrl("/manage-booking/payment"));
        mvc.perform(foreignAdvance(second))
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", VisitorPortalController.FOREIGN_PASSPORT_FIRST));
        assertThat(bookings).hasSize(1);
    }

    @Test
    void aLostClaimRaceBooksNothing() throws Exception {
        MockHttpSession session = openLink();
        when(reviews.claimApproval(eq(5L), any())).thenReturn(0); // another tab claimed it a moment ago
        mvc.perform(foreignAdvance(session))
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", VisitorPortalController.FOREIGN_LINK_USED));
        verify(seatReservation, never()).saveIfSeatsRemain(any());
    }

    @Test
    void aFullSlotGivesTheApprovalBackSoTheLinkCanBookAnotherSlot() throws Exception {
        MockHttpSession session = openLink();
        when(seatReservation.saveIfSeatsRemain(any(Visitor.class))).thenThrow(new SeatReservationService.CapacityFullException());
        mvc.perform(foreignAdvance(session)).andExpect(redirectedUrl("/"));
        verify(reviews).releaseApproval(5L);
        verify(reviews, never()).save(any());
    }

    // --- Expiry ---

    @Test
    void theLinkWindowIsFourteenDays() {
        assertThat(PassportReview.APPROVAL_LINK_DAYS).isEqualTo(14);
    }

    @Test
    void anApprovalLinkWorksUpToFourteenDaysAfterApproval() throws Exception {
        review.setDecidedAt(FixedClock.NOW.minusDays(13).minusHours(23));
        review.setTokenExpiresAt(review.getDecidedAt().plusDays(PassportReview.APPROVAL_LINK_DAYS)); // 1 hour left
        MockHttpSession session = openLink();
        assertThat(formFor(session)).contains("id=\"foreignApproved\"");
    }

    @Test
    void anApprovalLinkOlderThanFourteenDaysIsRefusedEvenWithABookingMidPayment() throws Exception {
        MockHttpSession session = openLink();
        mvc.perform(foreignAdvance(session)).andExpect(redirectedUrl("/manage-booking/payment"));
        // 14 days and 1 minute after approval: refused, though the booking still waits for its receipt
        review.setTokenExpiresAt(FixedClock.NOW.minusMinutes(1));
        MvcResult result = reopen();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains(PassportController.LINK_EXPIRED);
        assertThat(result.getResponse().getForwardedUrl()).isNotEqualTo("/manage-booking/payment");
        assertThat(result.getRequest().getSession(false) == null
                || result.getRequest().getSession(false).getAttribute(VisitorPortalController.MANAGE_VISITOR_ID) == null).isTrue();
    }

    @Test
    void anApprovalLinkOlderThanFourteenDaysIsRefused() throws Exception {
        review.setDecidedAt(FixedClock.NOW.minusDays(14).minusMinutes(1));
        review.setTokenExpiresAt(review.getDecidedAt().plusDays(PassportReview.APPROVAL_LINK_DAYS)); // a minute ago
        MvcResult result = mvc.perform(get("/passport/continue").param("token", TOKEN)).andReturn();
        String page = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        // A clear "expired" message on its own page: not a silent redirect or forward to the plain form
        assertThat(page).contains(PassportController.LINK_EXPIRED).doesNotContain("id=\"bookingSteps\"");
        assertThat(result.getResponse().getRedirectedUrl()).isNull();
        assertThat(result.getResponse().getForwardedUrl()).isNotEqualTo("/");
        assertThat(result.getRequest().getSession(false) == null
                || result.getRequest().getSession(false).getAttribute(PassportController.FOREIGN_REVIEW_ID) == null).isTrue();
        verify(reviews, never()).claimApproval(anyLong(), any());
    }

    @Test
    void aLinkThatExpiresAfterOpeningTheFormCanNoLongerBook() throws Exception {
        MockHttpSession session = openLink();
        review.setTokenExpiresAt(FixedClock.NOW.minusSeconds(1));
        mvc.perform(foreignAdvance(session))
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", VisitorPortalController.FOREIGN_PASSPORT_FIRST));
        assertThat(bookings).isEmpty();
    }

    @Test
    void anUnknownOrRejectedTokenOpensNothing() throws Exception {
        String page = mvc.perform(get("/passport/continue").param("token", "nope"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains(PassportController.LINK_INVALID);
        review.setStatus(PassportReview.REJECTED);
        assertThat(linkPage()).contains(PassportController.LINK_INVALID);
    }

    // --- No approval, no Foreign advance booking ---

    @Test
    void aForeignAdvanceBookingWithoutAnApprovedPassportIsRefused() throws Exception {
        mvc.perform(foreignAdvance(new MockHttpSession()))
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", VisitorPortalController.FOREIGN_PASSPORT_FIRST));
        verify(seatReservation, never()).saveIfSeatsRemain(any());
        verify(reviews, never()).claimApproval(anyLong(), any());
    }
}
