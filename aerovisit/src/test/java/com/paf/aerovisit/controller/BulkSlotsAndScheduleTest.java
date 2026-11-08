package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.paf.aerovisit.FixedClock;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Holiday;
import com.paf.aerovisit.model.TimeWindowConfig;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.HolidayRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.Holidays;

/**
 * Bulk Slot Generator with many short windows (several per run, capacity 50, no duplicates, holidays skipped) and the
 * Active Museum Schedule's exact times and capacity. No database.
 */
@WebMvcTest(ManagementController.class)
@Import({SecurityConfig.class, FixedClock.class, Holidays.class})
@WithMockUser(roles = "DIRECTOR")
class BulkSlotsAndScheduleTest {

    private static final TimeWindowConfig W1 = new TimeWindowConfig("Tour 1", LocalTime.of(9, 30), LocalTime.of(10, 30));
    private static final TimeWindowConfig W2 = new TimeWindowConfig("Tour 2", LocalTime.of(10, 30), LocalTime.of(11, 30));
    private static final TimeWindowConfig W3 = new TimeWindowConfig("Tour 3", LocalTime.of(15, 0), LocalTime.of(16, 0));
    // A Tuesday-to-Thursday week in November 2026 (the 30th is Bonifacio Day, a Monday)
    private static final LocalDate TUE = LocalDate.of(2026, 11, 24);

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;
    @MockitoBean private HolidayRepository holidayRepo;

