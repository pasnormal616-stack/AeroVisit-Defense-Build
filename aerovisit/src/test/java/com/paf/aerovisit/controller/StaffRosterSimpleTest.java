package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import com.paf.aerovisit.FixedClock;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.MuseumEmployee;
import com.paf.aerovisit.model.StaffSchedule;
import com.paf.aerovisit.model.TimeWindowConfig;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;

/**
 * The simplified staff management (2026-11-06): a names-only Staff Directory, and a Duty Roster where the Director picks
 * a date and a time window and adds staff one at a time. No roles, templates, AI roster, publishing, leave or
 * deactivation. The gate records the signed-in staff account instead of reading the roster. No database; the clock is
 * fixed at noon Manila (FixedClock), so "today" never moves during a run.
 */
@WebMvcTest({ManagementController.class, GuardOperationController.class})
@Import({SecurityConfig.class, FixedClock.class})
@WithMockUser(roles = "DIRECTOR")
class StaffRosterSimpleTest {

    private static final LocalDate TODAY = FixedClock.TODAY;
    private static final LocalDate DAY = TODAY.plusDays(2);
    private static final TimeWindowConfig MORNING = new TimeWindowConfig("Morning", LocalTime.of(8, 0), LocalTime.of(12, 0));
    private static final TimeWindowConfig MIDDAY = new TimeWindowConfig("Midday", LocalTime.of(11, 0), LocalTime.of(14, 0));
    private static final TimeWindowConfig EVENING = new TimeWindowConfig("Evening", LocalTime.of(16, 0), LocalTime.of(20, 0));
    private static final String AM = "Morning (0800H-1200H)";   // the Morning window keeps its original roster string

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    @BeforeEach
    void windowsAndOpenDays() {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY, EVENING));
        openOn(TODAY.minusDays(3), TODAY.minusDays(1), TODAY, DAY, DAY.plusDays(1));
    }

    private void openOn(LocalDate... dates) {
        List<BookingSlot> slots = new ArrayList<>();
        for (LocalDate d : dates) {
            for (TimeWindowConfig w : List.of(MORNING, MIDDAY, EVENING)) {   // the roster's windows = the date's booking slots
                BookingSlot slot = new BookingSlot();
                slot.setSlotDate(d);
                slot.setTimeWindow(w.getLabel());
                slot.setMaxCapacity(50);
                slots.add(slot);
            }
        }
        when(bookingRepo.findAll()).thenReturn(slots);
    }

    private static StaffSchedule entry(long id, String name, LocalDate date, String shiftTime) {
        StaffSchedule s = new StaffSchedule();
        s.setId(id);
        s.setStaffName(name);
        s.setDutyDate(date);
        s.setShiftTime(shiftTime);
        s.setArchived(date.isBefore(TODAY));
        return s;
    }

    private StaffSchedule savedEntry() {
        ArgumentCaptor<StaffSchedule> saved = ArgumentCaptor.forClass(StaffSchedule.class);
        verify(staffRepo).save(saved.capture());
        return saved.getValue();
    }

    private org.springframework.test.web.servlet.ResultActions assign(String name, LocalDate date, String shiftTime) throws Exception {
        return mvc.perform(post("/admin/staff/add").with(csrf())
                .param("staffName", name).param("dutyDate", date.toString()).param("shiftTime", shiftTime));
    }

    private org.springframework.test.web.servlet.ResultActions update(long id, String name, LocalDate date, String shiftTime) throws Exception {
        return mvc.perform(post("/admin/staff/update").with(csrf()).param("id", String.valueOf(id))
                .param("staffName", name).param("dutyDate", date.toString()).param("shiftTime", shiftTime));
    }

    // --- Staff Directory: names only ---

    @Test
    void addingAnOfficerStoresTheTrimmedNameAndNothingElse() throws Exception {
        mvc.perform(post("/admin/employee/add").with(csrf()).param("name", "  Sgt. Reyes  "))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", "✅ Officer Sgt. Reyes added to the official museum roster."));

        ArgumentCaptor<MuseumEmployee> saved = ArgumentCaptor.forClass(MuseumEmployee.class);
        verify(employeeRepo).save(saved.capture());
        MuseumEmployee emp = saved.getValue();
        assertThat(emp.getName()).isEqualTo("Sgt. Reyes");
        assertThat(emp.getRole()).isNull();
        assertThat(emp.getEmail()).isNull();
        assertThat(emp.getWorkingDays()).isNull();
        assertThat(emp.getDefaultShift()).isNull();
    }

    @Test
    void aBlankOrOverlongNameIsRefused() throws Exception {
        mvc.perform(post("/admin/employee/add").with(csrf()).param("name", "   "))
            .andExpect(flash().attribute("error", "Add failed: the officer's name is required."));
        mvc.perform(post("/admin/employee/add").with(csrf()).param("name", "x".repeat(256)))
            .andExpect(flash().attribute("error", "Add failed: the name can be at most 255 characters."));
        verify(employeeRepo, never()).save(any());
    }

    @Test
    void renamingChangesOnlyTheNameAndCarriesItToActiveEntries() throws Exception {
        MuseumEmployee emp = new MuseumEmployee();
        emp.setName("Sgt. Reyes");
        emp.setRole("Gate Guard");                    // an older officer: the unused fields keep their values
        emp.setEmail("reyes@example.com");
        when(employeeRepo.findById(7L)).thenReturn(Optional.of(emp));
        StaffSchedule upcoming = entry(1, "Sgt. Reyes", DAY, AM);
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(upcoming));

        mvc.perform(post("/admin/employee/edit/7").with(csrf()).param("name", " SSgt. Reyes "))
            .andExpect(flash().attribute("success", "✅ Officer SSgt. Reyes updated."));

        assertThat(emp.getName()).isEqualTo("SSgt. Reyes");
        assertThat(emp.getRole()).isEqualTo("Gate Guard");
        assertThat(emp.getEmail()).isEqualTo("reyes@example.com");
        assertThat(upcoming.getStaffName()).isEqualTo("SSgt. Reyes");
    }

    @Test
    void removingAnOfficerNeverTouchesTheRoster() throws Exception {
        mvc.perform(post("/admin/employee/delete/5").with(csrf()))
            .andExpect(flash().attribute("success", "Officer removed from the official roster."));
        verify(employeeRepo).deleteById(5L);
        verifyNoInteractions(staffRepo);   // their entries stay and keep showing the name
    }

    // --- Duty Roster: one name, one date, one or more of that date's booking slots per Add ---

    @Test
    void assigningSavesOneEntryWithNoRole() throws Exception {
        assign("  Sgt. Reyes ", DAY, MIDDAY.getLabel())
            .andExpect(redirectedUrl("/admin/staff?day=" + DAY + "#roster"))   // back on that day of the Duty Roster
            .andExpect(flash().attribute("success", "✅ Sgt. Reyes is on duty " + MIDDAY.getLabel() + " on " + DAY + "."));

        StaffSchedule saved = savedEntry();
        assertThat(saved.getStaffName()).isEqualTo("Sgt. Reyes");
        assertThat(saved.getDutyDate()).isEqualTo(DAY);
        assertThat(saved.getShiftTime()).isEqualTo(MIDDAY.getLabel());
        assertThat(saved.getRole()).isNull();
        assertThat(saved.isArchived()).isFalse();
        assertThat(saved.isRosterNotified()).isFalse();
    }

    @Test
    void todayCanStillBeFilled() throws Exception {
        assign("Sgt. Reyes", TODAY, EVENING.getLabel()).andExpect(flash().attributeExists("success"));
        assertThat(savedEntry().getDutyDate()).isEqualTo(TODAY);
    }

    @Test
    void anAssignmentNeedsANameAnOpenDateTodayOrLaterAndAConfiguredWindow() throws Exception {
        assign("   ", DAY, AM)
            .andExpect(flash().attribute("error", "Choose a staff member from the Staff Directory. Nothing was saved."));
        assign("Sgt. Reyes", TODAY.minusDays(1), AM)
            .andExpect(flash().attribute("error", "⛔ " + TODAY.minusDays(1) + " is in the past. Past dates can be corrected, but not newly filled. Nothing was saved."));
        assign("Sgt. Reyes", DAY.plusDays(5), AM)
            .andExpect(flash().attribute("error", "❌ No shift assigned: the museum has no open public booking slots on " + DAY.plusDays(5) + "."));
        assign("Sgt. Reyes", DAY, "Graveyard (0000H-0600H)")
            .andExpect(flash().attribute("error", "⛔ \"Graveyard (0000H-0600H)\" is not one of the booking slots on " + DAY + ". Nothing was saved."));
        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", "Sgt. Reyes").param("dutyDate", "not-a-date").param("shiftTime", AM))
            .andExpect(flash().attribute("error", "Please pick a valid date. Nothing was saved."));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void theSamePersonCannotHoldTwoOverlappingWindowsOnOneDate() throws Exception {
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(DAY)).thenReturn(List.of(entry(1, "Sgt. Reyes", DAY, AM)));

        assign("SGT.  reyes", DAY, MIDDAY.getLabel())        // 11:00-14:00 overlaps 08:00-12:00; same person, other spelling
            .andExpect(flash().attribute("error", "⛔ SGT.  reyes is already on duty " + AM + " on " + DAY
                    + ", which overlaps " + MIDDAY.getLabel() + ". Nothing was saved."));
        verify(staffRepo, never()).save(any());

        assign("Lt. Santos", DAY, MIDDAY.getLabel()).andExpect(flash().attributeExists("success"));   // someone else
        assign("Sgt. Reyes", DAY, EVENING.getLabel()).andExpect(flash().attributeExists("success"));  // no overlap
    }

    // --- Edit and remove ---

    @Test
    void editingChangesTheNameAndTheTimeWindow() throws Exception {
        StaffSchedule e = entry(3, "Sgt. Reyes", DAY, AM);
        when(staffRepo.findById(3L)).thenReturn(Optional.of(e));
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(DAY)).thenReturn(List.of(e));

        update(3, " Lt. Santos ", DAY, EVENING.getLabel())
            .andExpect(flash().attribute("success", "✅ Shift assignment successfully updated for Lt. Santos."));

        assertThat(e.getStaffName()).isEqualTo("Lt. Santos");
        assertThat(e.getShiftTime()).isEqualTo(EVENING.getLabel());
        assertThat(e.getRole()).isNull();
        verify(staffRepo).save(e);
    }

    @Test
    void anEditCannotCreateAnOverlap() throws Exception {
        StaffSchedule reyesEvening = entry(3, "Sgt. Reyes", DAY, EVENING.getLabel());
        StaffSchedule santosMorning = entry(4, "Lt. Santos", DAY, AM);
        when(staffRepo.findById(3L)).thenReturn(Optional.of(reyesEvening));
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(DAY)).thenReturn(List.of(reyesEvening, santosMorning));

        update(3, "Lt. Santos", DAY, MIDDAY.getLabel())
            .andExpect(flash().attribute("error", "⛔ Lt. Santos is already on duty " + AM + " on " + DAY
                    + ", which overlaps " + MIDDAY.getLabel() + ". Nothing was saved."));
        assertThat(reyesEvening.getStaffName()).isEqualTo("Sgt. Reyes");
        verify(staffRepo, never()).save(any());
    }

    @Test
    void aPastEntryCanBeCorrectedButStaysOnItsDate() throws Exception {
        LocalDate past = TODAY.minusDays(3);
        StaffSchedule e = entry(8, "Sgt. Reyes", past, AM);
        assertThat(e.isArchived()).isTrue();
        when(staffRepo.findById(8L)).thenReturn(Optional.of(e));
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(past)).thenReturn(List.of(e));

        update(8, "Sgt. Cruz", past, EVENING.getLabel()).andExpect(flash().attributeExists("success"));
        assertThat(e.getStaffName()).isEqualTo("Sgt. Cruz");
        assertThat(e.getShiftTime()).isEqualTo(EVENING.getLabel());
        assertThat(e.getDutyDate()).isEqualTo(past);

        update(8, "Sgt. Cruz", TODAY.minusDays(1), EVENING.getLabel())
            .andExpect(flash().attribute("error", "⛔ A past entry can be corrected, but not moved to another date. Nothing was saved."));
        update(8, "Sgt. Cruz", DAY, EVENING.getLabel())
            .andExpect(flash().attribute("error", "⛔ A past entry can be corrected, but not moved to another date. Nothing was saved."));
        assertThat(e.getDutyDate()).isEqualTo(past);
    }

    @Test
    void aPastCorrectionIsCheckedAgainstThatDaysArchivedEntries() throws Exception {
        LocalDate past = TODAY.minusDays(3);
        StaffSchedule mine = entry(8, "Sgt. Cruz", past, EVENING.getLabel());
        StaffSchedule reyes = entry(9, "Sgt. Reyes", past, AM);       // archived too
        when(staffRepo.findById(8L)).thenReturn(Optional.of(mine));
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(past)).thenReturn(List.of(mine, reyes));

        update(8, "Sgt. Reyes", past, MIDDAY.getLabel())
            .andExpect(flash().attribute("error", "⛔ Sgt. Reyes is already on duty " + AM + " on " + past
                    + ", which overlaps " + MIDDAY.getLabel() + ". Nothing was saved."));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void aFutureEntryCannotMoveIntoThePastOrOntoAClosedDate() throws Exception {
        StaffSchedule e = entry(3, "Sgt. Reyes", DAY, AM);
        when(staffRepo.findById(3L)).thenReturn(Optional.of(e));

        update(3, "Sgt. Reyes", TODAY.minusDays(1), AM)
            .andExpect(flash().attribute("error", "⛔ " + TODAY.minusDays(1) + " is in the past. Past dates can be corrected, but not newly filled. Nothing was saved."));
        update(3, "Sgt. Reyes", DAY.plusDays(5), AM)
            .andExpect(flash().attribute("error", "❌ Update failed: The museum has no open booking slots on " + DAY.plusDays(5) + "."));
        update(3, "   ", DAY, AM)
            .andExpect(flash().attribute("error", "Choose a staff member from the Staff Directory. Nothing was saved."));
        update(99, "Sgt. Reyes", DAY, AM)
            .andExpect(flash().attribute("error", "Error: Shift record could not be found."));
        assertThat(e.getDutyDate()).isEqualTo(DAY);
        verify(staffRepo, never()).save(any());

        // another open date, in one of its booking slots: fine (its old roster string is not a slot there)
        update(3, "Sgt. Reyes", DAY.plusDays(1), MORNING.getLabel()).andExpect(flash().attributeExists("success"));
        assertThat(e.getDutyDate()).isEqualTo(DAY.plusDays(1));
    }

    @Test
    void removingAnEntryOnlyDeletesIt() throws Exception {
        mvc.perform(post("/admin/staff/ajax-delete/3").with(csrf())).andExpect(content().string("OK"));
        verify(staffRepo).deleteById(3L);
        mvc.perform(post("/admin/staff/ajax-bulk-delete").with(csrf()).param("ids", "4", "5")).andExpect(content().string("OK"));
        verify(staffRepo).deleteAllById(List.of(4L, 5L));
        verify(staffRepo, never()).saveAll(anyList());     // nothing re-queued for an email any more
        verify(staffRepo, never()).findById(any());
    }

    // --- Show past dates ---

    @Test
    void pastDatesAreListedOnlyWhenAskedFor() throws Exception {
        StaffSchedule past = entry(1, "Sgt. Archive", TODAY.minusDays(3), AM);
        StaffSchedule upcoming = entry(2, "Sgt. Upcoming", DAY, AM);
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(upcoming));
        when(staffRepo.findAllByOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(past, upcoming));

        String normal = mvc.perform(get("/admin/staff"))
            .andExpect(model().attribute("showPast", false))
            .andExpect(model().attribute("schedules", List.of(upcoming)))
            .andReturn().getResponse().getContentAsString();
        assertThat(normal).doesNotContain("Sgt. Archive").contains("Sgt. Upcoming")
            .containsPattern("id=\"showPastDates\"[^>]*>")
            .doesNotContainPattern("id=\"showPastDates\"[^>]*checked=\"checked\"");  // the switch, not ticked

        String withPast = mvc.perform(get("/admin/staff").param("past", "1"))
            .andExpect(model().attribute("showPast", true))
            .andExpect(model().attribute("schedules", List.of(past, upcoming)))
            .andReturn().getResponse().getContentAsString();
        assertThat(withPast).contains("Sgt. Archive").contains("Sgt. Upcoming")
            .containsPattern("id=\"showPastDates\"[^>]*checked=\"checked\"")
            .contains("class=\"av-badge av-badge--warn ms-1\">Past</span>");
    }

    // --- Pages: no roles, templates, AI roster, publishing, leave or deactivation ---

    @Test
    void theStaffPageOffersNamesOnlyAndOneAtATimeAssignment() throws Exception {
        MuseumEmployee legacy = new MuseumEmployee();
        legacy.setId(7L);
        legacy.setName("Sgt. Reyes");
        legacy.setRole("Gate Guard");
        legacy.setEmail("reyes@example.com");
        legacy.setWorkingDays("MONDAY,FRIDAY");
        legacy.setDefaultShift("Morning");
        legacy.setActive(false);                                   // an officer deactivated before: just a name now
        when(employeeRepo.findAll()).thenReturn(List.of(legacy));
        StaffSchedule old = entry(1, "Sgt. Reyes", DAY, AM);
        old.setRole("Tour Guide");
        old.setRosterNotified(true);
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(old));

        String html = mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html)
            .contains("<i class=\"bi bi-shield-check\"></i>Duty Roster</button>")
            .contains("name=\"dutyDate\" id=\"rosterDate\" value=\"" + TODAY + "\"")   // the day view opens on today
            .contains("<option value=\"Sgt. Reyes\">Sgt. Reyes</option>")
            .contains("Show past dates")
            .contains("data-staff-name=\"Sgt. Reyes\"")
            .contains("\"staffName\":\"Sgt. Reyes\"");
        assertThat(html)
            .doesNotContain("Master Duty Roster")
            .doesNotContain("Gate Guard").doesNotContain("Tour Guide").doesNotContain("name=\"role\"").doesNotContain("Primary Role")
            .doesNotContain("reyes@example.com").doesNotContain("name=\"email\"")
            .doesNotContain("workingDays").doesNotContain("defaultShift").doesNotContain("Shift Template")
            .doesNotContain("ai-preview").doesNotContain("ai-deploy").doesNotContain("AI Roster")
            .doesNotContain("template-preview").doesNotContain("Fill from Staff Templates")
            .doesNotContain("publish-roster").doesNotContain("Publish Roster").doesNotContain("rosterNotified")
            .doesNotContain("staff/leave").doesNotContain("Staff Leave")
            .doesNotContain("employee/deactivate").doesNotContain("employee/reactivate").doesNotContain("Inactive")
            .doesNotContain("name=\"startDate\" id=\"rosterStartDate\"").doesNotContain("excludeWeekends");
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void theDutyRosterPageListsNamesByDateThenTimeWindowWithoutRoles() throws Exception {
        StaffSchedule a = entry(1, "Sgt. Zamora", DAY, EVENING.getLabel());
        StaffSchedule b = entry(2, "Lt. Santos", DAY, AM);
        StaffSchedule c = entry(3, "Sgt. Abad", DAY, AM);
        StaffSchedule d = entry(4, "Sgt. Cruz", TODAY, MIDDAY.getLabel());
        a.setRole("Gate Guard");
        b.setRole("Tour Guide");
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(new ArrayList<>(List.of(a, b, c, d)));

        String html = mvc.perform(get("/admin/calendar")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("<h1 class=\"av-pagehead-title\">Duty Roster</h1>")
            .doesNotContain("Master Duty Roster").doesNotContain("Gate Guard").doesNotContain("Tour Guide")
            .doesNotContain("modalStaffRole").doesNotContain("\"role\"");
        String list = html.substring(html.indexOf("id=\"dutyList\""));
        // today first; then DAY: Morning (Lt. Santos, Sgt. Abad: by name) before Evening (Zamora)
        assertThat(list).containsSubsequence("Sgt. Cruz", AM, "Lt. Santos", "Sgt. Abad", EVENING.getLabel(), "Sgt. Zamora");
    }

    // --- The read-only Duty Roster page is the guards' view; the Director uses Slots & Staff -> Duty Roster ---

    @Test
    void theDirectorSidebarNoLongerLinksTheReadOnlyDutyRoster() throws Exception {
        String html = mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String sidebar = html.substring(html.indexOf("id=\"avSidebar\""), html.indexOf("</aside>"));
        assertThat(sidebar).doesNotContain("href=\"/admin/calendar\"")
            .contains("href=\"/admin/blacklist\"").contains("Banned Visitors")
            .contains("href=\"/admin/staff\"").contains("Slots &amp; Staff");
    }

    @Test
    void theOldDirectorLinkRedirectsToSlotsAndStaffDutyRoster() throws Exception {
        mvc.perform(get("/admin/calendar"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/admin/staff#roster"));

        verify(staffRepo, never()).findByArchivedFalseOrderByDutyDateAscStaffNameAsc();
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void aGuardStillSeesTheDutyRosterInTheSidebarAndOnItsPage() throws Exception {
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc())
            .thenReturn(new ArrayList<>(List.of(entry(1, "Sgt. Reyes", DAY, AM))));

        String html = mvc.perform(get("/admin/calendar")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        String sidebar = html.substring(html.indexOf("id=\"avSidebar\""), html.indexOf("</aside>"));
        assertThat(sidebar).containsPattern("<a href=\"/admin/calendar\" class=\"av-nav-link is-active\"[^>]*>\\s*<i class=\"bi bi-calendar-week\"></i><span>Duty Roster</span>");
        String list = html.substring(html.indexOf("id=\"dutyList\""));
        assertThat(list).containsSubsequence(AM, "Sgt. Reyes");
    }

    @Test
    void rosterOrderIsDateThenWindowStartThenName() {
        List<StaffSchedule> rows = new ArrayList<>(List.of(
            entry(1, "zed", DAY, EVENING.getLabel()), entry(2, "Bea", DAY, AM), entry(3, "amy", DAY, AM),
            entry(4, "Carl", TODAY, MIDDAY.getLabel())));
        rows.sort(ManagementController.ROSTER_ORDER);
        assertThat(rows).extracting(StaffSchedule::getStaffName).containsExactly("Carl", "amy", "Bea", "zed");

        Map<LocalDate, Map<String, List<StaffSchedule>>> grouped = ManagementController.byDateThenWindow(rows);
        assertThat(grouped.keySet()).containsExactly(TODAY, DAY);
        assertThat(grouped.get(DAY).keySet()).containsExactly(AM, EVENING.getLabel());
        assertThat(grouped.get(DAY).get(AM)).extracting(StaffSchedule::getStaffName).containsExactly("amy", "Bea");
    }

    // --- The gate: the signed-in account, never the roster ---

    private Visitor approvedToday() {
        Visitor v = new Visitor();
        v.setId(5L);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setStatus("APPROVED");
        v.setVisitDate(TODAY);
        when(repo.findById(5L)).thenReturn(Optional.of(v));
        when(repo.findByReferenceId("PAF-A7X92K")).thenReturn(Optional.of(v));
        return v;
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void aCheckInRecordsTheSignedInAccountWithoutReadingTheRoster() throws Exception {
        Visitor v = approvedToday();
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.status").value("success"));

        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(v.getCheckedInBy()).isEqualTo("guard");
        verify(emailService, timeout(2000)).sendCheckInGuidelines("juan@example.com", "Juan Dela Cruz");
        verifyNoInteractions(staffRepo);
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void aGateDenialRecordsTheSignedInAccount() throws Exception {
        Visitor v = approvedToday();
        v.setStatus("CHECKED-IN");
        v.setCheckedInBy("Sgt. Ramos");
        v.setCheckInTime(FixedClock.NOW.minusMinutes(2));
        mvc.perform(post("/admin/api/deny-entry").with(csrf()).param("visitorId", "5").param("reason", "Group does not match manifest"))
            .andExpect(jsonPath("$.status").value("success"));
        assertThat(v.getEntryDeniedBy()).isEqualTo("guard");
        verifyNoInteractions(staffRepo);
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void aManualCheckInRecordsTheSignedInAccountAsManual() throws Exception {
        Visitor v = approvedToday();
        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-A7X92K"))
            .andExpect(status().is3xxRedirection());

        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
        assertThat(v.getCheckedInBy()).isEqualTo("guard (Manual)");
        verify(emailService).sendCheckInGuidelines("juan@example.com", "Juan Dela Cruz");
        verifyNoInteractions(staffRepo);
    }

    @Test
    void theCheckInEmailNoLongerNamesATourGuide() throws Exception {
        String template = new String(getClass().getResourceAsStream("/templates/email/check-in.html").readAllBytes(), StandardCharsets.UTF_8);
        assertThat(template).doesNotContain("tourGuideName").doesNotContain("Your tour guide for today")
            .contains("Our tour guides and museum personnel are stationed throughout the gallery.");
    }
}
