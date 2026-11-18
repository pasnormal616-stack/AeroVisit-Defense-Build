package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.paf.aerovisit.FixedClock;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Holiday;
import com.paf.aerovisit.model.MuseumEmployee;
import com.paf.aerovisit.model.StaffSchedule;
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
 * 🧲 The drag-and-drop Duty Roster (2026-11-10): a date opens in place as its booking-slot windows plus the Staff
 * Directory pool; a drop is saved at once in the background (assign, move), a name is removed with ajax-delete; closed
 * dates (no slots, holiday, emergency closure) take no names; the guards' page re-reads /admin/calendar/entries. The
 * roster lives in an in-memory list here (no database); the clock is fixed at noon Manila (FixedClock).
 */
@WebMvcTest(ManagementController.class)
@Import({SecurityConfig.class, FixedClock.class, Holidays.class})
@WithMockUser(roles = "DIRECTOR")
class DutyRosterDragDropTest {

    private static final LocalDate TODAY = FixedClock.TODAY;
    private static final LocalDate DAY = TODAY.plusDays(2);
    private static final LocalDate HOLIDAY = TODAY.plusDays(3);
    private static final LocalDate CLOSURE = TODAY.plusDays(4);
    private static final LocalDate NO_SLOTS = TODAY.plusDays(5);
    private static final String T930 = new TimeWindowConfig("Tour", LocalTime.of(9, 30), LocalTime.of(10, 30)).getLabel();
    private static final String T1030 = new TimeWindowConfig("Tour", LocalTime.of(10, 30), LocalTime.of(11, 30)).getLabel();
    private static final String T1130 = new TimeWindowConfig("Tour", LocalTime.of(11, 30), LocalTime.of(12, 30)).getLabel();

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