    private static BookingSlot slot(LocalDate date, TimeWindowConfig w, int capacity) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(w.getLabel());
        s.setMaxCapacity(capacity);
        return s;
    }

    private List<BookingSlot> savedSlots(int expected) {
        ArgumentCaptor<BookingSlot> saved = ArgumentCaptor.forClass(BookingSlot.class);
        verify(bookingRepo, times(expected)).save(saved.capture());
        return saved.getAllValues();
    }

    private String staffPage() throws Exception {
        return mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // --- 1. Many windows at once ---

    @Test
    void oneRunCreatesASlotForEveryTickedWindowOnEveryIncludedDay() throws Exception {
        mvc.perform(post("/admin/slots/add").with(csrf())
                .param("startDate", TUE.toString()).param("endDate", TUE.plusDays(2).toString())
                .param("timeWindow", W1.getLabel(), W2.getLabel(), W3.getLabel()).param("maxCapacity", "50"))
            .andExpect(flash().attribute("success", "✅ Bulk Scheduling Complete: 9 slots opened for 3 time windows."));

        List<BookingSlot> saved = savedSlots(9);
        assertThat(saved).extracting(BookingSlot::getMaxCapacity).containsOnly(50);   // capacity applies to each window
        assertThat(saved).filteredOn(s -> s.getSlotDate().equals(TUE)).extracting(BookingSlot::getTimeWindow)
            .containsExactly(W1.getLabel(), W2.getLabel(), W3.getLabel());
    }

    @Test
    void aDateAndWindowThatAlreadyHasASlotIsNotDuplicated() throws Exception {
        when(bookingRepo.findBySlotDateBetween(TUE, TUE.plusDays(1))).thenReturn(List.of(slot(TUE, W1, 80), slot(TUE.plusDays(1), W2, 80)));

        mvc.perform(post("/admin/slots/add").with(csrf())
                .param("startDate", TUE.toString()).param("endDate", TUE.plusDays(1).toString())
                .param("timeWindow", W1.getLabel(), W2.getLabel(), W1.getLabel()).param("maxCapacity", "50"))   // W1 ticked twice
            .andExpect(flash().attribute("success", "✅ Bulk Scheduling Complete: 2 slots opened for 2 time windows. 2 already existed."));

        assertThat(savedSlots(2)).extracting(s -> s.getSlotDate() + "|" + s.getTimeWindow())
            .containsExactly(TUE + "|" + W2.getLabel(), TUE.plusDays(1) + "|" + W1.getLabel());
    }

    @Test
    void tickingNoWindowIsRefused() throws Exception {
        mvc.perform(post("/admin/slots/add").with(csrf())
                .param("startDate", TUE.toString()).param("endDate", TUE.toString()).param("maxCapacity", "50"))
            .andExpect(flash().attribute("error", "Tick at least one time window. No slots were created."));
        verify(bookingRepo, never()).save(any());
    }

    @Test
    void theFormTicksWindowsWithSelectAllAndStartsAtFiftyPerWindow() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(W1, W2, W3));
        String html = staffPage();
        String form = html.substring(html.indexOf("action=\"/admin/slots/add\""), html.indexOf("</form>", html.indexOf("action=\"/admin/slots/add\"")));

        assertThat(form).contains("id=\"bulkSelectAll\"").contains(">Select all</label>")
            .contains("name=\"timeWindow\" value=\"" + W1.getLabel() + "\"")
            .contains("name=\"timeWindow\" value=\"" + W3.getLabel() + "\"")
            .contains("Max Capacity (per window)")
            .containsPattern("name=\"maxCapacity\"[^>]*value=\"50\"")
            .doesNotContain("value=\"100\"").doesNotContain("<select id=\"bulkTimeWindow\"");
    }

    // --- 4. Holidays: always skipped, listed in the message; a single day on a holiday is refused ---

    @Test
    void bulkCreationSkipsListedHolidaysAndNamesThem() throws Exception {
        when(holidayRepo.findAllByOrderByHolidayDateAsc()).thenReturn(List.of(
            new Holiday(LocalDate.of(2026, 11, 30), "Bonifacio Day", false),
            new Holiday(LocalDate.of(2000, 12, 25), "Christmas Day", true)));

        mvc.perform(post("/admin/slots/add").with(csrf())
                .param("startDate", "2026-11-29").param("endDate", "2026-12-26")
                .param("timeWindow", W1.getLabel()).param("maxCapacity", "50").param("skipDays", "SUNDAY"))
            .andExpect(flash().attribute("success", "✅ Bulk Scheduling Complete: 22 days successfully opened for " + W1.getLabel() + "."
                + " Skipped 4 by weekday (Sun). Skipped 2 holidays: Nov 30 (Bonifacio Day), Dec 25 (Christmas Day)."));

        assertThat(savedSlots(22)).extracting(BookingSlot::getSlotDate)
            .doesNotContain(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 25));
    }

    @Test
    void aSingleSlotOnAHolidayIsRefusedWithItsName() throws Exception {
        when(holidayRepo.findAllByOrderByHolidayDateAsc()).thenReturn(List.of(new Holiday(LocalDate.of(2026, 11, 30), "Bonifacio Day", false)));

        mvc.perform(post("/admin/slots/add").with(csrf())
                .param("startDate", "2026-11-30").param("endDate", "2026-11-30")
                .param("timeWindow", W1.getLabel()).param("maxCapacity", "50"))
            .andExpect(flash().attribute("error", "⛔ No slot created: Nov 30, 2026 is a holiday (Bonifacio Day)."));
        verify(bookingRepo, never()).save(any());
    }

    // --- 2. Active Museum Schedule: exact times and capacity, in time order ---

    @Test
    void slotTimesAndSummaryShowTheExactTimesAndSeats() {
        assertThat(ManagementController.slotTimes(W1.getLabel())).isEqualTo("9:30 AM – 10:30 AM");
        assertThat(ManagementController.slotTimes("Morning (0800H-1200H)")).isEqualTo("8:00 AM – 12:00 PM");
        assertThat(ManagementController.slotTimes("Standard Shift")).isEqualTo("Standard Shift");
        assertThat(ManagementController.slotSummary(W1.getLabel(), 50, 12)).isEqualTo("9:30 AM – 10:30 AM · 50 seats (12 booked)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theScheduleListsEachDaysSlotsInTimeOrderWithSeatsAndBookings() throws Exception {
        LocalDate day = FixedClock.TODAY.plusDays(3);
        when(bookingRepo.findAll()).thenReturn(new ArrayList<>(List.of(slot(day, W3, 40), slot(day, W1, 50), slot(day, W2, 50))));
        when(repo.sumSeatsHeldFrom(any(), any())).thenReturn(List.<Object[]>of(new Object[] {day, W1.getLabel(), 12L}));
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(W1, W2, W3));

        MvcResult result = mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn();
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.getModelAndView().getModel().get("slotEntries");
        assertThat(entries).extracting(e -> e.get("summary")).containsExactly(
            "9:30 AM – 10:30 AM · 50 seats (12 booked)",
            "10:30 AM – 11:30 AM · 50 seats (0 booked)",
            "3:00 PM – 4:00 PM · 40 seats (0 booked)");
        assertThat(entries).extracting(e -> e.get("order")).containsExactly(570, 630, 900);

        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("eventOrder: 'order'").contains("title: slot.summary")
            .doesNotContain("av-legend--guard").doesNotContain(">Other window<").doesNotContain("label = \"AM\"");
    }
}
