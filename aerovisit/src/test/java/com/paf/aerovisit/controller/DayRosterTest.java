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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.paf.aerovisit.FixedClock;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.StaffSchedule;
import com.paf.aerovisit.model.TimeWindowConfig;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;

/**
 * The Duty Roster tab, one day at a time: opens on today, offers exactly that date's booking slots in time order, assigns
 * one person to several windows at once, and only refuses real overlaps (back-to-back windows are fine). No database.
 */
@WebMvcTest(ManagementController.class)
@Import({SecurityConfig.class, FixedClock.class})
@WithMockUser(roles = "DIRECTOR")
class DayRosterTest {

    private static final LocalDate TODAY = FixedClock.TODAY;
    private static final LocalDate DAY = TODAY.plusDays(2);
    private static final TimeWindowConfig T930 = new TimeWindowConfig("Tour", LocalTime.of(9, 30), LocalTime.of(10, 30));
    private static final TimeWindowConfig T1030 = new TimeWindowConfig("Tour", LocalTime.of(10, 30), LocalTime.of(11, 30));
    private static final TimeWindowConfig T1130 = new TimeWindowConfig("Tour", LocalTime.of(11, 30), LocalTime.of(12, 30));
    private static final TimeWindowConfig LONG = new TimeWindowConfig("Long Tour", LocalTime.of(10, 0), LocalTime.of(12, 0));

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    private final List<BookingSlot> slots = new ArrayList<>();

    @BeforeEach
    void bookingSlots() {
        when(bookingRepo.findAll()).thenReturn(slots);
        // listed out of order on purpose: the roster sorts them by start time
        slotsOn(TODAY, T1130, T930, T1030);
        slotsOn(DAY, T1030, T930, T1130);
    }

    private void slotsOn(LocalDate date, TimeWindowConfig... windows) {
        for (TimeWindowConfig w : windows) {
            BookingSlot s = new BookingSlot();
            s.setSlotDate(date);
            s.setTimeWindow(w.getLabel());
            s.setMaxCapacity(50);
            slots.add(s);
        }
    }

    private static StaffSchedule entry(long id, String name, LocalDate date, String window) {
        StaffSchedule s = new StaffSchedule();
        s.setId(id);
        s.setStaffName(name);
        s.setDutyDate(date);
        s.setShiftTime(window);
        return s;
    }

    private ResultActions assign(String name, LocalDate date, String... windows) throws Exception {
        return mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", name).param("dutyDate", date.toString()).param("shiftTime", windows));
    }

    private List<StaffSchedule> saved(int expected) {
        ArgumentCaptor<StaffSchedule> saved = ArgumentCaptor.forClass(StaffSchedule.class);
        verify(staffRepo, times(expected)).save(saved.capture());
        return saved.getAllValues();
    }

    // --- Today first, with that date's booking slots ---

    @Test
    @SuppressWarnings("unchecked")
    void theRosterOpensOnTodayWithTodaysSlotsInTimeOrder() throws Exception {
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(TODAY)).thenReturn(List.of(entry(1, "Sgt. Reyes", TODAY, T1030.getLabel())));
        when(repo.sumSeatsHeldFrom(any(), any())).thenReturn(List.<Object[]>of(new Object[] {TODAY, T930.getLabel(), 12L}));

        MvcResult result = mvc.perform(get("/admin/staff"))
            .andExpect(model().attribute("rosterDay", TODAY.toString()))
            .andExpect(model().attribute("rosterDayPast", false))
            .andReturn();
        List<Map<String, Object>> daySlots = (List<Map<String, Object>>) result.getModelAndView().getModel().get("rosterSlots");
        assertThat(daySlots).extracting(s -> s.get("window")).containsExactly(T930.getLabel(), T1030.getLabel(), T1130.getLabel());
        assertThat(daySlots).extracting(s -> s.get("summary")).first().isEqualTo("9:30 AM – 10:30 AM · 50 seats (12 booked)");

