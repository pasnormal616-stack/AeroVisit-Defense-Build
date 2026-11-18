package com.paf.aerovisit.controller;

import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.paf.aerovisit.FixedClock;
import com.paf.aerovisit.TestImages;
import com.paf.aerovisit.TestPdfs;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
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
import com.paf.aerovisit.service.WalkInService;

/**
 * 🛂 The Nationality choice on /save. LOCAL (or no choice at all, as every existing form and test sends) is exactly the
 * old flow: the ID photo is still required, the email code is still checked, no passport field is touched. FOREIGN is
 * advance only (passport review first): a Foreign walk-in is refused on the server, not only hidden on the page. No
 * database.
 */
@WebMvcTest(VisitorPortalController.class)
@Import({FixedClock.class, SecurityConfig.class, WalkInService.class, PaymentWorkflow.class, PassportStorage.class})
class NationalityBookingTest {

    private static final String EMAIL = "juan@example.com";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);
    private static final LocalDate ADVANCE_DAY = FixedClock.TODAY.plusDays(3);
    private static final String ADVANCE_WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final byte[] ID_PHOTO = TestImages.jpeg(44);

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
    @MockitoBean private PassportReviewRepository passportReviews;

    private String openWindow;

    private void slot(LocalDate day, String window) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(day);
        s.setTimeWindow(window);
        s.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(day, window)).thenReturn(List.of(s));
    }

    @BeforeEach
    void setUp() {
        LocalTime now = FixedClock.TIME;
        openWindow = "Open (" + CLOCK.format(now.minusHours(1).withSecond(0).withNano(0)) + " - "
                + CLOCK.format(now.plusHours(1).withSecond(0).withNano(0)) + ")";
        slot(FixedClock.TODAY, openWindow);
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(FixedClock.TODAY)).thenReturn(List.of());
        slot(ADVANCE_DAY, ADVANCE_WINDOW);
        when(admissionPricing.current()).thenReturn(new AdmissionPricing.Prices(10_000L, 15_000L));
        when(otpService.isVerified(EMAIL)).thenReturn(true);
        when(billingNumbers.next()).thenReturn("000888");
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        when(seatReservation.saveIfSeatsRemain(any(Visitor.class))).thenAnswer(inv -> {
            Visitor v = inv.getArgument(0);
            if (v.getId() == null) v.setId(51L);
            return v;
        });
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void removeTestUploads() throws Exception {
        try {
            ArgumentCaptor<Visitor> all = ArgumentCaptor.forClass(Visitor.class);
            verify(seatReservation, org.mockito.Mockito.atLeast(0)).saveIfSeatsRemain(all.capture());
            for (Visitor v : all.getAllValues()) {
                for (String f : new String[] {v.getIdCardImageUrl(), v.getPassportFileUrl()}) {
                    if (f != null) Files.deleteIfExists(Paths.get("uploads").resolve(f));
                }
            }
        } catch (org.mockito.exceptions.base.MockitoException ignored) {
            // nothing was saved
        }
    }

    private Visitor saved() {
        ArgumentCaptor<Visitor> captor = ArgumentCaptor.forClass(Visitor.class);
        verify(seatReservation).saveIfSeatsRemain(captor.capture());
        return captor.getValue();
    }

    private static MockMultipartHttpServletRequestBuilder advance(String nationality, boolean withIdPhoto) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        if (withIdPhoto) req.file(new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", ID_PHOTO));
        if (nationality != null) req.param("nationality", nationality);
        req.param("termsAccepted", "true").param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL)
           .param("contactNumber", "0917-555-0101").param("visitDate", ADVANCE_DAY.toString()).param("timeWindow", ADVANCE_WINDOW)
           .param("discountedCount", "0").param("regularCount", "2").param("freeCount", "0");
        return req;
    }

    private MockMultipartHttpServletRequestBuilder walkIn(String nationality) {
        MockMultipartHttpServletRequestBuilder req = multipart("/save");
        req.with(csrf()).with(kycNonce());
        req.param("bookingType", "WALK_IN").param("nationality", nationality).param("termsAccepted", "true")
           .param("fullName", "Schmidt, Anna").param("email", EMAIL).param("contactNumber", "+49 151 2345678")
           .param("timeWindow", openWindow).param("discountedCount", "0").param("regularCount", "0").param("freeCount", "2");
        return req;
    }

    // --- Local: unchanged ---

    @Test
    void noNationalityAndLocalBookExactlyAsBeforeWithTheIdPhotoAndNoPassport() throws Exception {
        for (String nationality : new String[] {null, "LOCAL"}) {
            org.mockito.Mockito.clearInvocations(seatReservation, emailService, otpService);
            mvc.perform(advance(nationality, true)).andExpect(redirectedUrl("/manage-booking/payment"));
            Visitor v = saved();
            assertThat(v.getStatus()).as(nationality).isEqualTo(Visitor.STATUS_AWAITING_RECEIPT);
            assertThat(v.getIdCardImageUrl()).isNotNull();
            assertThat(v.getFullName()).isEqualTo("Dela Cruz, Juan P.");
            assertThat(v.getNationality()).isEqualTo(Visitor.NATIONALITY_LOCAL);
            assertThat(v.foreign()).isFalse();
            assertThat(v.getPassportFileUrl()).isNull();
            assertThat(v.getPassportStatus()).isNull();
            verify(otpService).isVerified(EMAIL);
            verify(otpService).consume(EMAIL);
            verify(emailService).sendPaymentRequest(any());
            Files.deleteIfExists(Paths.get("uploads").resolve(v.getIdCardImageUrl()));
        }
        verify(passportReviews, never()).claimApproval(org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void localStillNeedsTheIdPhotoAndTheEmailCode() throws Exception {
        mvc.perform(advance("LOCAL", false))
           .andExpect(redirectedUrl("/"))
           .andExpect(flash().attribute("error", VisitorPortalController.PHOTOS_REQUIRED));
        when(otpService.isVerified(EMAIL)).thenReturn(false);
        mvc.perform(advance(null, true)).andExpect(redirectedUrl("/"));
        verify(seatReservation, never()).saveIfSeatsRemain(any());
    }

    @Test
    void aNationalityCanNotBeBoundFromTheForm() throws Exception {
        // only the server sets it from the choice: an unknown value is Local, and the passport fields are never bound
        MockMultipartHttpServletRequestBuilder req = advance("SOMETHING", true);
        req.param("passportStatus", "APPROVED").param("passportFileUrl", "passport-x.pdf");
        mvc.perform(req).andExpect(redirectedUrl("/manage-booking/payment"));
        Visitor v = saved();
        assertThat(v.getNationality()).isEqualTo(Visitor.NATIONALITY_LOCAL);
        assertThat(v.getPassportStatus()).isNull();
        assertThat(v.getPassportFileUrl()).isNull();
    }

    @Test
    void theBookingPageAsksForNationalityFirstAndShowsNoApprovalWithoutALink() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("id=\"nationalityChoice\"", "data-nationality=\"LOCAL\"", "data-nationality=\"FOREIGN\"",
                "id=\"passportPanel\"", "name=\"nationality\"").doesNotContain("id=\"foreignApproved\"");
    }

    // --- Foreign: advance only, no walk-in ---

    @Test
    void aForeignWalkInIsRefusedOnTheServerWhateverItSends() throws Exception {
        // a hand-made request, as the page no longer offers it: with a passport PDF, with an ID photo, or with nothing
        MockMultipartHttpServletRequestBuilder withPdf = walkIn("FOREIGN");
        withPdf.file(new MockMultipartFile("passportFile", "passport.pdf", "application/pdf", TestPdfs.pdf("walk-in")));
        MockMultipartHttpServletRequestBuilder withIdPhoto = walkIn("FOREIGN");
        withIdPhoto.file(new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", ID_PHOTO));
        for (MockMultipartHttpServletRequestBuilder req : List.of(withPdf, withIdPhoto, walkIn("FOREIGN"))) {
            mvc.perform(req)
               .andExpect(redirectedUrl("/"))
               .andExpect(flash().attribute("error", VisitorPortalController.FOREIGN_NO_WALK_IN));
        }
        verify(seatReservation, never()).saveIfSeatsRemain(any());
        verify(otpService, never()).consume(anyString());
    }

    @Test
    void theBookingPageHasNoForeignWalkInPassportUploadAndKeepsForeignOnAdvance() throws Exception {
        String html = mvc.perform(get("/")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        // the walk-in passport upload is gone; the only passport file field is the review form's (#passportPanel)
        assertThat(html).doesNotContain("walkInPassportBlock", "walkInPassportInput");
        assertThat(html.split("name=\"passportFile\"", -1)).hasSize(2);
        assertThat(html).contains("id=\"passportFileInput\" name=\"passportFile\"");
        // the nationality script hides the Advance / Walk-in choice for Foreign and switches a Walk-in pick back
        assertThat(html).contains("show($('bookingTypeChoice'), chosen && !foreign)")
                        .contains("Foreign is Advance only: back to Advance Booking");
        // Local still has its Walk-in card
        assertThat(html).contains("data-booking-type=\"WALK_IN\"");
    }
}
