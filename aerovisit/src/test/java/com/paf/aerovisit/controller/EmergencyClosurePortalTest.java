package com.paf.aerovisit.controller;

import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Emergency closure on the public side: calendar, new bookings, Manage Booking. No database. */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
class EmergencyClosurePortalTest {

    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate CLOSED = LocalDate.now().plusDays(4);
    private static final LocalDate OPEN = LocalDate.now().plusDays(6);
    private static final String CLOSED_MESSAGE = "The museum is closed on this date due to an emergency. Please choose another date.";

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static BookingSlot slot(LocalDate date, int max) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(WINDOW);
        s.setMaxCapacity(max);
        return s;
    }

    private static Visitor booking(String status) {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setStatus(status);
        v.setReferenceId("PAF-A7X92K");
        v.setVisitDate(LocalDate.now().plusDays(2));
        v.setTimeWindow(WINDOW);
        v.setGroupSize(2);
        return v;
    }

    private static MockHttpSession signedIn() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("manageVisitorId", 7L);
        session.setAttribute("manageFormToken", "tok-123");
        return session;
    }

    @Test
    @SuppressWarnings("unchecked")
    void calendarShowsNoSeatsOnAClosedDate() throws Exception {
        when(bookingRepo.findAll()).thenReturn(List.of(slot(CLOSED, 50), slot(OPEN, 50)));
        when(repo.findClosureDates()).thenReturn(List.of(CLOSED));

        MvcResult result = mvc.perform(get("/")).andExpect(status().isOk()).andReturn();

        List<Map<String, Object>> slots = (List<Map<String, Object>>) result.getModelAndView().getModel().get("calendarSlots");
        assertThat(slots).filteredOn(s -> s.get("date").equals(CLOSED.toString())).extracting(s -> s.get("remaining")).containsExactly(0);
        assertThat(slots).filteredOn(s -> s.get("date").equals(OPEN.toString())).extracting(s -> s.get("remaining")).containsExactly(50);
    }

    @Test
    void newBookingForAClosedDateIsRefused() throws Exception {
        when(repo.findClosureDates()).thenReturn(List.of(CLOSED));

        mvc.perform(multipart("/save")
                .file(new MockMultipartFile("livenessImage", new byte[0]))
                .file(new MockMultipartFile("highResIdImage", new byte[0]))
                .param("fullName", "Juan Dela Cruz").param("email", "juan@example.com")
                .param("visitDate", CLOSED.toString()).param("timeWindow", WINDOW)
                .param("isMasterBooking", "true").with(csrf()).with(kycNonce()))  // not even a VIP buyout request may book a closed day
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", CLOSED_MESSAGE));

        verify(repo, never()).save(any());
    }

    @Test
    void rescheduleToAClosedDateIsRefused() throws Exception {
        Visitor v = booking("APPROVED");
        LocalDate original = v.getVisitDate();
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        when(repo.findClosureDates()).thenReturn(List.of(CLOSED));
        when(bookingRepo.findAll()).thenReturn(List.of(slot(CLOSED, 50)));

        mvc.perform(post("/manage-booking/update").with(csrf()).session(signedIn()).param("formToken", "tok-123")
                .param("slot", CLOSED + "|" + WINDOW))
            .andExpect(redirectedUrl("/manage-booking"))
            .andExpect(flash().attribute("error", CLOSED_MESSAGE));

        assertThat(v.getVisitDate()).isEqualTo(original);
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        verify(repo, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rescheduleOptionsLeaveOutClosedDates() throws Exception {
        when(repo.findById(7L)).thenReturn(Optional.of(booking("APPROVED")));
        when(bookingRepo.findAll()).thenReturn(List.of(slot(CLOSED, 50), slot(OPEN, 50)));
        when(repo.findClosureDates()).thenReturn(List.of(CLOSED));

        MvcResult result = mvc.perform(get("/manage-booking").session(signedIn())).andExpect(status().isOk()).andReturn();

        List<Map<String, Object>> open = (List<Map<String, Object>>) result.getModelAndView().getModel().get("openSlots");
        assertThat(open).extracting(o -> o.get("date")).containsExactly(OPEN.toString());
    }

    @Test
    void closureCancelledBookingExplainsItselfAndCannotBeChanged() throws Exception {
        when(repo.findById(7L)).thenReturn(Optional.of(booking("CANCELLED")));

        mvc.perform(get("/manage-booking").session(signedIn()))
            .andExpect(status().isOk())
            .andExpect(model().attribute("statusLabel", "Cancelled by Museum"))
            .andExpect(model().attribute("editable", false))
            .andExpect(model().attribute("cancellable", false))
            .andExpect(content().string(containsString("The museum is closed on this date due to an emergency")));
    }
}