    private final List<BookingSlot> slots = new ArrayList<>();
    private final List<StaffSchedule> roster = new ArrayList<>();   // the "database"
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeEach
    void setUp() {
        when(bookingRepo.findAll()).thenReturn(slots);
        slotsOn(DAY, T1130, T930, T1030);          // out of order on purpose
        slotsOn(TODAY.minusDays(1), T930);         // a past open date
        slotsOn(HOLIDAY, T930);                    // has a slot, but is a listed holiday
        slotsOn(CLOSURE, T930);                    // has a slot, but is under an emergency closure
        when(holidayRepo.findAllByOrderByHolidayDateAsc()).thenReturn(List.of(new Holiday(HOLIDAY, "Bonifacio Day", false)));
        when(repo.findClosureDates()).thenReturn(List.of(CLOSURE));
        when(employeeRepo.findAll()).thenReturn(List.of(employee("Sgt. Reyes"), employee("lt. Abad"), employee("Cpl. Cruz")));

        // The roster repository, kept in memory so a save shows up in later reads
        when(staffRepo.save(any(StaffSchedule.class))).thenAnswer(inv -> {
            StaffSchedule s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(ids.incrementAndGet());
                roster.add(s);
            }
            return s;
        });
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(any())).thenAnswer(inv ->
            new ArrayList<>(roster.stream().filter(s -> inv.getArgument(0).equals(s.getDutyDate())).toList()));
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenAnswer(inv -> new ArrayList<>(roster));
        when(staffRepo.findAllByOrderByDutyDateAscStaffNameAsc()).thenAnswer(inv -> new ArrayList<>(roster));
        when(staffRepo.findById(anyLong())).thenAnswer(inv -> roster.stream().filter(s -> inv.getArgument(0).equals(s.getId())).findFirst());
    }

    private void slotsOn(LocalDate date, String... windows) {
        for (String w : windows) {
            BookingSlot s = new BookingSlot();
            s.setSlotDate(date);
            s.setTimeWindow(w);
            s.setMaxCapacity(50);
            slots.add(s);
        }
    }

    private static MuseumEmployee employee(String name) {
        MuseumEmployee e = new MuseumEmployee();
        e.setName(name);
        return e;
    }

    private StaffSchedule existing(String name, LocalDate date, String window) {
        StaffSchedule s = new StaffSchedule();
        s.setId(ids.incrementAndGet());
        s.setStaffName(name);
        s.setDutyDate(date);
        s.setShiftTime(window);
        roster.add(s);
        return s;
    }

    private ResultActions assign(String name, LocalDate date, String window) throws Exception {
        return mvc.perform(post("/admin/staff/roster/assign").with(csrf())
                .param("staffName", name).param("dutyDate", date.toString()).param("shiftTime", window));
    }

    private ResultActions day(LocalDate date) throws Exception {
        return mvc.perform(get("/admin/staff/roster/day").param("date", date.toString()));
    }

    // --- Clicking an open date shows its windows and the staff pool ---

    @Test
    void anOpenDateShowsItsWindowsInTimeOrderAndTheWholeStaffPool() throws Exception {
        day(DAY).andExpect(status().isOk())
            .andExpect(jsonPath("$.date").value(DAY.toString()))
            .andExpect(jsonPath("$.open").value(true))
            .andExpect(jsonPath("$.editable").value(true))
            .andExpect(jsonPath("$.windows[*].window", contains(T930, T1030, T1130)))
            .andExpect(jsonPath("$.windows[0].times").value("9:30 AM – 10:30 AM"))
            .andExpect(jsonPath("$.windows[0].summary").value("9:30 AM – 10:30 AM · 50 seats (0 booked)"))
            .andExpect(jsonPath("$.staff", contains("Cpl. Cruz", "lt. Abad", "Sgt. Reyes")))   // A to Z, case ignored
            .andExpect(jsonPath("$.entries", empty()));
    }

    @Test
    void theRosterStillOpensOnTodayAndThePanelHasItsDropZonesAndPool() throws Exception {
        String html = mvc.perform(get("/admin/staff")).andExpect(status().isOk())
                .andExpect(model().attribute("rosterDay", TODAY.toString()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).containsSubsequence("id=\"rosterDayPanel\"", "id=\"rosterWindowsBox\"", "id=\"rosterPoolBox\"", "id=\"rosterPool\"")
            .contains("window.AV_ROSTER = { day: {\"date\":\"" + TODAY + "\"")
            .contains("<script src=\"/js/duty-roster.js\"></script>")
            .contains("if (window.DutyRoster) { DutyRoster.open(date, true); return; }")   // a date opens in place
            .doesNotContain("id=\"assignStaffForm\"");
    }

    // --- Dragging a name onto a window assigns it, saved without a page reload ---

    @Test
    void droppingANameOntoAWindowSavesItInTheBackground() throws Exception {
        assign("Sgt. Reyes", DAY, T1030).andExpect(status().isOk())
            .andExpect(header().doesNotExist("Location"))                          // JSON, not a redirect: no reload
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.staffName").value("Sgt. Reyes"))
            .andExpect(jsonPath("$.dutyDate").value(DAY.toString()))
            .andExpect(jsonPath("$.shiftTime").value(T1030));

        assertThat(roster).singleElement().satisfies(s -> {
            assertThat(s.getStaffName()).isEqualTo("Sgt. Reyes");
            assertThat(s.getDutyDate()).isEqualTo(DAY);
            assertThat(s.getShiftTime()).isEqualTo(T1030);
        });
    }

    @Test
    void severalNamesCanShareOneWindow() throws Exception {
        assign("Sgt. Reyes", DAY, T930).andExpect(status().isOk());
        assign("lt. Abad", DAY, T930).andExpect(status().isOk());
        assign("Cpl. Cruz", DAY, T930).andExpect(status().isOk());

        day(DAY).andExpect(jsonPath("$.entries[*].staffName", contains("Cpl. Cruz", "lt. Abad", "Sgt. Reyes")))
                .andExpect(jsonPath("$.entries[*].shiftTime", contains(T930, T930, T930)));
    }

    @Test
    void oneNameCanCoverSeveralWindowsTheSameDay() throws Exception {
        assign("Sgt. Reyes", DAY, T930).andExpect(status().isOk());
        assign("Sgt. Reyes", DAY, T1030).andExpect(status().isOk());   // back to back: not an overlap
        assign("Sgt. Reyes", DAY, T1130).andExpect(status().isOk());

        assertThat(roster).extracting(StaffSchedule::getShiftTime).containsExactly(T930, T1030, T1130);
    }

    @Test
    void theSameNameTwiceOnOneWindowIsRefused() throws Exception {
        existing("Sgt. Reyes", DAY, T930);
        assign("  sgt.  reyes ", DAY, T930).andExpect(status().isConflict())
            .andExpect(jsonPath("$.error", containsString("is already on duty")));
        assertThat(roster).hasSize(1);
    }

    @Test
    void aNameOutsideTheDirectoryAPastDateOrAWindowThatIsNotASlotIsRefused() throws Exception {
        assign("Someone Else", DAY, T930).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("Choose a staff member from the Staff Directory. Nothing was saved."));
        assign("Sgt. Reyes", TODAY.minusDays(1), T930).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("is in the past")));
        assign("Sgt. Reyes", DAY, "Evening (04:00 PM - 08:00 PM)").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error", containsString("is not one of the booking slots")));
        assign("Sgt. Reyes", DAY, T930).andExpect(status().isOk());   // ...and a good one still goes through
        assertThat(roster).hasSize(1);
    }

    // --- Removing an assigned name unassigns it; dragging it onto another window moves it ---

    @Test
    void removingAnAssignedNameUnassignsIt() throws Exception {
        StaffSchedule placed = existing("Sgt. Reyes", DAY, T930);

        mvc.perform(post("/admin/staff/ajax-delete/" + placed.getId()).with(csrf())).andExpect(status().isOk());

        verify(staffRepo).deleteById(placed.getId());
    }

    @Test
    void draggingAPlacedNameOntoAnotherWindowMovesIt() throws Exception {
        StaffSchedule placed = existing("Sgt. Reyes", DAY, T930);

        mvc.perform(post("/admin/staff/roster/move").with(csrf()).param("id", placed.getId().toString()).param("shiftTime", T1130))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(placed.getId()))
            .andExpect(jsonPath("$.shiftTime").value(T1130));
        assertThat(placed.getShiftTime()).isEqualTo(T1130);

        // onto a window the same person already covers: refused, nothing changes
        existing("Sgt. Reyes", DAY, T930);
        mvc.perform(post("/admin/staff/roster/move").with(csrf()).param("id", placed.getId().toString()).param("shiftTime", T930))
            .andExpect(status().isConflict());
        assertThat(placed.getShiftTime()).isEqualTo(T1130);
    }

    // --- A closed / holiday date isn't clickable for assignment ---

    @Test
    void closedDatesAreNotClickableAndTakeNoNames() throws Exception {
        day(HOLIDAY).andExpect(jsonPath("$.open").value(false)).andExpect(jsonPath("$.editable").value(false))
            .andExpect(jsonPath("$.closedReason").value("Closed: Bonifacio Day")).andExpect(jsonPath("$.staff", empty()));
        day(CLOSURE).andExpect(jsonPath("$.open").value(false)).andExpect(jsonPath("$.closedReason").value("Closed: emergency closure"))
            .andExpect(jsonPath("$.staff", empty()));
        day(NO_SLOTS).andExpect(jsonPath("$.open").value(false)).andExpect(jsonPath("$.closedReason").value("No booking slots on this date"))
            .andExpect(jsonPath("$.staff", empty()));

        // the calendar only lets open dates be clicked: holidays and closures are left out, holidays are marked Closed
        mvc.perform(get("/admin/staff"))
            .andExpect(model().attribute("openDates", hasItem(DAY.toString())))
            .andExpect(model().attribute("openDates", not(hasItem(HOLIDAY.toString()))))
            .andExpect(model().attribute("openDates", not(hasItem(CLOSURE.toString()))))
            .andExpect(model().attribute("openDates", not(hasItem(NO_SLOTS.toString()))))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                .string(containsString("dateClick: function(info) { if (openDates.has(info.dateStr)) openRosterDay(info.dateStr); }")));

        // and the server refuses names there even if someone tries
        assign("Sgt. Reyes", HOLIDAY, T930).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("Closed: Bonifacio Day")));
        assign("Sgt. Reyes", CLOSURE, T930).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("emergency closure")));
        assign("Sgt. Reyes", NO_SLOTS, T930).andExpect(status().isBadRequest());
        assertThat(roster).isEmpty();
    }

    @Test
    void aPastOpenDateShowsItsNamesButTakesNoNewOnes() throws Exception {
        existing("Sgt. Reyes", TODAY.minusDays(1), T930);
        day(TODAY.minusDays(1)).andExpect(jsonPath("$.open").value(true)).andExpect(jsonPath("$.past").value(true))
            .andExpect(jsonPath("$.editable").value(false)).andExpect(jsonPath("$.staff", empty()))
            .andExpect(jsonPath("$.entries[0].staffName").value("Sgt. Reyes"));
    }

    // --- Reopening a date shows its existing assignments under their windows ---

    @Test
    void reopeningADateShowsWhoIsAssignedUnderTheirWindows() throws Exception {
        assign("Sgt. Reyes", DAY, T1130).andExpect(status().isOk());
        assign("lt. Abad", DAY, T930).andExpect(status().isOk());
        existing("Cpl. Cruz", DAY, "Morning (0800H-1200H)");   // an older label: listed under "Other times"

        day(DAY).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[*].staffName", contains("Cpl. Cruz", "lt. Abad", "Sgt. Reyes")))   // roster order
            .andExpect(jsonPath("$.entries[?(@.staffName == 'lt. Abad')].shiftTime", contains(T930)))
            .andExpect(jsonPath("$.entries[?(@.staffName == 'Sgt. Reyes')].shiftTime", contains(T1130)))
            .andExpect(jsonPath("$.entries[?(@.staffName == 'Cpl. Cruz')].shiftTime", contains("Morning (0800H-1200H)")));

        // and the page itself, opened on that day, carries the same placement
        String html = mvc.perform(get("/admin/staff").param("day", DAY.toString())).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("\"staffName\":\"Sgt. Reyes\",\"dutyDate\":\"" + DAY + "\",\"shiftTime\":\"" + T1130 + "\"");
    }

    // --- An assignment made by the Director appears on the guard side without a refresh ---

    @Test
    void anAssignmentAppearsInTheGuardsLiveFeed() throws Exception {
        mvc.perform(get("/admin/calendar/entries").with(user("guard").roles("GUARD")))
            .andExpect(status().isOk()).andExpect(jsonPath("$", empty()));

        assign("Sgt. Reyes", DAY, T930).andExpect(status().isOk());

        mvc.perform(get("/admin/calendar/entries").with(user("guard").roles("GUARD")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].staffName").value("Sgt. Reyes"))
            .andExpect(jsonPath("$[0].dutyDate").value(DAY.toString()))
            .andExpect(jsonPath("$[0].shiftTime").value(T930));

        // the guard page polls that feed and redraws the calendar and the Duty List from it
        String page = mvc.perform(get("/admin/calendar").with(user("guard").roles("GUARD"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("fetch('/admin/calendar/entries'").contains("setInterval(refresh, EVERY_MS);")
            .contains("drawDutyList(entries);").contains("calendar.addEventSource(entries.map(toEvent));")
            .contains("title: staff.staffName + (tinyShift ? '\\n(' + tinyShift + ')' : ''),");   // one line: valid JS
    }

    // --- A Guard is view-only ---

    @Test
    @WithMockUser(roles = "GUARD")
    void aGuardIsRefusedWriteAccessToTheRoster() throws Exception {
        StaffSchedule placed = existing("Sgt. Reyes", DAY, T930);

        assign("Sgt. Reyes", DAY, T1030).andExpect(status().isForbidden());
        mvc.perform(post("/admin/staff/roster/move").with(csrf()).param("id", placed.getId().toString()).param("shiftTime", T1130))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/staff/ajax-delete/" + placed.getId()).with(csrf())).andExpect(status().isForbidden());
        day(DAY).andExpect(status().isForbidden());

        verify(staffRepo, never()).save(any());
        verify(staffRepo, never()).deleteById(any());
        assertThat(placed.getShiftTime()).isEqualTo(T930);

        // ...but can still read the roster
        mvc.perform(get("/admin/calendar/entries")).andExpect(status().isOk()).andExpect(jsonPath("$[0].staffName").value("Sgt. Reyes"));
        mvc.perform(get("/admin/calendar")).andExpect(status().isOk());
    }

    @Test
    void aDropWithoutTheCsrfTokenIsRefused() throws Exception {
        mvc.perform(post("/admin/staff/roster/assign").param("staffName", "Sgt. Reyes").param("dutyDate", DAY.toString()).param("shiftTime", T930))
            .andExpect(status().isForbidden());
        assertThat(roster).isEmpty();
        verify(staffRepo, never()).save(any());
    }
}
