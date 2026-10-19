package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.MuseumEmployee;
import com.paf.aerovisit.model.StaffLeave;
import com.paf.aerovisit.model.StaffSchedule;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffLeaveRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Fill from Staff Templates: a roster preview built from each officer's Working Days and Default Shift, no AI. No database. */
@WebMvcTest(ManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class TemplateRosterFillTest {

    // No time windows configured in these tests, so the three original roster shifts apply
    private static final String MORNING = "Morning (0800H-1200H)";
    private static final String AFTERNOON = "Afternoon (1300H-1700H)";
    private static final Map<String, String> DUTY_SHIFTS = Map.of("Morning", MORNING, "Afternoon", AFTERNOON,
            "Full Day", "Full Day (0800H-1700H)");
    private static final List<String> ALLOWED = List.of(MORNING, AFTERNOON, "Full Day (0800H-1700H)");
    private static final LocalDate MON = LocalDate.of(2026, 10, 5);
    private static final LocalDate TUE = MON.plusDays(1), WED = MON.plusDays(2), SAT = MON.plusDays(5), SUN = MON.plusDays(6);
    private static final Set<LocalDate> OPEN_WEEK = Set.of(MON, TUE, WED, MON.plusDays(3), MON.plusDays(4), SAT, SUN);

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;
    @MockitoBean private StaffLeaveRepository leaveRepo;

    private static MuseumEmployee officer(String name, String role, String workingDays, String defaultShift) {
        MuseumEmployee e = new MuseumEmployee();
        e.setName(name);
        e.setRole(role);
        e.setWorkingDays(workingDays);
        e.setDefaultShift(defaultShift);
        return e;
    }

    private static StaffSchedule shift(String name, LocalDate date, String time) {
        StaffSchedule s = new StaffSchedule();
        s.setStaffName(name);
        s.setRole("Gate Guard");
        s.setDutyDate(date);
        s.setShiftTime(time);
        return s;
    }

    private static ManagementController.TemplatePlan plan(List<MuseumEmployee> staff, Set<LocalDate> skip,
                                                          List<StaffLeave> leaves, List<StaffSchedule> existing) {
        return ManagementController.planFromTemplates(MON, SUN, OPEN_WEEK, skip, staff, DUTY_SHIFTS, ALLOWED, leaves, existing);
    }

    // --- the plan ---

    @Test
    void eachOfficerGetsTheirDefaultShiftOnTheirWorkingDaysInTheirRole() {
        var p = plan(List.of(officer("Sgt. Reyes", "Gate Guard", "MONDAY,WEDNESDAY,SATURDAY", "Morning"),
                             officer("Cpl. Santos", "Tour Guide", "TUESDAY", "Afternoon")), Set.of(), List.of(), List.of());

        assertThat(p.shifts().keySet()).containsExactly(MON, TUE, WED, SAT); // weekends follow the template too
        StaffSchedule mon = p.shifts().get(MON).get(0);
        assertThat(mon.getStaffName()).isEqualTo("Sgt. Reyes");
        assertThat(mon.getRole()).isEqualTo("Gate Guard");
        assertThat(mon.getShiftTime()).isEqualTo(MORNING);
        assertThat(p.shifts().get(TUE)).extracting(StaffSchedule::getStaffName, StaffSchedule::getShiftTime)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Cpl. Santos", AFTERNOON));
        assertThat(p.skipped()).isEmpty();
    }

    @Test
    void closedDatesAndSkippedHolidaysGetNoShifts() {
        var reyes = officer("Sgt. Reyes", "Gate Guard", "MONDAY,TUESDAY,WEDNESDAY", "Morning");
        var p = ManagementController.planFromTemplates(MON, SUN, Set.of(MON, WED), Set.of(WED), List.of(reyes),
                DUTY_SHIFTS, ALLOWED, List.of(), List.of());
        assertThat(p.shifts().keySet()).containsExactly(MON); // TUE has no booking slots, WED is a skipped holiday
    }

    @Test
    void anOfficerOnLeaveIsSkippedForThoseDatesWhileEveryoneElseIsStillAdded() {
        var p = plan(List.of(officer("Sgt. Reyes", "Gate Guard", "MONDAY,WEDNESDAY", "Morning"),
                             officer("Cpl. Santos", "Tour Guide", "MONDAY", "Morning")),
                Set.of(), List.of(new StaffLeave("Sgt. Reyes", MON, MON)), List.of());

        assertThat(p.shifts().get(MON)).extracting(StaffSchedule::getStaffName).containsExactly("Cpl. Santos");
        assertThat(p.shifts().get(WED)).extracting(StaffSchedule::getStaffName).containsExactly("Sgt. Reyes");
        assertThat(p.skipped()).containsExactly("Sgt. Reyes on " + MON + ": on leave");
    }

    @Test
    void anOverlappingShiftAlreadyOnTheRosterIsSkippedNotDuplicated() {
        var p = plan(List.of(officer("Sgt. Reyes", "Gate Guard", "MONDAY,WEDNESDAY", "Morning")),
                Set.of(), List.of(), List.of(shift("sgt.  reyes", MON, "Full Day (0800H-1700H)"), shift("Sgt. Reyes", WED, AFTERNOON)));

        assertThat(p.shifts().keySet()).containsExactly(WED); // an afternoon shift doesn't overlap the morning one
        assertThat(p.skipped()).containsExactly("Sgt. Reyes on " + MON + ": already on duty Full Day (0800H-1700H)");
    }

    @Test
    void deactivatedOfficersAreLeftOutAndOfficersWithoutATemplateAreListed() {
        var gone = officer("Sgt. Gone", "Gate Guard", "MONDAY", "Morning");
        gone.setActive(false);
        var p = plan(List.of(gone, officer("Sgt. NoDays", "Admin", null, "Morning"),
                             officer("Sgt. NoShift", "Admin", "MONDAY", null)), Set.of(), List.of(), List.of());

        assertThat(p.shifts()).isEmpty();
        assertThat(p.skipped()).containsExactly("No shift template (Working Days + Default Shift) for: Sgt. NoDays, Sgt. NoShift");
    }

    @Test
    void aDefaultShiftThatIsNoLongerAConfiguredWindowIsListedNotUsed() {
        var p = ManagementController.planFromTemplates(MON, SUN, OPEN_WEEK, Set.of(),
                List.of(officer("Sgt. Reyes", "Gate Guard", "MONDAY", "Afternoon")), DUTY_SHIFTS, List.of(MORNING), List.of(), List.of());
        assertThat(p.shifts()).isEmpty();
        assertThat(p.skipped()).containsExactly("Sgt. Reyes: default shift " + AFTERNOON + " is not one of the configured time windows");
    }

    // --- the page ---

    private void openWeek() {
        when(bookingRepo.findAll()).thenReturn(OPEN_WEEK.stream().map(d -> {
            BookingSlot slot = new BookingSlot();
            slot.setSlotDate(d);
            return slot;
        }).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePreviewOpensWithTheTemplateShiftsAndTheSkippedList() throws Exception {
        openWeek();
        when(employeeRepo.findAll()).thenReturn(List.of(officer("Sgt. Reyes", "Gate Guard", "MONDAY,WEDNESDAY", "Morning")));
        when(leaveRepo.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any()))
                .thenReturn(List.of(new StaffLeave("Sgt. Reyes", WED, WED)));

        MvcResult result = mvc.perform(post("/admin/staff/template-preview").with(csrf())
                .param("startDate", MON.toString()).param("endDate", SUN.toString()))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("previewMode", true))
            .andExpect(flash().attribute("previewSource", "templates"))
            .andReturn();

        Map<LocalDate, List<StaffSchedule>> shifts = (Map<LocalDate, List<StaffSchedule>>) result.getFlashMap().get("groupedShifts");
        assertThat(shifts.keySet()).containsExactly(MON);
        assertThat((List<String>) result.getFlashMap().get("templateSkipped")).containsExactly("Sgt. Reyes on " + WED + ": on leave");
        verify(staffRepo, never()).save(any()); // nothing is saved until Deploy
    }

    @Test
    void nothingToAddExplainsWhatToCheck() throws Exception {
        openWeek();
        when(employeeRepo.findAll()).thenReturn(List.of(officer("Sgt. NoDays", "Admin", null, null)));

        mvc.perform(post("/admin/staff/template-preview").with(csrf())
                .param("startDate", MON.toString()).param("endDate", SUN.toString()))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("error", containsString("No shifts to add from staff templates")))
            .andExpect(flash().attribute("error", containsString("No shift template (Working Days + Default Shift) for: Sgt. NoDays")));
    }

    @Test
    void badRangesAreRefused() throws Exception {
        mvc.perform(post("/admin/staff/template-preview").with(csrf()).param("startDate", WED.toString()).param("endDate", MON.toString()))
            .andExpect(flash().attribute("error", containsString("End Date cannot be before Start Date")));
        mvc.perform(post("/admin/staff/template-preview").with(csrf()).param("startDate", MON.toString())
                .param("endDate", MON.plusDays(ManagementController.TEMPLATE_MAX_DAYS).toString()))
            .andExpect(flash().attribute("error", containsString("at most " + ManagementController.TEMPLATE_MAX_DAYS + " days")));
        mvc.perform(post("/admin/staff/template-preview").with(csrf()).param("startDate", "not-a-date").param("endDate", MON.toString()))
            .andExpect(flash().attribute("error", "Please pick a valid start and end date."));
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void onlyTheDirectorCanFillTheRoster() throws Exception {
        mvc.perform(post("/admin/staff/template-preview").with(csrf())
                .param("startDate", MON.toString()).param("endDate", SUN.toString()))
            .andExpect(status().isForbidden());
    }

    @Test
    void deployingATemplatePlanSaysWhereTheShiftsCameFrom() throws Exception {
        mvc.perform(post("/admin/staff/ai-deploy").with(csrf()).param("source", "templates")
                .param("staffNames", "Sgt. Reyes").param("roles", "Gate Guard")
                .param("dutyDates", MON.toString()).param("shiftTimes", MORNING))
            .andExpect(flash().attribute("success", "🗓️ Deployed 1 shifts from staff templates to the Master Duty Roster."));
        mvc.perform(post("/admin/staff/ai-deploy").with(csrf())
                .param("staffNames", "Sgt. Reyes").param("roles", "Gate Guard")
                .param("dutyDates", TUE.toString()).param("shiftTimes", MORNING))
            .andExpect(flash().attribute("success", containsString("AI-planned shifts")));
    }

    @Test
    void theStaffPageHasTheButtonAndATemplatePreviewTitle() throws Exception {
        Map<LocalDate, List<StaffSchedule>> grouped = new TreeMap<>(Map.of(MON, List.of(shift("Sgt. Reyes", MON, MORNING))));
        mvc.perform(get("/admin/staff").flashAttr("previewMode", true).flashAttr("previewSource", "templates")
                .flashAttr("groupedShifts", grouped).flashAttr("templateSkipped", List.of("Sgt. Reyes on " + WED + ": on leave")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("formaction=\"/admin/staff/template-preview\"")))
            .andExpect(content().string(containsString("Fill from Staff Templates")))
            .andExpect(content().string(containsString("Template Roster Preview &amp; Edit")))
            .andExpect(content().string(containsString("name=\"source\" value=\"templates\"")))
            .andExpect(content().string(containsString("Sgt. Reyes on " + WED + ": on leave")));
    }
}
