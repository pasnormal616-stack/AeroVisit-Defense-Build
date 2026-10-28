package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Waitlist FIFO order on the Approvals Queue and the Today's Capacity panel. No database: repositories are mocked. */
@WebMvcTest({AdminDashboardController.class, ManagementController.class})
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class WaitlistAndCapacityTest {

    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final String AFTERNOON = "Afternoon (01:00 PM - 05:00 PM)";

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

    private static Visitor visitor(long id, String status, LocalDateTime createdAt) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("Leisure");
        v.setStatus(status);
        v.setCreatedAt(createdAt);
        return v;
    }

    private static Visitor booking(long id, String status, String window, int groupSize) {
        Visitor v = visitor(id, status, null);
        v.setVisitDate(LocalDate.now());
        v.setTimeWindow(window);
        v.setGroupSize(groupSize);
        return v;
    }

    private static BookingSlot slot(LocalDate date, String window, int maxCapacity) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(window);
        s.setMaxCapacity(maxCapacity);
        return s;
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void directorSeesForceApproveAndTheCapacityRejectReason() throws Exception {
        Visitor master = visitor(8, "PENDING", null);
        master.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        when(repo.findAll()).thenReturn(List.of(visitor(3, "PENDING", null), master, visitor(9, "APPROVED", null)));

        mvc.perform(get("/admin").param("filter", "all"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("action=\"/admin/approve-override/3\" method=\"post\"")))
            .andExpect(content().string(containsString("Force Approve</button>")))
            // In the row's ⋮ menu, not a loud yellow button beside Approve on every row
            .andExpect(content().string(containsString("class=\"dropdown-item text-warning\"")))
            .andExpect(content().string(not(containsString("btn btn-sm btn-warning"))))
            // Master Bookings and non-pending rows get no override button
            .andExpect(content().string(not(containsString("/admin/approve-override/8"))))
            .andExpect(content().string(not(containsString("/admin/approve-override/9"))))
            .andExpect(content().string(containsString("<option value=\"Capacity Reached\">Capacity Reached - Please choose another date</option>")));
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardCannotReachTheApprovalQueueOrApprove() throws Exception {
        when(repo.findAll()).thenReturn(List.of(visitor(3, "PENDING", null)));

        mvc.perform(get("/admin").param("filter", "pending"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/guard/dashboard"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/approve/3").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/approve-override/3").with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pendingQueueIsOldestFirstWithUntimestampedRowsOnTop() throws Exception {
        LocalDateTime base = LocalDateTime.of(2026, 10, 12, 8, 30);
        when(repo.findByStatusIn(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(
            visitor(5, "PENDING", base.plusHours(2)),
            visitor(3, "PENDING", base),
            visitor(7, "PENDING", null),
            visitor(9, "APPROVED", base.minusDays(1)),
            visitor(2, "VERIFIED", null),
            visitor(4, "PENDING_MANUAL_REVIEW", base.plusMinutes(5))));

        MvcResult result = mvc.perform(get("/admin").param("filter", "pending"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("<th>Requested On</th>")))
            .andExpect(content().string(containsString("Oct 12, 08:30 AM")))
            .andExpect(content().string(containsString("Not recorded")))
            .andReturn();

        List<Visitor> queue = (List<Visitor>) result.getModelAndView().getModel().get("listVisitors");
        assertThat(queue).extracting(Visitor::getId).containsExactly(2L, 7L, 3L, 4L, 5L);
    }

    @Test
    void requestedOnColumnOnlyShowsOnThePendingQueue() throws Exception {
        when(repo.findByVisitDate(LocalDate.now())).thenReturn(new ArrayList<>(List.of(visitor(1, "APPROVED", LocalDateTime.now()))));

        mvc.perform(get("/admin"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("<th>Requested On</th>"))));
    }

    // --- Daily Capacity Tracker (admin.html) ---

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> capacityRows(MvcResult result) {
        return (List<Map<String, Object>>) result.getModelAndView().getModel().get("dailyCapacity");
    }

    // The date picker must carry the chosen date and submit only through its View button (typing a date must not auto-submit)
    private static org.hamcrest.Matcher<String> datePickerShowing(LocalDate date) {
        return org.hamcrest.Matchers.matchesPattern("(?s).*<input type=\"date\" id=\"capacityDate\" name=\"capacityDate\"[^>]*value=\"" + date + "\">"
            + "\\s*<button type=\"submit\" class=\"btn btn-sm btn-primary\">View</button>\\s*</form>.*");
    }

    @Test
    void capacityDefaultsToTodayAndCountsSeatHoldingBookingsOnly() throws Exception {
        LocalDate today = LocalDate.now();
        when(bookingRepo.findAll()).thenReturn(List.of(
            slot(today, AFTERNOON, 5),
            slot(today, MORNING, 10),
            slot(today.plusDays(1), MORNING, 50)));
        when(repo.findByVisitDate(today)).thenReturn(new ArrayList<>(List.of( // the Today's Manifest table sorts this list in place
            booking(1, "APPROVED", MORNING, 4),
            booking(2, "PENDING", MORNING, 3),              // waitlist: holds no seat
            booking(3, "CANCELLED_BY_VISITOR", MORNING, 2), // cancelled: holds no seat
            booking(4, "CHECKED-IN", AFTERNOON, 5))));

        MvcResult result = mvc.perform(get("/admin"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("targetDate", today))
            .andExpect(content().string(containsString("Daily Capacity Tracker")))
            .andExpect(content().string(containsString("FULL")))
            .andExpect(content().string(datePickerShowing(today)))
            .andExpect(content().string(containsString("<input type=\"hidden\" name=\"filter\" value=\"today\">")))
            .andReturn();

        List<Map<String, Object>> rows = capacityRows(result);
        assertThat(rows).extracting(r -> r.get("window")).containsExactly(MORNING, AFTERNOON);
        assertThat(rows).extracting(r -> r.get("remaining")).containsExactly(6, 0);
        assertThat(rows).extracting(r -> r.get("maxCapacity")).containsExactly(10, 5);
    }

    @Test
    void pickedDateIsUsedAndTheCurrentTabIsKept() throws Exception {
        LocalDate picked = LocalDate.now().plusDays(10);
        when(bookingRepo.findAll()).thenReturn(List.of(slot(LocalDate.now(), MORNING, 99), slot(picked, MORNING, 20)));
        when(repo.findByVisitDate(picked)).thenReturn(List.of(booking(1, "APPROVED", MORNING, 8)));
        when(repo.findByStatusIn(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(List.of(visitor(3, "PENDING", LocalDateTime.now())));

        MvcResult result = mvc.perform(get("/admin").param("filter", "pending").param("capacityDate", picked.toString()))
            .andExpect(status().isOk())
            .andExpect(model().attribute("targetDate", picked))
            .andExpect(model().attribute("currentFilter", "pending"))
            .andExpect(content().string(datePickerShowing(picked)))
            .andExpect(content().string(containsString("<input type=\"hidden\" name=\"filter\" value=\"pending\">")))
            .andExpect(content().string(containsString("<th>Requested On</th>"))) // still on the Pending tab
            .andReturn();

        assertThat(capacityRows(result)).extracting(r -> r.get("remaining")).containsExactly(12);
        assertThat(capacityRows(result)).extracting(r -> r.get("maxCapacity")).containsExactly(20);
    }

    @Test
    void entireDatabaseTabIsKeptToo() throws Exception {
        mvc.perform(get("/admin").param("filter", "all").param("capacityDate", "2026-12-25"))
            .andExpect(model().attribute("currentFilter", "all"))
            .andExpect(content().string(containsString("<input type=\"hidden\" name=\"filter\" value=\"all\">")));
    }

    @Test
    void buyoutAndClosureAreCheckedAgainstThePickedDate() throws Exception {
        LocalDate buyoutDay = LocalDate.now().plusDays(5);
        LocalDate closedDay = LocalDate.now().plusDays(6);
        LocalDate openDay = LocalDate.now().plusDays(7);
        when(bookingRepo.findAll()).thenReturn(List.of(slot(buyoutDay, MORNING, 10), slot(closedDay, MORNING, 10), slot(openDay, MORNING, 10)));
        when(repo.findBuyoutDates()).thenReturn(List.of(buyoutDay));
        when(repo.findClosureDates()).thenReturn(List.of(closedDay));

        for (Object[] c : new Object[][] {{buyoutDay, 0}, {closedDay, 0}, {openDay, 10}}) {
            MvcResult result = mvc.perform(get("/admin").param("filter", "pending").param("capacityDate", c[0].toString()))
                .andExpect(status().isOk()).andReturn();
            assertThat(capacityRows(result)).as("remaining on " + c[0]).extracting(r -> r.get("remaining")).containsExactly(c[1]);
        }
    }

    @Test
    void blankDateFallsBackToToday() throws Exception {
        mvc.perform(get("/admin").param("filter", "pending").param("capacityDate", ""))
            .andExpect(status().isOk())
            .andExpect(model().attribute("targetDate", LocalDate.now()));
    }

    @Test
    void dateWithNoSlotsShowsAnEmptyState() throws Exception {
        mvc.perform(get("/admin").param("filter", "pending").param("capacityDate", "2026-12-25"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("No booking slots are open on Fri, Dec 25, 2026.")));
    }

    @Test
    void capacityPanelIsGoneFromTheStaffPage() throws Exception {
        MvcResult result = mvc.perform(get("/admin/staff"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("Today's Capacity"))))
            .andExpect(content().string(not(containsString("Daily Capacity Tracker"))))
            .andReturn();
        assertThat(result.getModelAndView().getModel()).doesNotContainKeys("todayCapacity", "dailyCapacity");
    }
}
