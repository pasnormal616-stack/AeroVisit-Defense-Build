package com.paf.aerovisit.controller;

import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
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
import com.paf.aerovisit.model.Holiday;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.HolidayRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.Holidays;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** 🎌 Listed holidays on the public side: the booking calendar, new bookings and Manage Booking. No database. */
@WebMvcTest(VisitorPortalController.class)
@Import({SecurityConfig.class, Holidays.class})
class HolidayPortalTest {

    private static final String WINDOW = "Morning (09:30 AM - 10:30 AM)";
    private static final LocalDate HOLIDAY = LocalDate.now().plusDays(4);
    private static final LocalDate OPEN = LocalDate.now().plusDays(6);
    private static final String CLOSED = "Closed: Bonifacio Day. Please choose another date.";

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private HolidayRepository holidayRepo;

    @BeforeEach
    void bonifacioDay() {
        when(holidayRepo.findAllByOrderByHolidayDateAsc()).thenReturn(List.of(new Holiday(HOLIDAY, "Bonifacio Day", false)));
    }

    private static BookingSlot slot(LocalDate date) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(WINDOW);
        s.setMaxCapacity(50);
        return s;
    }

    private static Visitor booking(LocalDate date) {
        Visitor v = new Visitor();
        v.setId(7L);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setStatus("APPROVED");
        v.setReferenceId("PAF-A7X92K");
        v.setVisitDate(date);
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
    void theCalendarShowsClosedNameAndOffersNoSlotOnTheHoliday() throws Exception {
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(any())).thenReturn(List.of(slot(HOLIDAY), slot(OPEN)));

        MvcResult result = mvc.perform(get("/")).andExpect(status().isOk()).andReturn();

        Map<String, Object> model = result.getModelAndView().getModel();
        assertThat((Map<String, String>) model.get("closedDays")).containsExactly(Map.entry(HOLIDAY.toString(), "Closed: Bonifacio Day"));
        assertThat((List<Map<String, Object>>) model.get("calendarSlots")).extracting(s -> s.get("date")).containsExactly(OPEN.toString());
        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("var closedDays = {\"" + HOLIDAY + "\":\"Closed: Bonifacio Day\"};")
            .contains("if (closedDays[date]) return ['vp-day-closed'];")
            .contains("closed.className = 'vp-day-closed-label';")
            // the full text is listed under the calendar too, for phones where the cell only says "Closed"
            .contains("<ul class=\"vp-closed-note\" id=\"closedDaysNote\" hidden></ul>")
            .contains("li.textContent = d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }) + ' · ' + closedDays[date];");
    }

    @Test
    void aNewBookingOnTheHolidayIsRefused() throws Exception {
        mvc.perform(multipart("/save")
                .file(new MockMultipartFile("highResIdImage", new byte[0]))
                .param("fullName", "Juan Dela Cruz").param("email", "juan@example.com")
                .param("visitDate", HOLIDAY.toString()).param("timeWindow", WINDOW).param("termsAccepted", "true")
                .with(csrf()).with(kycNonce()))
            .andExpect(redirectedUrl("/"))
            .andExpect(flash().attribute("error", CLOSED));
        verify(repo, never()).save(any());
    }

    @Test
    void manageBookingCannotMoveABookingOntoTheHoliday() throws Exception {
        Visitor v = booking(LocalDate.now().plusDays(2));
        LocalDate original = v.getVisitDate();
        when(repo.findById(7L)).thenReturn(Optional.of(v));
        when(bookingRepo.findAll()).thenReturn(List.of(slot(HOLIDAY), slot(OPEN)));

        mvc.perform(post("/manage-booking/reschedule").with(csrf()).session(signedIn()).param("formToken", "tok-123")
                .param("visitDate", HOLIDAY.toString()).param("timeWindow", WINDOW))
            .andExpect(redirectedUrl("/manage-booking"))
            .andExpect(flash().attribute("error", CLOSED));
        assertThat(v.getVisitDate()).isEqualTo(original);
        verify(repo, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void manageBookingOffersNoHolidayDatesNotEvenTheBookingsOwnDate() throws Exception {
        when(bookingRepo.findAll()).thenReturn(List.of(slot(HOLIDAY), slot(OPEN)));
        when(repo.findById(7L)).thenReturn(Optional.of(booking(LocalDate.now().plusDays(2))));

        MvcResult result = mvc.perform(get("/manage-booking").session(signedIn())).andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> open = (List<Map<String, Object>>) result.getModelAndView().getModel().get("openSlots");
        assertThat(open).extracting(o -> o.get("date")).containsExactly(OPEN.toString());

        // 📅 a booking already on the holiday (listed after it was made) isn't offered that date either: it follows
        // the rules of a new advance booking
        Visitor onHoliday = booking(HOLIDAY);
        onHoliday.setTimeWindow("Afternoon (01:00 PM - 02:00 PM)");
        when(repo.findById(7L)).thenReturn(Optional.of(onHoliday));
        result = mvc.perform(get("/manage-booking").session(signedIn())).andExpect(status().isOk()).andReturn();
        open = (List<Map<String, Object>>) result.getModelAndView().getModel().get("openSlots");
        assertThat(open).extracting(o -> o.get("date")).containsExactly(OPEN.toString());
    }
}
