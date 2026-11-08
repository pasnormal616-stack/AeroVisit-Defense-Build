package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.MuseumEmployee;
import com.paf.aerovisit.model.StaffSchedule;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Staff page: Staff Directory edits, the Bulk Slot Generator, the Duty Roster calendar and Clear Date Range. No database. */
@WebMvcTest(ManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class ShiftTemplateRosterTest {

    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    private static MuseumEmployee employee(String name, String role, String email, String workingDays, String defaultShift) {
        MuseumEmployee e = new MuseumEmployee();
        e.setName(name);
        e.setRole(role);
        e.setEmail(email);
        e.setWorkingDays(workingDays);
        e.setDefaultShift(defaultShift);
        return e;
    }

    private static StaffSchedule shift(String staffName, LocalDate date, String shiftTime) {
        StaffSchedule s = new StaffSchedule();
        s.setStaffName(staffName);
        s.setRole("Gate Guard");
        s.setDutyDate(date);
        s.setShiftTime(shiftTime);
        return s;
    }

    private List<StaffSchedule> savedShifts(int expected) {
        ArgumentCaptor<StaffSchedule> saved = ArgumentCaptor.forClass(StaffSchedule.class);
        verify(staffRepo, times(expected)).save(saved.capture());
        return saved.getAllValues();
    }

    // --- Staff Directory ---

    @Test
    void addEmployeeStillWorksWithNameAndRoleOnly() throws Exception {
        mvc.perform(post("/admin/employee/add").with(csrf()).param("name", "Sgt. Cruz").param("role", "Tour Guide")
                .param("email", "").param("defaultShift", ""))
            .andExpect(flash().attribute("success", containsString("Sgt. Cruz")));

        ArgumentCaptor<MuseumEmployee> saved = ArgumentCaptor.forClass(MuseumEmployee.class);
        verify(employeeRepo).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isNull();
        assertThat(saved.getValue().getWorkingDays()).isNull();
        assertThat(saved.getValue().getDefaultShift()).isNull();
    }

    // --- The Bulk Slot Generator only opens public booking slots ---

    @Test
    void bulkSlotGeneratorDoesNotTouchTheDutyRoster() throws Exception {
        when(employeeRepo.findAll()).thenReturn(List.of(employee("Sgt. Reyes", "Gate Guard", null, "MONDAY,TUESDAY", "Morning")));

        mvc.perform(post("/admin/slots/add").with(csrf())
                .param("startDate", MONDAY.toString()).param("endDate", MONDAY.plusDays(6).toString())
                .param("timeWindow", MORNING).param("maxCapacity", "100"))
            .andExpect(flash().attribute("success", "✅ Bulk Scheduling Complete: 7 days successfully opened for " + MORNING + "."));

        verify(bookingRepo, times(7)).save(any(BookingSlot.class));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void bulkSlotGeneratorSkipsTheTickedWeekdaysAndReportsWhatItSkipped() throws Exception {
        // November 2026 starts on a Sunday: 5 Sundays + 5 Mondays are skipped. Nov 30 is a Monday that is also an
        // excluded date, so it counts once (by weekday); Nov 11 (a Wednesday) is skipped by date.
        mvc.perform(post("/admin/slots/add").with(csrf())
                .param("startDate", "2026-11-01").param("endDate", "2026-11-30")
                .param("timeWindow", MORNING).param("maxCapacity", "100")
                .param("skipDays", "SUNDAY", "MONDAY").param("excludeDates", "2026-11-30, 2026-11-11"))
            .andExpect(flash().attribute("success", "✅ Bulk Scheduling Complete: 19 days successfully opened for " + MORNING + "."
                + " Skipped 10 by weekday (Sun, Mon). Skipped 1 by date."));

        ArgumentCaptor<BookingSlot> saved = ArgumentCaptor.forClass(BookingSlot.class);
        verify(bookingRepo, times(19)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(BookingSlot::getSlotDate)
            .allSatisfy(d -> assertThat(d.getDayOfWeek()).isBetween(java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.SATURDAY))
            .doesNotContain(LocalDate.of(2026, 11, 11));
    }

    @Test
    void theBulkFormOffersSevenSkipDayBoxesWithSundayAndMondayTicked() throws Exception {
        String html = mvc.perform(get("/admin/staff")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        String form = html.substring(html.indexOf("action=\"/admin/slots/add\""), html.indexOf("</form>", html.indexOf("action=\"/admin/slots/add\"")));
        assertThat(form).contains("<legend class=\"form-label-custom\">Skip these days</legend>", "name=\"excludeDates\"")
            .doesNotContain("Exclude Weekends", "name=\"excludeWeekends\"");
        java.util.regex.Matcher boxes = java.util.regex.Pattern.compile("<input[^>]*name=\"skipDays\"[^>]*>").matcher(form);
        List<String> ticked = new java.util.ArrayList<>();
        int count = 0;
        while (boxes.find()) {
            count++;
            if (boxes.group().contains("checked")) {
                java.util.regex.Matcher value = java.util.regex.Pattern.compile("value=\"([A-Z]+)\"").matcher(boxes.group());
                if (value.find()) ticked.add(value.group(1));
            }
        }
        assertThat(count).isEqualTo(7);
        assertThat(ticked).containsExactly("SUNDAY", "MONDAY");
    }

    @Test
    void anyWeekdaysCanBeSkippedNotOnlyWeekends() throws Exception {
        // One week from Monday Oct 5: skip Wednesday and Friday only
        mvc.perform(post("/admin/slots/add").with(csrf())
                .param("startDate", MONDAY.toString()).param("endDate", MONDAY.plusDays(6).toString())
                .param("timeWindow", MORNING).param("maxCapacity", "100").param("skipDays", "wednesday", "FRIDAY"))
            .andExpect(flash().attribute("success", "✅ Bulk Scheduling Complete: 5 days successfully opened for " + MORNING + "."
                + " Skipped 2 by weekday (Wed, Fri)."));
        verify(bookingRepo, times(5)).save(any(BookingSlot.class));
    }

    @Test
    void anUnknownWeekdayOrBackwardsRangeIsRefusedAndNothingIsCreated() throws Exception {
        mvc.perform(post("/admin/slots/add").with(csrf())
                .param("startDate", MONDAY.toString()).param("endDate", MONDAY.plusDays(6).toString())
                .param("timeWindow", MORNING).param("maxCapacity", "100").param("skipDays", "FUNDAY"))
            .andExpect(flash().attribute("error", "Unknown day of the week: FUNDAY."));
        mvc.perform(post("/admin/slots/add").with(csrf())
                .param("startDate", MONDAY.plusDays(6).toString()).param("endDate", MONDAY.toString())
                .param("timeWindow", MORNING).param("maxCapacity", "100").param("skipDays", "SUNDAY"))
            .andExpect(flash().attribute("error", "Time Paradox: End Date cannot be before Start Date."));
        mvc.perform(post("/admin/slots/add").with(csrf())
                .param("startDate", "not-a-date").param("endDate", MONDAY.toString())
                .param("timeWindow", MORNING).param("maxCapacity", "100"))
            .andExpect(flash().attribute("error", "System Error: Please check your date formatting."));
        verify(bookingRepo, never()).save(any(BookingSlot.class));
    }

    private static final String OPEN = "⚠️ OPEN SHIFT (AI)";
    private static final String AM = "Morning (0800H-1200H)";
    private static final String PM = "Afternoon (1300H-1700H)";
    private static final String FULL = "Full Day (0800H-1700H)";

    // --- Duty Roster calendar: open days and Clear Date Range ---

    @Test
    void staffPageListsTheDatesTheMuseumIsOpen() throws Exception {
        BookingSlot morning = new BookingSlot();
        morning.setSlotDate(MONDAY);
        BookingSlot afternoon = new BookingSlot();
        afternoon.setSlotDate(MONDAY);                 // two slots on one date still count once
        BookingSlot closed = new BookingSlot();
        closed.setSlotDate(MONDAY.plusDays(1));        // has a slot, but is under an emergency closure
        BookingSlot wednesday = new BookingSlot();
        wednesday.setSlotDate(MONDAY.plusDays(2));
        when(bookingRepo.findAll()).thenReturn(List.of(wednesday, morning, afternoon, closed, new BookingSlot()));
        when(repo.findClosureDates()).thenReturn(List.of(MONDAY.plusDays(1)));

        mvc.perform(get("/admin/staff"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("openDates", contains("2026-10-05", "2026-10-07")))
            .andExpect(content().string(containsString("var openDates = new Set([\"2026-10-05\",\"2026-10-07\"]);")))
            .andExpect(content().string(containsString("dayCellClassNames")))
            .andExpect(content().string(containsString("Museum open")));
    }

    @Test
    void clearRosterDeletesEveryActiveShiftInTheRange() throws Exception {
        StaffSchedule emailed = shift("Sgt. Reyes", MONDAY, AM);
        emailed.setRosterNotified(true);
        StaffSchedule emailedOpen = shift(OPEN, MONDAY, AM);
        emailedOpen.setRosterNotified(true);           // nobody was emailed about an open shift
        List<StaffSchedule> inRange = List.of(emailed, emailedOpen, shift("Lt. Santos", MONDAY.plusDays(6), FULL));
        when(staffRepo.findByArchivedFalseAndDutyDateBetween(MONDAY, MONDAY.plusDays(6))).thenReturn(inRange);

        mvc.perform(post("/admin/staff/clear-roster").with(csrf()).param("startDate", MONDAY.toString()).param("endDate", MONDAY.plusDays(6).toString()))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", "🗑️ Successfully cleared 3 shifts from the roster (2026-10-05 to 2026-10-11)."))
            .andExpect(flash().attributeCount(1));

        verify(staffRepo).deleteAllInBatch(inRange);
    }

    @Test
    void clearRosterOnASingleDayWithNothingEmailedKeepsTheMessageShort() throws Exception {
        List<StaffSchedule> inRange = List.of(shift(OPEN, MONDAY, AM));
        when(staffRepo.findByArchivedFalseAndDutyDateBetween(MONDAY, MONDAY)).thenReturn(inRange);

        mvc.perform(post("/admin/staff/clear-roster").with(csrf()).param("startDate", MONDAY.toString()).param("endDate", MONDAY.toString()))
            .andExpect(flash().attribute("success", "🗑️ Successfully cleared 1 shift from the roster (2026-10-05 to 2026-10-05)."));

        verify(staffRepo).deleteAllInBatch(inRange);
    }

    @Test
    void clearRosterRefusesABadRangeAndReportsAnEmptyOne() throws Exception {
        mvc.perform(post("/admin/staff/clear-roster").with(csrf()).param("startDate", MONDAY.plusDays(1).toString()).param("endDate", MONDAY.toString()))
            .andExpect(flash().attribute("error", "Roster not cleared: the end date cannot be before the start date."));
        mvc.perform(post("/admin/staff/clear-roster").with(csrf()).param("startDate", "").param("endDate", "not-a-date"))
            .andExpect(flash().attribute("error", "Roster not cleared: please pick a valid start and end date."));
        mvc.perform(post("/admin/staff/clear-roster").with(csrf()).param("startDate", MONDAY.toString()).param("endDate", MONDAY.toString()))
            .andExpect(flash().attribute("info", "No shifts found from 2026-10-05 to 2026-10-05. Nothing was cleared."));

        verify(staffRepo, never()).deleteAllInBatch(any());
        verify(staffRepo, never()).deleteAll();
    }

    @Test
    void staffPageHasTheClearDateRangeButtonAndModal() throws Exception {
        mvc.perform(get("/admin/staff"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-bs-target=\"#clearRosterModal\"")))
            .andExpect(content().string(containsString("Clear Date Range</button>")))
            .andExpect(content().string(containsString("action=\"/admin/staff/clear-roster\" method=\"post\"")));
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCannotClearTheRoster() throws Exception {
        mvc.perform(post("/admin/staff/clear-roster").with(csrf()).param("startDate", MONDAY.toString()).param("endDate", MONDAY.toString()))
            .andExpect(status().isForbidden());
        verify(staffRepo, never()).deleteAllInBatch(any());
    }

    @Test
    void staffPageShowsTheInfoMessage() throws Exception {
        mvc.perform(get("/admin/staff").flashAttr("info", "All staff are already up-to-date. No new emails sent."))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("alert alert-primary")))
            .andExpect(content().string(containsString("All staff are already up-to-date. No new emails sent.")));
    }

    // --- Edit Museum Employee ---

    @Test
    void renamingAnOfficerRenamesTheirActiveShifts() throws Exception {
        MuseumEmployee emp = employee("Sgt. Reyes", "Gate Guard", "reyes@example.com", null, null);
        when(employeeRepo.findById(7L)).thenReturn(Optional.of(emp));
        StaffSchedule theirs = shift("Sgt. Reyes", LocalDate.now(), "Morning (0800H-1200H)");
        StaffSchedule someoneElse = shift("Sgt. Reyes Jr.", LocalDate.now(), "Morning (0800H-1200H)");
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(theirs, someoneElse));

        mvc.perform(post("/admin/employee/edit/7").with(csrf()).param("name", "  SSgt. Reyes ").param("role", "Gate Guard"))
            .andExpect(flash().attribute("success", "✅ Officer SSgt. Reyes updated."));

        assertThat(emp.getName()).isEqualTo("SSgt. Reyes");
        assertThat(theirs.getStaffName()).isEqualTo("SSgt. Reyes");
        assertThat(someoneElse.getStaffName()).isEqualTo("Sgt. Reyes Jr.");
        verify(staffRepo).saveAll(List.of(theirs));
    }

    @Test
    void editEmployeeRefusesAMissingOfficerOrABlankName() throws Exception {
        when(employeeRepo.findById(9L)).thenReturn(Optional.empty());
        mvc.perform(post("/admin/employee/edit/9").with(csrf()).param("name", "Ghost").param("role", "Admin"))
            .andExpect(flash().attribute("error", "Update failed: that officer is no longer on the roster."));

        MuseumEmployee emp = employee("Sgt. Reyes", "Gate Guard", null, null, null);
        when(employeeRepo.findById(7L)).thenReturn(Optional.of(emp));
        mvc.perform(post("/admin/employee/edit/7").with(csrf()).param("name", "   ").param("role", "Admin"))
            .andExpect(flash().attribute("error", "Update failed: the officer's name is required."));

        assertThat(emp.getName()).isEqualTo("Sgt. Reyes");
        verify(employeeRepo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCannotEditAnEmployee() throws Exception {
        mvc.perform(post("/admin/employee/edit/7").with(csrf()).param("name", "Hacked").param("role", "Admin")).andExpect(status().isForbidden());
        verify(employeeRepo, never()).save(any());
    }

    @Test
    void staffDirectoryRowsCarryAnEditButtonAndTheEditModal() throws Exception {
        MuseumEmployee emp = employee("Sgt. O'Brien \"Ace\"", "Gate Guard", "ace@example.com", "MONDAY,FRIDAY", "Morning");
        emp.setId(7L);
        when(employeeRepo.findAll()).thenReturn(List.of(emp));

        mvc.perform(get("/admin/staff"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("onclick=\"openEmployeeModal(this)\"")))
            .andExpect(content().string(containsString("data-id=\"7\"")))
            .andExpect(content().string(containsString("data-name=\"Sgt. O&#39;Brien &quot;Ace&quot;\"")))
            .andExpect(content().string(containsString("id=\"editEmployeeForm\"")))
            .andExpect(content().string(containsString("function openEmployeeModal(btn)")));
    }

    // --- Scheduling edge cases ---

    @Test
    @WithMockUser(roles = "GUARD")
    void guardIsForbiddenFromEveryScheduleCreationEndpoint() throws Exception {
        mvc.perform(get("/admin/staff")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", "Sgt. Reyes").param("role", "Gate Guard")
                .param("startDate", MONDAY.toString()).param("endDate", MONDAY.toString()).param("shiftTime", AM))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "1").param("staffName", "Sgt. Reyes")
                .param("role", "Gate Guard").param("dutyDate", MONDAY.toString()).param("shiftTime", AM))
            .andExpect(status().isForbidden());

        verify(staffRepo, never()).save(any());
    }
}
