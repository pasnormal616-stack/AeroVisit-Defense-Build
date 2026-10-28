package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * ⚡ The public booking page is opened by everybody, so it must not load every visitor ever saved (face data included)
 * just to show seats left: the database totals the seats for today onwards. No database here: the totals are mocked.
 */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
class BookingCalendarLoadTest {

    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final String AFTERNOON = "Afternoon (01:00 PM - 05:00 PM)";
    private static final LocalDate DAY = LocalDate.now().plusDays(3);

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static BookingSlot slot(String window, int max) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(DAY);
        s.setTimeWindow(window);
        s.setMaxCapacity(max);
        return s;
    }

    @Test
    @SuppressWarnings("unchecked")
    void seatsLeftComeFromTheDatabaseTotalsNotFromEveryVisitor() throws Exception {
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(any())).thenReturn(List.of(slot(MORNING, 100), slot(AFTERNOON, 50)));
        when(repo.sumSeatsHeldFrom(any(), any())).thenReturn(List.<Object[]>of(new Object[] {DAY, MORNING, 37L}));

        List<Map<String, Object>> slots = (List<Map<String, Object>>) mvc.perform(get("/")).andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("calendarSlots");

        assertThat(slots).filteredOn(s -> MORNING.equals(s.get("timeWindow")))
                .extracting(s -> s.get("booked"), s -> s.get("remaining")).containsExactly(org.assertj.core.groups.Tuple.tuple(37, 63));
        assertThat(slots).filteredOn(s -> AFTERNOON.equals(s.get("timeWindow")))
                .extracting(s -> s.get("remaining")).containsExactly(50);
        verify(repo, never()).findAll();
        verify(bookingRepo, never()).findAll();
        verify(repo).sumSeatsHeldFrom(LocalDate.now(), Visitor.SEAT_HOLDING_STATUSES); // pending requests hold no seats
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSlotNeverShowsMoreTakenThanItHas() throws Exception {
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(any())).thenReturn(List.of(slot(MORNING, 10)));
        when(repo.sumSeatsHeldFrom(any(), any())).thenReturn(List.<Object[]>of(new Object[] {DAY, MORNING, 14L})); // overbooked by a VIP override

        List<Map<String, Object>> slots = (List<Map<String, Object>>) mvc.perform(get("/"))
                .andReturn().getModelAndView().getModel().get("calendarSlots");
        assertThat(slots).extracting(s -> s.get("remaining")).containsExactly(0);
    }
}