        String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).containsSubsequence("id=\"rosterDayPanel\"", "9:30 AM – 10:30 AM · 50 seats (12 booked)",
                "10:30 AM – 11:30 AM · 50 seats (0 booked)", "Sgt. Reyes", "11:30 AM – 12:30 PM · 50 seats (0 booked)")
            .contains("id=\"rosterDayPicker\" name=\"day\"").contains("value=\"" + TODAY + "\"")
            .contains("id=\"rosterWholeDay\"")
            .containsSubsequence("name=\"shiftTime\" value=\"" + T930.getLabel() + "\"", "name=\"shiftTime\" value=\"" + T1030.getLabel() + "\"",
                "name=\"shiftTime\" value=\"" + T1130.getLabel() + "\"")
            .doesNotContain("id=\"dailyRosterModal\"");
    }

    @Test
    void anotherDayCanBePickedAndADayWithoutSlotsSaysSo() throws Exception {
        mvc.perform(get("/admin/staff").param("day", DAY.toString())).andExpect(model().attribute("rosterDay", DAY.toString()));
        mvc.perform(get("/admin/staff").param("day", "garbage")).andExpect(model().attribute("rosterDay", TODAY.toString()));

        String empty = mvc.perform(get("/admin/staff").param("day", DAY.plusDays(9).toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(empty).contains("No booking slots on this date").doesNotContain("name=\"shiftTime\" value=");

        String past = mvc.perform(get("/admin/staff").param("day", TODAY.minusDays(1).toString()))
                .andExpect(model().attribute("rosterDayPast", true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(past).contains("This date is in the past: past dates can be corrected, but not newly filled.");
    }

    // --- Several windows at once ---

    @Test
    void onePersonCanBeAssignedToSeveralBackToBackWindowsAtOnce() throws Exception {
        assign("Sgt. Reyes", DAY, T930.getLabel(), T1030.getLabel(), T1130.getLabel())   // "Whole day": every slot of the date
            .andExpect(redirectedUrl("/admin/staff?day=" + DAY + "#roster"))
            .andExpect(flash().attribute("success", "✅ Sgt. Reyes is on duty on " + DAY
                    + ": 9:30 AM – 10:30 AM, 10:30 AM – 11:30 AM, 11:30 AM – 12:30 PM."));

        assertThat(saved(3)).allSatisfy(s -> {
            assertThat(s.getStaffName()).isEqualTo("Sgt. Reyes");
            assertThat(s.getDutyDate()).isEqualTo(DAY);
        }).extracting(StaffSchedule::getShiftTime).containsExactly(T930.getLabel(), T1030.getLabel(), T1130.getLabel());
    }

    @Test
    void backToBackWithAnExistingShiftIsAllowed() throws Exception {
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(DAY)).thenReturn(List.of(entry(1, "Sgt. Reyes", DAY, T930.getLabel())));
        assign("Sgt. Reyes", DAY, T1030.getLabel())          // 10:30 starts exactly when 9:30-10:30 ends
            .andExpect(flash().attribute("success", "✅ Sgt. Reyes is on duty " + T1030.getLabel() + " on " + DAY + "."));
        assertThat(saved(1)).extracting(StaffSchedule::getShiftTime).containsExactly(T1030.getLabel());
    }

    @Test
    void aRealOverlapSavesNoneOfTheTickedWindows() throws Exception {
        slotsOn(DAY, LONG);
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(DAY)).thenReturn(List.of(entry(1, "Sgt. Reyes", DAY, LONG.getLabel())));

        assign("Sgt. Reyes", DAY, T1130.getLabel(), T1030.getLabel())   // 11:30-12:30 overlaps his 10:00-12:00 (first clash named)
            .andExpect(flash().attribute("error", "⛔ Sgt. Reyes is already on duty " + LONG.getLabel() + " on " + DAY
                    + ", which overlaps " + T1130.getLabel() + ". Nothing was saved."));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void twoTickedWindowsThatOverlapEachOtherAreRefused() throws Exception {
        slotsOn(DAY, LONG);
        assign("Sgt. Reyes", DAY, T930.getLabel(), LONG.getLabel())     // 9:30-10:30 and 10:00-12:00 overlap
            .andExpect(flash().attribute("error", "⛔ Sgt. Reyes is already on duty " + T930.getLabel() + " on " + DAY
                    + ", which overlaps " + LONG.getLabel() + ". Nothing was saved."));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void onlyTheDatesSlotsCanBeAssignedAndAtLeastOneIsNeeded() throws Exception {
        assign("Sgt. Reyes", DAY, T930.getLabel(), LONG.getLabel())     // LONG is no slot on DAY
            .andExpect(flash().attribute("error", "⛔ \"" + LONG.getLabel() + "\" is not one of the booking slots on " + DAY + ". Nothing was saved."));
        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", "Sgt. Reyes").param("dutyDate", DAY.toString()))
            .andExpect(flash().attribute("error", "Tick at least one time window. Nothing was saved."));
        verify(staffRepo, never()).save(any());
    }

    // --- Editing stays per window, within the date's slots ---

    @Test
    void anEditUsesOneOfTheDatesSlotsAndBackToBackIsFine() throws Exception {
        StaffSchedule mine = entry(5, "Sgt. Reyes", DAY, T1130.getLabel());
        StaffSchedule other = entry(6, "Sgt. Reyes", DAY, T930.getLabel());
        when(staffRepo.findById(5L)).thenReturn(Optional.of(mine));
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(DAY)).thenReturn(List.of(mine, other));

        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "5").param("staffName", "Sgt. Reyes")
                .param("dutyDate", DAY.toString()).param("shiftTime", LONG.getLabel()))
            .andExpect(flash().attribute("error", "⛔ \"" + LONG.getLabel() + "\" is not one of the booking slots on " + DAY + ". Nothing was saved."));

        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "5").param("staffName", "Sgt. Reyes")
                .param("dutyDate", DAY.toString()).param("shiftTime", T1030.getLabel()))     // right after his 9:30-10:30
            .andExpect(redirectedUrl("/admin/staff?day=" + DAY + "#roster"))
            .andExpect(flash().attribute("success", "✅ Shift assignment successfully updated for Sgt. Reyes."));
        assertThat(mine.getShiftTime()).isEqualTo(T1030.getLabel());
    }
}
