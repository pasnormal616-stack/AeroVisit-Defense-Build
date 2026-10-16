package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.paf.aerovisit.model.MuseumPrediction;
import com.paf.aerovisit.model.StaffLeave;
import com.paf.aerovisit.model.StaffSchedule;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.StaffLeaveRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Staff shift templates: directory form, officer suggestions in the AI roster preview, and Publish Roster & Notify Staff. No database. */
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
    @MockitoBean private StaffLeaveRepository leaveRepo;

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
    void addEmployeeStoresTheShiftTemplate() throws Exception {
        mvc.perform(post("/admin/employee/add").with(csrf()).param("name", "Sgt. Reyes").param("role", "Gate Guard")
                .param("email", "  reyes@example.com ")
                .param("workingDays", "FRIDAY", "MONDAY", "wednesday", "FUNDAY")
                .param("defaultShift", "Morning"))
            .andExpect(redirectedUrl("/admin/staff"));

        ArgumentCaptor<MuseumEmployee> saved = ArgumentCaptor.forClass(MuseumEmployee.class);
        verify(employeeRepo).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("reyes@example.com");
        assertThat(saved.getValue().getWorkingDays()).isEqualTo("MONDAY,WEDNESDAY,FRIDAY");
        assertThat(saved.getValue().getDefaultShift()).isEqualTo("Morning");
        assertThat(saved.getValue().getWorkingDaysLabel()).isEqualTo("Mon, Wed, Fri");
    }

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

    @Test
    void staffPageShowsTheTemplateFieldsAndThePublishButton() throws Exception {
        when(employeeRepo.findAll()).thenReturn(List.of(
            employee("Sgt. Reyes", "Gate Guard", "reyes@example.com", "MONDAY,WEDNESDAY,FRIDAY", "Morning"),
            employee("Sgt. Legacy", "Admin", null, null, null)));

        mvc.perform(get("/admin/staff"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("name=\"email\"")))
            .andExpect(content().string(containsString("name=\"workingDays\" id=\"workDaySun\" value=\"SUNDAY\"")))
            .andExpect(content().string(containsString("name=\"defaultShift\"")))
            .andExpect(content().string(containsString("action=\"/admin/staff/publish-roster\" method=\"post\"")))
            .andExpect(content().string(containsString("Publish Roster &amp; Notify Staff")))
            .andExpect(content().string(containsString("reyes@example.com")))
            .andExpect(content().string(containsString("Mon, Wed, Fri")))
            .andExpect(content().string(containsString("No email on file")))
            .andExpect(content().string(containsString("Not set")));
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

    // --- AI Roster Generator: the preview suggests officers from their shift templates ---

    private static final String OPEN = "⚠️ OPEN SHIFT (AI)";
    private static final String AM = "Morning (0800H-1200H)";
    private static final String PM = "Afternoon (1300H-1700H)";
    private static final String FULL = "Full Day (0800H-1700H)";

    // The AI asks for the same crew every day; the museum is open on the Monday and the Tuesday
    private void aiWants(String personnel) {
        BookingSlot monday = new BookingSlot();
        monday.setSlotDate(MONDAY);
        BookingSlot tuesday = new BookingSlot();
        tuesday.setSlotDate(MONDAY.plusDays(1));
        when(bookingRepo.findAll()).thenReturn(List.of(monday, tuesday));
        MuseumPrediction prediction = new MuseumPrediction();
        prediction.setPersonnel(personnel);
        when(aiService.fetchTurnoutPrediction(anyInt(), anyString(), anyInt())).thenReturn(prediction);
    }

    // Each planned shift as "ROLE: NAME @ SHIFT", grouped by date in the order the preview lists them
    @SuppressWarnings("unchecked")
    private Map<LocalDate, List<String>> preview() throws Exception {
        MvcResult result = mvc.perform(post("/admin/staff/ai-preview").with(csrf())
                .param("startDate", MONDAY.toString()).param("endDate", MONDAY.plusDays(1).toString()))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("previewMode", true))
            .andReturn();
        Map<LocalDate, List<StaffSchedule>> grouped = (Map<LocalDate, List<StaffSchedule>>) result.getFlashMap().get("groupedShifts");
        Map<LocalDate, List<String>> summary = new TreeMap<>();
        grouped.forEach((date, shifts) -> summary.put(date, shifts.stream()
            .map(sh -> sh.getRole() + ": " + (sh.getStaffName().isEmpty() ? "open" : sh.getStaffName()) + " @ " + sh.getShiftTime()).toList()));
        return summary;
    }

    @Test
    void previewSuggestsOfficersOfTheRightRoleOnTheirWorkingDays() throws Exception {
        aiWants("2 Gate Guards, 1 Tour Guide");
        when(employeeRepo.findAll()).thenReturn(List.of(
            employee("Sgt. Dizon", "Gate Guard", null, "MONDAY", "Afternoon"),
            employee("Sgt. Reyes", "Gate Guard", null, "MONDAY,TUESDAY", "Morning"),
            employee("Lt. Santos", "Tour Guide", null, "TUESDAY", "Full Day"),
            employee("Sgt. Cruz", "Admin", null, "MONDAY,TUESDAY", "Morning"),      // the AI did not ask for an Admin
            employee("Sgt. Legacy", "Gate Guard", null, null, null)));              // no template

        Map<LocalDate, List<String>> plan = preview();

        // Monday: the morning guard takes the first slot; the afternoon guard fills the second and brings his own shift
        assertThat(plan.get(MONDAY)).containsExactly(
            "Gate Guard: Sgt. Reyes @ " + AM, "Gate Guard: Sgt. Dizon @ " + PM, "Tour Guide: open @ " + AM);
        // Tuesday: only one guard works, and the full-day tour guide is in
        assertThat(plan.get(MONDAY.plusDays(1))).containsExactly(
            "Gate Guard: Sgt. Reyes @ " + AM, "Gate Guard: open @ " + AM, "Tour Guide: Lt. Santos @ " + FULL);
    }

    @Test
    void previewNeverSuggestsAnOfficerTwiceOnOneDateOrOneAlreadyOnDuty() throws Exception {
        aiWants("3 Gate Guards");
        when(employeeRepo.findAll()).thenReturn(List.of(
            employee("Sgt. Reyes", "Gate Guard", null, "MONDAY,TUESDAY", "Morning"),
            employee("Sgt. Ramos", "Gate Guard", null, "MONDAY,TUESDAY", "Morning")));
        // Sgt. Ramos is already on the roster that Monday
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(shift("Sgt. Ramos", MONDAY, AM)));

        Map<LocalDate, List<String>> plan = preview();

        assertThat(plan.get(MONDAY)).containsExactly("Gate Guard: Sgt. Reyes @ " + AM, "Gate Guard: open @ " + AM, "Gate Guard: open @ " + AM);
        assertThat(plan.get(MONDAY.plusDays(1))).containsExactly("Gate Guard: Sgt. Reyes @ " + AM, "Gate Guard: Sgt. Ramos @ " + AM, "Gate Guard: open @ " + AM);
        verify(staffRepo, never()).save(any()); // a preview saves nothing
    }

    @Test
    void previewWithoutTemplatesLeavesEveryShiftOpen() throws Exception {
        aiWants("1 Gate Guard");
        when(employeeRepo.findAll()).thenReturn(List.of(employee("Sgt. Legacy", "Gate Guard", null, null, null)));

        assertThat(preview().get(MONDAY)).containsExactly("Gate Guard: open @ " + AM);
    }

    @Test
    void previewModalPreselectsTheSuggestedOfficerAndShift() throws Exception {
        StaffSchedule suggested = shift("Sgt. Dizon", MONDAY, PM);
        StaffSchedule open = shift("", MONDAY, AM);
        Map<LocalDate, List<StaffSchedule>> grouped = new TreeMap<>(Map.of(MONDAY, List.of(suggested, open)));
        List<MuseumEmployee> roster = List.of(
            employee("Sgt. Reyes", "Gate Guard", null, "MONDAY", "Morning"),
            employee("Sgt. Dizon", "Gate Guard", null, "MONDAY", "Afternoon"));

        String html = mvc.perform(get("/admin/staff").flashAttr("groupedShifts", grouped).flashAttr("activeStaff", roster).flashAttr("previewMode", true))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String modal = html.substring(html.indexOf("id=\"aiPreviewModal\""), html.indexOf("id=\"editStaffModal\""));
        // Only the first row has an officer selected, with the officer's own shift
        assertThat(modal.split("<option value=\"Sgt. Dizon\" selected=\"selected\">Sgt. Dizon \\(Gate Guard\\)</option>", -1)).hasSize(2);
        assertThat(modal).doesNotContain("<option value=\"Sgt. Reyes\" selected");
        assertThat(modal.split("<option value=\"Afternoon \\(1300H-1700H\\)\" selected=\"selected\">", -1)).hasSize(2);
        assertThat(modal).contains("Names are suggested from staff shift templates");
    }

    // Each row is {staffName, role, dutyDate, shiftTime}, in the order the AI preview form posts them
    private org.springframework.test.web.servlet.ResultActions deploy(Object[]... rows) throws Exception {
        var request = post("/admin/staff/ai-deploy").with(csrf());
        for (Object[] row : rows) {
            request.param("staffNames", (String) row[0]).param("roles", (String) row[1])
                   .param("dutyDates", row[2].toString()).param("shiftTimes", (String) row[3]);
        }
        return mvc.perform(request);
    }

    @Test
    void deploySavesExactlyWhatTheDirectorReviewed() throws Exception {
        // Sgt. Reyes matches both rows, but the Director left one unassigned and hand-picked someone else for the other
        when(employeeRepo.findAll()).thenReturn(List.of(employee("Sgt. Reyes", "Gate Guard", null, "MONDAY", "Morning")));

        deploy(new Object[] {OPEN, "Gate Guard", MONDAY, AM}, new Object[] {"Sgt. Dizon", "Gate Guard", MONDAY, AM})
            .andExpect(flash().attribute("success", "🤖 Successfully deployed 2 AI-planned shifts to the Master Duty Roster."));

        assertThat(savedShifts(2)).extracting(StaffSchedule::getStaffName).containsExactly(OPEN, "Sgt. Dizon");
    }

    @Test
    void deployRefusesAClashWithTheRosterOrWithinTheBatchAndSavesNothing() throws Exception {
        when(staffRepo.findByDutyDateOrderByStaffNameAsc(MONDAY)).thenReturn(List.of(shift("Sgt. Dizon", MONDAY, FULL)));
        deploy(new Object[] {"Sgt. Reyes", "Gate Guard", MONDAY, AM}, new Object[] {"sgt. dizon", "Gate Guard", MONDAY, PM})
            .andExpect(flash().attribute("error", "Row 2: ⛔ sgt. dizon is already on duty " + FULL + " on " + MONDAY
                    + ", which overlaps " + PM + ". Nothing was saved."));

        // The same officer twice in one batch
        LocalDate tuesday = MONDAY.plusDays(1);
        deploy(new Object[] {"Sgt. Reyes", "Gate Guard", tuesday, FULL}, new Object[] {"Sgt. Reyes", "Gate Guard", tuesday, AM})
            .andExpect(flash().attribute("error", "Row 2: ⛔ Sgt. Reyes is already on duty " + FULL + " on " + tuesday
                    + ", which overlaps " + AM + ". Nothing was saved."));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void openShiftsNeverClashAndDeployedNamesAreTrimmed() throws Exception {
        deploy(new Object[] {OPEN, "Gate Guard", MONDAY, AM}, new Object[] {OPEN, "Gate Guard", MONDAY, AM},
               new Object[] {"  Sgt. Reyes  ", "Gate Guard", MONDAY, PM})
            .andExpect(flash().attribute("success", "🤖 Successfully deployed 3 AI-planned shifts to the Master Duty Roster."));
        assertThat(savedShifts(3)).extracting(StaffSchedule::getStaffName).containsExactly(OPEN, OPEN, "Sgt. Reyes");
    }

    // --- Master Duty Roster calendar: open days and Clear Date Range ---

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
            .andExpect(flash().attribute("success", "🗑️ Successfully cleared 3 shifts from the roster (2026-10-05 to 2026-10-11)."
                + " 1 of them had already been emailed to staff: publish the roster to send the updated schedules, and tell anyone left with no shifts directly."))
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

    // --- Delta tracker: a shift is only emailed once ---

    @Test
    void publishOnlySendsShiftsNotYetNotifiedAndFlagsThemAfterwards() throws Exception {
        LocalDate today = LocalDate.now();
        StaffSchedule alreadySent = shift("Sgt. Reyes", today, "Morning (0800H-1200H)");
        alreadySent.setRosterNotified(true);
        StaffSchedule fresh = shift("Sgt. Reyes", today.plusDays(2), "Morning (0800H-1200H)");
        StaffSchedule santosSent = shift("Lt. Santos", today.plusDays(1), "Full Day (0800H-1700H)");
        santosSent.setRosterNotified(true);
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(alreadySent, santosSent, fresh));
        when(employeeRepo.findAll()).thenReturn(List.of(
            employee("Sgt. Reyes", "Gate Guard", "reyes@example.com", null, null),
            employee("Lt. Santos", "Tour Guide", "santos@example.com", null, null)));

        mvc.perform(post("/admin/staff/publish-roster").with(csrf()))
            .andExpect(flash().attribute("success", "📨 Roster published and 1 email sent!"))
            .andExpect(flash().attributeCount(1));

        verify(emailService).sendStaffScheduleEmail("reyes@example.com", "Sgt. Reyes", List.of(fresh));
        verify(emailService, times(1)).sendStaffScheduleEmail(anyString(), anyString(), anyList());
        assertThat(fresh.isRosterNotified()).isTrue();
        verify(staffRepo).saveAll(List.of(fresh));
    }

    @Test
    void publishingAgainWithNothingNewSendsNoEmailsAndSaysSo() throws Exception {
        StaffSchedule sent = shift("Sgt. Reyes", LocalDate.now(), "Morning (0800H-1200H)");
        sent.setRosterNotified(true);
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(sent));
        when(employeeRepo.findAll()).thenReturn(List.of(employee("Sgt. Reyes", "Gate Guard", "reyes@example.com", null, null)));

        mvc.perform(post("/admin/staff/publish-roster").with(csrf()))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("info", "All staff are already up-to-date. No new emails sent."))
            .andExpect(flash().attributeCount(1));

        verify(emailService, never()).sendStaffScheduleEmail(anyString(), anyString(), anyList());
        verify(staffRepo, never()).saveAll(any());
    }

    @Test
    void shiftsStayUnflaggedWhenTheEmailFailsOrThereIsNoAddress() throws Exception {
        LocalDate today = LocalDate.now();
        StaffSchedule ok = shift("Sgt. Reyes", today, "Morning (0800H-1200H)");
        StaffSchedule noAddress = shift("Sgt. Legacy", today, "Morning (0800H-1200H)");
        StaffSchedule smtpDown = shift("Sgt. Cruz", today, "Afternoon (1300H-1700H)");
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(ok, noAddress, smtpDown));
        when(employeeRepo.findAll()).thenReturn(List.of(
            employee("Sgt. Reyes", "Gate Guard", "reyes@example.com", null, null),
            employee("Sgt. Legacy", "Admin", null, null, null),
            employee("Sgt. Cruz", "Admin", "cruz@example.com", null, null)));
        doThrow(new EmailService.MailDeliveryException("SMTP send failed: 503 Service Unavailable")).when(emailService).sendStaffScheduleEmail(eq("cruz@example.com"), anyString(), anyList());

        mvc.perform(post("/admin/staff/publish-roster").with(csrf()))
            .andExpect(flash().attribute("success", "📨 Roster published and 1 email sent!"));

        assertThat(ok.isRosterNotified()).isTrue();
        assertThat(noAddress.isRosterNotified()).isFalse();
        assertThat(smtpDown.isRosterNotified()).isFalse();
        verify(staffRepo, times(1)).saveAll(any());
    }

    @Test
    void editingAShiftsOfficerDateOrTimeQueuesItForTheNextPublish() throws Exception {
        LocalDate date = LocalDate.now().plusDays(3);
        BookingSlot open = new BookingSlot();
        open.setSlotDate(date);
        when(bookingRepo.findAll()).thenReturn(List.of(open));

        StaffSchedule moved = shift("Sgt. Reyes", date, "Morning (0800H-1200H)");
        moved.setRosterNotified(true);
        when(staffRepo.findById(1L)).thenReturn(Optional.of(moved));
        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "1").param("staffName", "Sgt. Reyes").param("role", "Gate Guard")
            .param("dutyDate", date.toString()).param("shiftTime", "Afternoon (1300H-1700H)"));
        assertThat(moved.isRosterNotified()).isFalse();

        StaffSchedule roleOnly = shift("Sgt. Reyes", date, "Morning (0800H-1200H)");
        roleOnly.setRosterNotified(true);
        when(staffRepo.findById(2L)).thenReturn(Optional.of(roleOnly));
        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "2").param("staffName", "Sgt. Reyes").param("role", "Admin")
            .param("dutyDate", date.toString()).param("shiftTime", "Morning (0800H-1200H)"));
        assertThat(roleOnly.isRosterNotified()).isTrue();
    }

    // An emailed shift with an id, as the delete endpoints load it
    private static StaffSchedule emailedShift(long id, String staffName, LocalDate date) {
        StaffSchedule s = shift(staffName, date, AM);
        s.setId(id);
        s.setRosterNotified(true);
        return s;
    }

    @Test
    void deletingAnEmailedShiftQueuesTheOfficersRemainingUpcomingShifts() throws Exception {
        LocalDate today = LocalDate.now();
        StaffSchedule deleted = emailedShift(1, "Sgt. Reyes", today.plusDays(1));
        StaffSchedule remaining1 = emailedShift(2, "Sgt. Reyes", today);
        StaffSchedule remaining2 = emailedShift(3, "Sgt. Reyes", today.plusDays(5));
        StaffSchedule past = emailedShift(4, "Sgt. Reyes", today.minusDays(1));
        StaffSchedule someoneElse = emailedShift(5, "Lt. Santos", today.plusDays(1));
        when(staffRepo.findById(1L)).thenReturn(Optional.of(deleted));
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(past, remaining1, deleted, someoneElse, remaining2));

        mvc.perform(post("/admin/staff/ajax-delete/1").with(csrf())).andExpect(status().isOk()).andExpect(content().string("OK"));

        assertThat(remaining1.isRosterNotified()).isFalse();
        assertThat(remaining2.isRosterNotified()).isFalse();
        assertThat(past.isRosterNotified()).isTrue();
        assertThat(someoneElse.isRosterNotified()).isTrue();
        var order = org.mockito.Mockito.inOrder(staffRepo);
        order.verify(staffRepo).saveAll(List.of(remaining1, remaining2));
        order.verify(staffRepo).deleteById(1L);
    }

    @Test
    void theRedirectingDeleteEndpointQueuesTheRemainingShiftsToo() throws Exception {
        LocalDate today = LocalDate.now();
        StaffSchedule deleted = emailedShift(1, "Sgt. Reyes", today.plusDays(1));
        StaffSchedule remaining = emailedShift(2, "Sgt. Reyes", today.plusDays(2));
        when(staffRepo.findById(1L)).thenReturn(Optional.of(deleted));
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(deleted, remaining));

        mvc.perform(post("/admin/staff/delete/1").with(csrf())).andExpect(redirectedUrl("/admin/staff"));

        assertThat(remaining.isRosterNotified()).isFalse();
        verify(staffRepo).deleteById(1L);
    }

    @Test
    void deletingAShiftTheOfficerNeverHeardAboutQueuesNothing() throws Exception {
        LocalDate today = LocalDate.now();
        StaffSchedule remaining = emailedShift(9, "Sgt. Reyes", today.plusDays(3));
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(remaining));

        StaffSchedule neverEmailed = emailedShift(1, "Sgt. Reyes", today.plusDays(1));
        neverEmailed.setRosterNotified(false);
        StaffSchedule openShift = emailedShift(2, OPEN, today.plusDays(1));
        StaffSchedule alreadyWorked = emailedShift(3, "Sgt. Reyes", today.minusDays(2));
        for (StaffSchedule s : List.of(neverEmailed, openShift, alreadyWorked)) {
            when(staffRepo.findById(s.getId())).thenReturn(Optional.of(s));
            mvc.perform(post("/admin/staff/ajax-delete/" + s.getId()).with(csrf())).andExpect(content().string("OK"));
            verify(staffRepo).deleteById(s.getId());
        }
        // An id that no longer exists is still answered with OK
        when(staffRepo.findById(77L)).thenReturn(Optional.empty());
        mvc.perform(post("/admin/staff/ajax-delete/77").with(csrf())).andExpect(content().string("OK"));

        assertThat(remaining.isRosterNotified()).isTrue();
        verify(staffRepo, never()).saveAll(any());
    }

    @Test
    void bulkDeleteQueuesEachAffectedOfficerOnce() throws Exception {
        LocalDate today = LocalDate.now();
        StaffSchedule reyesA = emailedShift(1, "Sgt. Reyes", today.plusDays(1));
        StaffSchedule reyesB = emailedShift(2, "Sgt. Reyes", today.plusDays(2));
        StaffSchedule reyesLeft = emailedShift(3, "Sgt. Reyes", today.plusDays(3));
        StaffSchedule santos = emailedShift(4, "Lt. Santos", today.plusDays(1));
        StaffSchedule santosLeft = emailedShift(5, "Lt. Santos", today.plusDays(4));
        StaffSchedule cruzLeft = emailedShift(6, "Sgt. Cruz", today.plusDays(1));
        when(staffRepo.findAllById(List.of(1L, 2L, 4L))).thenReturn(List.of(reyesA, reyesB, santos));
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(reyesA, santos, cruzLeft, reyesB, reyesLeft, santosLeft));

        mvc.perform(post("/admin/staff/ajax-bulk-delete").with(csrf()).param("ids", "1,2,4")).andExpect(content().string("OK"));

        assertThat(List.of(reyesLeft, santosLeft)).allMatch(s -> !s.isRosterNotified());
        assertThat(cruzLeft.isRosterNotified()).isTrue();
        verify(staffRepo).saveAll(List.of(reyesLeft, santosLeft));
        verify(staffRepo).deleteAllById(List.of(1L, 2L, 4L));
    }

    @Test
    void clearingADateRangeQueuesTheAffectedOfficersRemainingShifts() throws Exception {
        LocalDate today = LocalDate.now();
        StaffSchedule clearedReyes = emailedShift(1, "Sgt. Reyes", today.plusDays(1));
        StaffSchedule clearedSantos = emailedShift(2, "Lt. Santos", today.plusDays(2));
        clearedSantos.setRosterNotified(false);       // never emailed: Lt. Santos's last email is still correct
        StaffSchedule reyesLeft = emailedShift(3, "Sgt. Reyes", today.plusDays(9));
        StaffSchedule santosLeft = emailedShift(4, "Lt. Santos", today.plusDays(9));
        List<StaffSchedule> inRange = List.of(clearedReyes, clearedSantos);
        when(staffRepo.findByArchivedFalseAndDutyDateBetween(today, today.plusDays(6))).thenReturn(inRange);
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(clearedReyes, clearedSantos, reyesLeft, santosLeft));

        mvc.perform(post("/admin/staff/clear-roster").with(csrf()).param("startDate", today.toString()).param("endDate", today.plusDays(6).toString()))
            .andExpect(flash().attribute("success", containsString("Successfully cleared 2 shifts from the roster")))
            .andExpect(flash().attribute("success", containsString("1 of them had already been emailed to staff")));

        assertThat(reyesLeft.isRosterNotified()).isFalse();
        assertThat(santosLeft.isRosterNotified()).isTrue();
        var order = org.mockito.Mockito.inOrder(staffRepo);
        order.verify(staffRepo).saveAll(List.of(reyesLeft));
        order.verify(staffRepo).deleteAllInBatch(inRange);
    }

    @Test
    void afterADeleteTheNextPublishSendsTheOfficerTheirRemainingRoster() throws Exception {
        LocalDate today = LocalDate.now();
        StaffSchedule deleted = emailedShift(1, "Sgt. Reyes", today.plusDays(1));
        StaffSchedule remaining1 = emailedShift(2, "Sgt. Reyes", today);
        StaffSchedule remaining2 = emailedShift(3, "Sgt. Reyes", today.plusDays(5));
        when(staffRepo.findById(1L)).thenReturn(Optional.of(deleted));
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(remaining1, deleted, remaining2));
        when(employeeRepo.findAll()).thenReturn(List.of(employee("Sgt. Reyes", "Gate Guard", "reyes@example.com", null, null)));

        mvc.perform(post("/admin/staff/ajax-delete/1").with(csrf()));
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(remaining1, remaining2)); // the shift is gone
        mvc.perform(post("/admin/staff/publish-roster").with(csrf()))
            .andExpect(flash().attribute("success", "📨 Roster published and 1 email sent!"));

        verify(emailService).sendStaffScheduleEmail("reyes@example.com", "Sgt. Reyes", List.of(remaining1, remaining2));
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
    void editEmployeeUpdatesEveryFieldAndCanClearTheTemplate() throws Exception {
        MuseumEmployee emp = employee("Sgt. Reyes", "Gate Guard", "old@example.com", "MONDAY,FRIDAY", "Morning");
        when(employeeRepo.findById(7L)).thenReturn(Optional.of(emp));

        mvc.perform(post("/admin/employee/edit/7").with(csrf()).param("name", "Sgt. Reyes").param("role", "Tour Guide")
                .param("email", "new@example.com").param("workingDays", "TUESDAY", "THURSDAY").param("defaultShift", "Full Day"))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", "✅ Officer Sgt. Reyes updated."));

        verify(employeeRepo).save(emp);
        assertThat(emp.getRole()).isEqualTo("Tour Guide");
        assertThat(emp.getEmail()).isEqualTo("new@example.com");
        assertThat(emp.getWorkingDays()).isEqualTo("TUESDAY,THURSDAY");
        assertThat(emp.getDefaultShift()).isEqualTo("Full Day");
        verify(staffRepo, never()).saveAll(any()); // name unchanged: shifts are not touched

        // No days ticked, no shift, blank email: the template is cleared
        mvc.perform(post("/admin/employee/edit/7").with(csrf()).param("name", "Sgt. Reyes").param("role", "Tour Guide")
            .param("email", "").param("defaultShift", ""));
        assertThat(emp.getEmail()).isNull();
        assertThat(emp.getWorkingDays()).isNull();
        assertThat(emp.getDefaultShift()).isNull();
    }

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
            .andExpect(content().string(containsString("data-days=\"MONDAY,FRIDAY\"")))
            .andExpect(content().string(containsString("data-shift=\"Morning\"")))
            .andExpect(content().string(containsString("id=\"editEmployeeForm\"")))
            .andExpect(content().string(containsString("function openEmployeeModal(btn)")));
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCannotPublishTheRoster() throws Exception {
        mvc.perform(post("/admin/staff/publish-roster").with(csrf())).andExpect(status().isForbidden());
        verify(emailService, never()).sendStaffScheduleEmail(anyString(), anyString(), anyList());
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardDoesNotGetThePublishEndpointFromTheStaffPage() throws Exception {
        mvc.perform(get("/admin/staff")).andExpect(status().isForbidden())
            .andExpect(content().string(not(containsString("publish-roster"))));
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
        mvc.perform(post("/admin/staff/ai-preview").with(csrf())
                .param("startDate", MONDAY.toString()).param("endDate", MONDAY.toString()))
            .andExpect(status().isForbidden());
        deploy(new Object[] {"Sgt. Reyes", "Gate Guard", MONDAY, AM}).andExpect(status().isForbidden());

        verify(staffRepo, never()).save(any());
        verify(aiService, never()).fetchTurnoutPrediction(anyInt(), anyString(), anyInt());
    }

    @Test
    void aShiftCannotBeAssignedOnADateTheOfficerIsOnApprovedLeave() throws Exception {
        LocalDate day = LocalDate.now().plusDays(3);
        BookingSlot open = new BookingSlot();
        open.setSlotDate(day);
        when(bookingRepo.findAll()).thenReturn(List.of(open));

        mvc.perform(post("/admin/staff/leave").with(csrf())
                .param("staffName", "Sgt. Reyes").param("startDate", day.toString()).param("endDate", day.toString()))
            .andExpect(redirectedUrl("/admin/staff"));
        ArgumentCaptor<StaffLeave> saved = ArgumentCaptor.forClass(StaffLeave.class);
        verify(leaveRepo).save(saved.capture());
        assertThat(saved.getValue().getStaffName()).isEqualTo("Sgt. Reyes");
        assertThat(saved.getValue().getStartDate()).isEqualTo(day);
        assertThat(saved.getValue().getEndDate()).isEqualTo(day);

        // The mocked repository now returns the recorded leave
        when(leaveRepo.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any())).thenReturn(List.of(saved.getValue()));
        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", "Sgt. Reyes").param("role", "Gate Guard")
                .param("startDate", day.toString()).param("endDate", day.toString()).param("shiftTime", AM))
            .andExpect(flash().attribute("error", "⛔ Sgt. Reyes is on approved leave from " + day + " to " + day
                    + ", so they can't be scheduled on " + day + ". Nothing was saved."));
        verify(staffRepo, never()).save(any());
    }

    // --- Vacation Rule: leave ---

    @Test
    void leaveRefusesUpdateAndTheWholeDeploy() throws Exception {
        LocalDate day = LocalDate.now().plusDays(4);
        when(leaveRepo.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any()))
            .thenReturn(List.of(new StaffLeave("Sgt. Reyes", day, day.plusDays(2))));
        BookingSlot open = new BookingSlot();
        open.setSlotDate(day);
        when(bookingRepo.findAll()).thenReturn(List.of(open));

        StaffSchedule dizons = shift("Sgt. Dizon", day, AM);
        dizons.setId(1L);
        when(staffRepo.findById(1L)).thenReturn(Optional.of(dizons));
        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "1").param("staffName", "Sgt. Reyes").param("role", "Gate Guard")
                .param("dutyDate", day.toString()).param("shiftTime", AM))
            .andExpect(flash().attribute("error", "⛔ Sgt. Reyes is on approved leave from " + day + " to " + day.plusDays(2)
                    + ", so they can't be scheduled on " + day + ". Nothing was saved."));
        assertThat(dizons.getStaffName()).isEqualTo("Sgt. Dizon");

        deploy(new Object[] {"Sgt. Dizon", "Gate Guard", day, AM}, new Object[] {"sgt. reyes", "Gate Guard", day.plusDays(1), PM})
            .andExpect(flash().attribute("error", "Row 2: ⛔ sgt. reyes is on approved leave from " + day + " to " + day.plusDays(2)
                    + ", so they can't be scheduled on " + day.plusDays(1) + ". Nothing was saved."));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void previewDoesNotSuggestAnOfficerOnLeaveThatDay() throws Exception {
        aiWants("1 Gate Guard");
        when(employeeRepo.findAll()).thenReturn(List.of(employee("Sgt. Reyes", "Gate Guard", null, "MONDAY,TUESDAY", "Morning")));
        when(leaveRepo.findByStartDateLessThanEqualAndEndDateGreaterThanEqual(any(), any()))
            .thenReturn(List.of(new StaffLeave("SGT. REYES", MONDAY, MONDAY)));      // Monday only

        Map<LocalDate, List<String>> plan = preview();

        assertThat(plan.get(MONDAY)).containsExactly("Gate Guard: open @ " + AM);
        assertThat(plan.get(MONDAY.plusDays(1))).containsExactly("Gate Guard: Sgt. Reyes @ " + AM);
    }

    @Test
    void recordingLeaveOpensTheOfficersShiftsInThatPeriod() throws Exception {
        LocalDate start = LocalDate.now().plusDays(2);
        LocalDate end = start.plusDays(2);
        StaffSchedule inside = shift("Sgt. Reyes", start.plusDays(1), AM);
        inside.setRosterNotified(true);
        StaffSchedule colleague = shift("Sgt. Dizon", start.plusDays(1), AM);
        when(staffRepo.findByArchivedFalseAndDutyDateBetween(start, end)).thenReturn(List.of(inside, colleague));

        mvc.perform(post("/admin/staff/leave").with(csrf())
                .param("staffName", " Sgt. Reyes ").param("startDate", start.toString()).param("endDate", end.toString()))
            .andExpect(flash().attribute("success", "🏖️ Leave recorded for Sgt. Reyes from " + start + " to " + end
                    + ". 1 shift(s) in that period are now open shifts."));

        assertThat(inside.getStaffName()).isEqualTo(OPEN);
        assertThat(inside.isRosterNotified()).isFalse();
        assertThat(colleague.getStaffName()).isEqualTo("Sgt. Dizon");
    }

    @Test
    void invalidLeaveIsRefusedAndNothingIsSaved() throws Exception {
        String day = LocalDate.now().plusDays(2).toString();
        mvc.perform(post("/admin/staff/leave").with(csrf()).param("staffName", "  ").param("startDate", day).param("endDate", day))
            .andExpect(flash().attribute("error", "Choose the officer going on leave."));
        mvc.perform(post("/admin/staff/leave").with(csrf()).param("staffName", "Sgt. Reyes").param("startDate", "2026-13-40").param("endDate", day))
            .andExpect(flash().attribute("error", "Please enter valid leave dates."));
        mvc.perform(post("/admin/staff/leave").with(csrf()).param("staffName", "Sgt. Reyes")
                .param("startDate", day).param("endDate", LocalDate.now().toString()))
            .andExpect(flash().attribute("error", "Time Paradox: the leave cannot end before it starts."));
        verify(leaveRepo, never()).save(any());
        verify(staffRepo, never()).saveAll(anyList());
    }

    @Test
    void staffPageListsUpcomingLeaveAndALeaveCanBeCancelled() throws Exception {
        StaffLeave leave = new StaffLeave("Sgt. Reyes", LocalDate.now().plusDays(1), LocalDate.now().plusDays(3));
        leave.setId(4L);
        when(leaveRepo.findByEndDateGreaterThanEqualOrderByStartDateAsc(any())).thenReturn(List.of(leave));

        String html = mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String panel = html.substring(html.indexOf("id=\"staffLeaveTable\""));
        assertThat(panel).contains("Sgt. Reyes").contains(leave.getStartDate().toString())
            .contains("action=\"/admin/staff/leave/delete/4\"").doesNotContain("No upcoming leave recorded.");

        mvc.perform(post("/admin/staff/leave/delete/4").with(csrf()))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", "Leave cancelled. Shifts opened for it stay open; assign them again if needed."));
        verify(leaveRepo).deleteById(4L);
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCannotRecordOrCancelLeave() throws Exception {
        String day = LocalDate.now().plusDays(2).toString();
        mvc.perform(post("/admin/staff/leave").with(csrf()).param("staffName", "Sgt. Reyes").param("startDate", day).param("endDate", day))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/staff/leave/delete/4").with(csrf())).andExpect(status().isForbidden());
        verify(leaveRepo, never()).save(any());
        verify(leaveRepo, never()).deleteById(any());
    }

    @Test
    void aDeactivatedGuardIsNoLongerSuggestedForFutureShifts() throws Exception {
        aiWants("2 Gate Guards");
        MuseumEmployee reyes = employee("Sgt. Reyes", "Gate Guard", null, "MONDAY,TUESDAY", "Morning");
        reyes.setId(7L);
        MuseumEmployee ramos = employee("Sgt. Ramos", "Gate Guard", null, "MONDAY,TUESDAY", "Morning");
        when(employeeRepo.findById(7L)).thenReturn(Optional.of(reyes));
        when(employeeRepo.findAll()).thenReturn(List.of(reyes, ramos));

        mvc.perform(post("/admin/employee/deactivate/7").with(csrf()))          // proposed endpoint
            .andExpect(redirectedUrl("/admin/staff"));

        Map<LocalDate, List<String>> plan = preview();
        assertThat(plan.get(MONDAY)).containsExactly("Gate Guard: Sgt. Ramos @ " + AM, "Gate Guard: open @ " + AM);
        assertThat(plan.values()).allSatisfy(day -> assertThat(day).noneMatch(s -> s.contains("Sgt. Reyes")));
    }

    // --- Ghost Rule: deactivation ---

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCannotDeactivateOrReactivateAnOfficer() throws Exception {
        mvc.perform(post("/admin/employee/deactivate/7").with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/employee/reactivate/7").with(csrf())).andExpect(status().isForbidden());
        verify(employeeRepo, never()).save(any());
        verify(staffRepo, never()).saveAll(anyList());
    }

    @Test
    void deactivationOpensTheOfficersUpcomingShiftsOnly() throws Exception {
        MuseumEmployee reyes = employee("Sgt. Reyes", "Gate Guard", null, "MONDAY", "Morning");
        reyes.setId(7L);
        when(employeeRepo.findById(7L)).thenReturn(Optional.of(reyes));
        LocalDate today = LocalDate.now();
        StaffSchedule todays = shift("Sgt. Reyes", today, AM);
        todays.setRosterNotified(true);
        StaffSchedule later = shift(" sgt. reyes ", today.plusDays(5), PM);
        StaffSchedule yesterday = shift("Sgt. Reyes", today.minusDays(1), AM);   // past, not archived yet: history
        StaffSchedule other = shift("Sgt. Dizon", today.plusDays(5), AM);
        when(staffRepo.findByArchivedFalseOrderByDutyDateAscStaffNameAsc()).thenReturn(List.of(yesterday, todays, later, other));

        mvc.perform(post("/admin/employee/deactivate/7").with(csrf()))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success",
                "👻 Sgt. Reyes was deactivated and will not be scheduled again. 2 upcoming shift(s) are now open shifts."));

        assertThat(reyes.isActive()).isFalse();
        verify(employeeRepo).save(reyes);
        assertThat(todays.getStaffName()).isEqualTo(OPEN);
        assertThat(todays.isRosterNotified()).isFalse();
        assertThat(later.getStaffName()).isEqualTo(OPEN);
        assertThat(yesterday.getStaffName()).isEqualTo("Sgt. Reyes");
        assertThat(other.getStaffName()).isEqualTo("Sgt. Dizon");
    }

    @Test
    void aReactivatedOfficerIsSuggestedAgain() throws Exception {
        aiWants("1 Gate Guard");
        MuseumEmployee reyes = employee("Sgt. Reyes", "Gate Guard", null, "MONDAY,TUESDAY", "Morning");
        reyes.setId(7L);
        reyes.setActive(false);
        when(employeeRepo.findById(7L)).thenReturn(Optional.of(reyes));
        when(employeeRepo.findAll()).thenReturn(List.of(reyes));
        assertThat(preview().get(MONDAY)).containsExactly("Gate Guard: open @ " + AM);

        mvc.perform(post("/admin/employee/reactivate/7").with(csrf()))
            .andExpect(flash().attribute("success",
                "✅ Sgt. Reyes is active again. Shifts opened at deactivation stay open; assign them again if needed."));

        assertThat(reyes.isActive()).isTrue();
        assertThat(preview().get(MONDAY)).containsExactly("Gate Guard: Sgt. Reyes @ " + AM);
    }

    @Test
    void aDeactivatedOfficerCannotBeScheduledByAddUpdateOrDeploy() throws Exception {
        MuseumEmployee reyes = employee("Sgt. Reyes", "Gate Guard", null, null, null);
        reyes.setActive(false);
        when(employeeRepo.findAll()).thenReturn(List.of(reyes));
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        BookingSlot monday = new BookingSlot();
        monday.setSlotDate(MONDAY);
        BookingSlot next = new BookingSlot();
        next.setSlotDate(tomorrow);
        when(bookingRepo.findAll()).thenReturn(List.of(monday, next));
        String refused = "⛔ Sgt. Reyes is deactivated and can't be scheduled. Reactivate them in the Staff Directory first. Nothing was saved.";

        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", "sgt. reyes").param("role", "Gate Guard")
                .param("startDate", MONDAY.toString()).param("endDate", MONDAY.toString()).param("shiftTime", AM))
            .andExpect(flash().attribute("error", refused));

        StaffSchedule dizons = shift("Sgt. Dizon", tomorrow, AM);
        dizons.setId(1L);
        when(staffRepo.findById(1L)).thenReturn(Optional.of(dizons));
        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "1").param("staffName", "Sgt. Reyes").param("role", "Gate Guard")
                .param("dutyDate", tomorrow.toString()).param("shiftTime", AM))
            .andExpect(flash().attribute("error", refused));
        assertThat(dizons.getStaffName()).isEqualTo("Sgt. Dizon");

        deploy(new Object[] {OPEN, "Gate Guard", MONDAY, AM}, new Object[] {"Sgt. Reyes", "Gate Guard", MONDAY, PM})
            .andExpect(flash().attribute("error", refused));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void staffPageMarksInactiveOfficersAndLeavesThemOutOfTheShiftDropdowns() throws Exception {
        MuseumEmployee reyes = employee("Sgt. Reyes", "Gate Guard", null, null, null);
        reyes.setId(7L);
        reyes.setActive(false);
        MuseumEmployee ramos = employee("Sgt. Ramos", "Gate Guard", null, null, null);
        ramos.setId(8L);
        when(employeeRepo.findAll()).thenReturn(List.of(reyes, ramos));

        String html = mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html).contains(">Inactive</span>")
            .contains("action=\"/admin/employee/reactivate/7\"").doesNotContain("action=\"/admin/employee/deactivate/7\"")
            .contains("action=\"/admin/employee/deactivate/8\"");
        assertThat(html).doesNotContain("<option value=\"Sgt. Reyes\">").contains("<option value=\"Sgt. Ramos\">");
    }

    @Test
    void deactivatingAnUnknownOfficerChangesNothing() throws Exception {
        mvc.perform(post("/admin/employee/deactivate/99").with(csrf()))
            .andExpect(flash().attribute("error", "That officer is no longer on the roster."));
        verify(employeeRepo, never()).save(any());
        verify(staffRepo, never()).saveAll(anyList());
    }
}
