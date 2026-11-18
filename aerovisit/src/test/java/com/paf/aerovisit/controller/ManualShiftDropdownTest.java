package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** The Duty Roster's windows are the picked date's booking slots (Assign Staff and the Edit window), and shift overlaps. No database. */
@WebMvcTest(ManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class ManualShiftDropdownTest {

    private static final TimeWindowConfig MORNING = new TimeWindowConfig("Morning", LocalTime.of(8, 0), LocalTime.of(12, 0));
    private static final TimeWindowConfig MIDDAY = new TimeWindowConfig("Midday", LocalTime.of(11, 0), LocalTime.of(14, 0));
    private static final TimeWindowConfig EVENING = new TimeWindowConfig("Evening", LocalTime.of(16, 0), LocalTime.of(20, 0));

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    private String staffPage() throws Exception {
        return mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // Booking slots on that date, one per window (the roster's windows for the date)
    private void slotsOn(LocalDate day, TimeWindowConfig... windows) {
        List<BookingSlot> slots = new ArrayList<>();
        for (TimeWindowConfig w : windows) {
            BookingSlot slot = new BookingSlot();
            slot.setSlotDate(day);
            slot.setTimeWindow(w.getLabel());
            slot.setMaxCapacity(50);
            slots.add(slot);
        }
        when(bookingRepo.findAll()).thenReturn(slots);
    }

    // 🧲 The drag-and-drop day panel's windows (rosterDayData, which the page script draws): the slot labels, in order
    @SuppressWarnings("unchecked")
    private static List<Object> dayWindows(org.springframework.test.web.servlet.MvcResult result, String field) {
        Map<String, Object> day = (Map<String, Object>) result.getModelAndView().getModel().get("rosterDayData");
        List<Object> values = new ArrayList<>();
        for (Map<String, Object> w : (List<Map<String, Object>>) day.get("windows")) values.add(w.get(field));
        return values;
    }

    private static String rosterDay(LocalDate day) {
        return "/admin/staff?day=" + day + "#roster";
    }

    @Test
    void theAssignFormOffersExactlyThatDatesBookingSlotsInTimeOrder() throws Exception {
        LocalDate day = LocalDate.now().plusDays(2);
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY, EVENING)); // configured, but only two are slots
        slotsOn(day, EVENING, MORNING);
        org.springframework.test.web.servlet.MvcResult result = mvc.perform(get("/admin/staff").param("day", day.toString())).andExpect(status().isOk()).andReturn();
        String html = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 🧲 the drag-and-drop panel's windows: only that date's slots (not Midday, not the old Morning string), by time
        assertThat(dayWindows(result, "window")).containsExactly(MORNING.getLabel(), EVENING.getLabel());
        assertThat(dayWindows(result, "times")).containsExactly("8:00 AM – 12:00 PM", "4:00 PM – 8:00 PM");
        // the Edit window gets the same slots for that date, by exact time. Its entries are Map.of(...), whose key order
        // changes from run to run, so each pair is checked on its own (this used to fail now and then)
        String editSlots = html.substring(html.indexOf("\"" + day + "\":["), html.indexOf("]", html.indexOf("\"" + day + "\":[")));
        assertThat(editSlots).containsSubsequence(MORNING.getLabel(), EVENING.getLabel())
            .contains("\"value\":\"" + MORNING.getLabel() + "\"").contains("\"text\":\"8:00 AM \\u2013 12:00 PM\"") // JS-escaped dash
            .doesNotContain(MIDDAY.getLabel());
    }

    @Test
    void aDateWithNoBookingSlotsSaysSoAndOffersNoWindows() throws Exception {
        LocalDate day = LocalDate.now().plusDays(2);
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY, EVENING));
        slotsOn(day.plusDays(1), MORNING);
        org.springframework.test.web.servlet.MvcResult result = mvc.perform(get("/admin/staff").param("day", day.toString())).andExpect(status().isOk()).andReturn();

        assertThat(dayWindows(result, "window")).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> dayData = (Map<String, Object>) result.getModelAndView().getModel().get("rosterDayData");
        assertThat(dayData.get("closedReason")).isEqualTo("No booking slots on this date");
        assertThat(dayData.get("editable")).isEqualTo(false);   // nothing can be dropped on it
        assertThat((List<?>) dayData.get("staff")).isEmpty();
    }

    @Test
    void theEditModalNoLongerForcesAnUnknownShiftOntoAHardCodedOne() throws Exception {
        String html = staffPage();
        assertThat(html).doesNotContain("select.value = \"Morning (0800H-1200H)\"")
                        .doesNotContain("select.value = \"Full Day (0800H-1700H)\"");
    }

    @Test
    void addingAShiftOutsideTheDatesBookingSlotsIsRefused() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY));
        LocalDate day = LocalDate.now().plusDays(2);
        slotsOn(day, MORNING, MIDDAY);
        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", "Sgt. Reyes")
                .param("dutyDate", day.toString()).param("shiftTime", "Afternoon (1300H-1700H)"))
            .andExpect(redirectedUrl(rosterDay(day)))
            .andExpect(flash().attribute("error", "⛔ \"Afternoon (1300H-1700H)\" is not one of the booking slots on " + day + ". Nothing was saved."));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void addingAShiftInOneOfTheDatesSlotsStillWorks() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY));
        LocalDate day = LocalDate.now().plusDays(2);
        slotsOn(day, MORNING, MIDDAY);

        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", "Sgt. Reyes")
                .param("dutyDate", day.toString()).param("shiftTime", MIDDAY.getLabel()))
            .andExpect(flash().attributeExists("success"));
        verify(staffRepo).save(org.mockito.ArgumentMatchers.argThat((StaffSchedule s) -> MIDDAY.getLabel().equals(s.getShiftTime())));
    }

    // --- Shift overlap (Morning 08:00-12:00 and Midday 11:00-14:00 overlap; Evening 16:00-20:00 does not) ---

    private static StaffSchedule onDuty(String name, LocalDate day, String shiftTime) {
        StaffSchedule s = new StaffSchedule();
        s.setStaffName(name);
        s.setDutyDate(day);
        s.setShiftTime(shiftTime);
        return s;
    }

    private LocalDate openDayWithReyesOnMorningDuty() {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY, EVENING));
        LocalDate day = LocalDate.now().plusDays(2);
        slotsOn(day, MORNING, MIDDAY, EVENING);
        StaffSchedule existing = onDuty("Sgt. Reyes", day, "Morning (0800H-1200H)");
        // stubbed both ways, so the test doesn't dictate which query the fix uses
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(day)).thenReturn(List.of(existing));
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(existing));
        return day;
    }

    @Test
    void anOfficerCannotBeGivenAnOverlappingShiftOnTheSameDay() throws Exception {
        LocalDate day = openDayWithReyesOnMorningDuty();
        // Midday 11:00-14:00 overlaps his Morning 08:00-12:00; the name differs only in case and spacing
        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", " sgt. reyes ")
                .param("dutyDate", day.toString()).param("shiftTime", MIDDAY.getLabel()))
            .andExpect(redirectedUrl(rosterDay(day)))
            .andExpect(flash().attributeExists("error"));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void aSecondShiftThatDoesNotOverlapIsStillAllowed() throws Exception {   // passes today; stops the fix over-blocking
        LocalDate day = openDayWithReyesOnMorningDuty();
        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", "Sgt. Reyes")
                .param("dutyDate", day.toString()).param("shiftTime", EVENING.getLabel()))
            .andExpect(flash().attributeExists("success"));
        verify(staffRepo).save(any(StaffSchedule.class));
    }

    @Test
    void shiftLabelsAreReadInBothFormats() {
        assertThat(ManagementController.shiftHours("Morning (0800H-1200H)")).containsExactly(LocalTime.of(8, 0), LocalTime.of(12, 0));
        assertThat(ManagementController.shiftHours("Full Day (0800H-1700H)")).containsExactly(LocalTime.of(8, 0), LocalTime.of(17, 0));
        assertThat(ManagementController.shiftHours(MIDDAY.getLabel())).containsExactly(LocalTime.of(11, 0), LocalTime.of(14, 0));
        assertThat(ManagementController.shiftHours("Noon Tour (12:00 PM - 01:30 PM)")).containsExactly(LocalTime.of(12, 0), LocalTime.of(13, 30));
        for (String unreadable : new String[] {null, "", "Master Booking Bypass", "Broken (2500H-2600H)", "Backwards (1200H-0800H)"}) {
            assertThat(ManagementController.shiftHours(unreadable)).as(unreadable).isNull();
        }
    }

    @Test
    void overlapIsStrictAndUnreadableLabelsOnlyClashWhenIdentical() {
        assertThat(ManagementController.shiftsOverlap("Morning (0800H-1200H)", MIDDAY.getLabel())).isTrue();          // 08-12 vs 11-14
        assertThat(ManagementController.shiftsOverlap("Full Day (0800H-1700H)", "Afternoon (1300H-1700H)")).isTrue();
        assertThat(ManagementController.shiftsOverlap("Morning (0800H-1200H)", "Afternoon (1300H-1700H)")).isFalse();
        assertThat(ManagementController.shiftsOverlap("Morning (0800H-1200H)", "Noon Tour (12:00 PM - 01:30 PM)")).isFalse(); // touching
        assertThat(ManagementController.shiftsOverlap("Special Duty", "special duty ")).isTrue();
        assertThat(ManagementController.shiftsOverlap("Special Duty", "Morning (0800H-1200H)")).isFalse();
    }

    @Test
    void officerNamesMatchIgnoringCaseAndSpacingButOpenShiftsNeverMatch() {
        assertThat(ManagementController.officerKey("  Sgt.   Reyes ")).isEqualTo(ManagementController.officerKey("sgt. reyes"));
        assertThat(ManagementController.officerKey("⚠️ OPEN SHIFT (AI)")).isNull();
        assertThat(ManagementController.officerKey("   ")).isNull();
    }

    @Test
    void editingAShiftOntoAnOverlappingTimeIsRefusedButKeepingItsOwnTimeIsNot() throws Exception {
        LocalDate day = openDayWithReyesOnMorningDuty();
        StaffSchedule morning = onDuty("Sgt. Reyes", day, "Morning (0800H-1200H)");
        morning.setId(4L);
        StaffSchedule evening = onDuty("Sgt. Reyes", day, EVENING.getLabel());
        evening.setId(5L);
        when(staffRepo.findById(5L)).thenReturn(Optional.of(evening));
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(day)).thenReturn(List.of(morning, evening));

        // Moving the evening shift to Midday collides with his morning shift
        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "5").param("staffName", "Sgt. Reyes")
                .param("dutyDate", day.toString()).param("shiftTime", MIDDAY.getLabel()))
            .andExpect(flash().attribute("error", "⛔ Sgt. Reyes is already on duty Morning (0800H-1200H) on " + day
                    + ", which overlaps " + MIDDAY.getLabel() + ". Nothing was saved."));
        assertThat(evening.getShiftTime()).isEqualTo(EVENING.getLabel());
        verify(staffRepo, never()).save(any());

        // Re-saving the evening shift as it is: its own row never counts as a clash
        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "5").param("staffName", "Sgt. Reyes")
                .param("dutyDate", day.toString()).param("shiftTime", EVENING.getLabel()))
            .andExpect(flash().attributeExists("success"));
    }

    @Test
    void officerNamesAreTrimmedWhenSaved() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY));
        LocalDate day = LocalDate.now().plusDays(2);
        slotsOn(day, MORNING, MIDDAY);

        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", "  Sgt. Reyes  ")
                .param("dutyDate", day.toString()).param("shiftTime", MIDDAY.getLabel()))
            .andExpect(flash().attributeExists("success"));
        verify(staffRepo).save(org.mockito.ArgumentMatchers.argThat((StaffSchedule s) -> "Sgt. Reyes".equals(s.getStaffName())));

        StaffSchedule old = onDuty("Sgt. Reyes", day, MIDDAY.getLabel());
        old.setId(9L);
        when(staffRepo.findById(9L)).thenReturn(Optional.of(old));
        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "9").param("staffName", " Sgt. Cruz ")
                .param("dutyDate", day.toString()).param("shiftTime", MIDDAY.getLabel()))
            .andExpect(flash().attributeExists("success"));
        assertThat(old.getStaffName()).isEqualTo("Sgt. Cruz");
    }

    @Test
    void editingKeepsAnOlderShiftValueButRefusesAMadeUpNewOne() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY));
        LocalDate day = LocalDate.now().plusDays(2);
        slotsOn(day, MORNING, MIDDAY);
        StaffSchedule old = new StaffSchedule();
        old.setId(1L);
        old.setStaffName("Sgt. Reyes");
        old.setDutyDate(day);
        old.setShiftTime("Afternoon (1300H-1700H)"); // its window was since removed
        when(staffRepo.findById(1L)).thenReturn(Optional.of(old));

        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "1").param("staffName", "Sgt. Reyes")
                .param("dutyDate", day.toString()).param("shiftTime", "Afternoon (1300H-1700H)"))
            .andExpect(flash().attributeExists("success"));

        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "1").param("staffName", "Sgt. Reyes")
                .param("dutyDate", day.toString()).param("shiftTime", "Graveyard (0000H-0600H)"))
            .andExpect(flash().attribute("error", "⛔ \"Graveyard (0000H-0600H)\" is not one of the booking slots on " + day + ". Nothing was saved."));
        assertThat(old.getShiftTime()).isEqualTo("Afternoon (1300H-1700H)");
    }
}
