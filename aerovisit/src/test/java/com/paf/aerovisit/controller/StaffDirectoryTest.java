package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

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

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.MuseumEmployee;
import com.paf.aerovisit.model.MuseumPrediction;
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

/** Staff Directory: default shift dropdown built from the time windows, and the free-text primary role. No database. */
@WebMvcTest(ManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class StaffDirectoryTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final String MIDDAY = "Midday (11:00 AM - 02:00 PM)";

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

    @BeforeEach
    void timeWindows() {
        // the three seeded windows plus one the Director added
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(
            new TimeWindowConfig("Morning", LocalTime.of(8, 0), LocalTime.of(12, 0)),
            new TimeWindowConfig("Full Day", LocalTime.of(8, 0), LocalTime.of(17, 0)),
            new TimeWindowConfig("Midday", LocalTime.of(11, 0), LocalTime.of(14, 0)),
            new TimeWindowConfig("Afternoon", LocalTime.of(13, 0), LocalTime.of(17, 0))));
    }

    private static MuseumEmployee employee(String name, String role, String jobTitle, String workingDays, String defaultShift) {
        MuseumEmployee e = new MuseumEmployee();
        e.setId(7L);
        e.setName(name);
        e.setRole(role);
        e.setJobTitle(jobTitle);
        e.setWorkingDays(workingDays);
        e.setDefaultShift(defaultShift);
        return e;
    }

    private MuseumEmployee savedEmployee() {
        ArgumentCaptor<MuseumEmployee> saved = ArgumentCaptor.forClass(MuseumEmployee.class);
        verify(employeeRepo).save(saved.capture());
        return saved.getValue();
    }

    private String page() throws Exception {
        return mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    // --- Feature 1: default shift dropdown from the time windows ---

    @Test
    void bothDirectoryFormsListEveryTimeWindowByLabelAndStoreItsName() throws Exception {
        String html = page();
        String addForm = html.substring(html.indexOf("action=\"/admin/employee/add\""), html.indexOf("Official Museum Personnel"));
        String editForm = html.substring(html.indexOf("id=\"editEmployeeModal\""), html.indexOf("id=\"clearRosterModal\""));
        for (String form : List.of(addForm, editForm)) {
            assertThat(form).containsSubsequence("name=\"defaultShift\"",
                "<option value=\"\">-- No template (assign manually) --</option>",
                "<option value=\"Morning\">Morning (08:00 AM - 12:00 PM)</option>",
                "<option value=\"Full Day\">Full Day (08:00 AM - 05:00 PM)</option>",
                "<option value=\"Midday\">Midday (11:00 AM - 02:00 PM)</option>",
                "<option value=\"Afternoon\">Afternoon (01:00 PM - 05:00 PM)</option>");
            assertThat(form).doesNotContain("0800H");
        }
    }

    @Test
    void aNewTimeWindowCanBeAnOfficersDefaultShift() throws Exception {
        mvc.perform(post("/admin/employee/add").with(csrf()).param("name", "Sgt. Lim").param("role", "Gate Guard")
                .param("workingDays", "MONDAY").param("defaultShift", "Midday"))
            .andExpect(redirectedUrl("/admin/staff"));
        assertThat(savedEmployee().getDefaultShift()).isEqualTo("Midday");
    }

    @Test
    void aShiftThatIsNotATimeWindowIsNotStored() throws Exception {
        mvc.perform(post("/admin/employee/add").with(csrf()).param("name", "Sgt. Lim").param("role", "Gate Guard")
                .param("workingDays", "MONDAY").param("defaultShift", "Graveyard"))
            .andExpect(redirectedUrl("/admin/staff"));
        assertThat(savedEmployee().getDefaultShift()).isNull();
    }

    @Test
    void aiPreviewSuggestsANewWindowOfficerWithThatWindowsHours() throws Exception {
        BookingSlot monday = new BookingSlot();
        monday.setSlotDate(MONDAY);
        when(bookingRepo.findAll()).thenReturn(List.of(monday));
        MuseumPrediction prediction = new MuseumPrediction();
        prediction.setPersonnel("1 Gate Guard");
        when(aiService.fetchTurnoutPrediction(anyInt(), anyString(), anyInt())).thenReturn(prediction);
        when(employeeRepo.findAll()).thenReturn(List.of(employee("Sgt. Lim", "Gate Guard", null, "MONDAY", "Midday")));

        MvcResult result = mvc.perform(post("/admin/staff/ai-preview").with(csrf()).param("startDate", MONDAY.toString()).param("endDate", MONDAY.toString()))
            .andExpect(flash().attribute("previewMode", true)).andReturn();
        @SuppressWarnings("unchecked")
        Map<LocalDate, List<StaffSchedule>> grouped = (Map<LocalDate, List<StaffSchedule>>) result.getFlashMap().get("groupedShifts");
        StaffSchedule suggested = grouped.get(MONDAY).get(0);
        assertThat(suggested.getStaffName()).isEqualTo("Sgt. Lim");
        assertThat(suggested.getShiftTime()).isEqualTo(MIDDAY);
    }

    @Test
    void previewModalKeepsANewWindowShiftSelectedInsteadOfFallingBackToMorning() throws Exception {
        StaffSchedule s = new StaffSchedule();
        s.setStaffName("Sgt. Lim");
        s.setRole("Gate Guard");
        s.setDutyDate(MONDAY);
        s.setShiftTime(MIDDAY);
        String html = mvc.perform(get("/admin/staff").flashAttr("groupedShifts", new TreeMap<>(Map.of(MONDAY, List.of(s))))
                .flashAttr("activeStaff", List.of(employee("Sgt. Lim", "Gate Guard", null, "MONDAY", "Midday"))).flashAttr("previewMode", true))
            .andReturn().getResponse().getContentAsString();
        String modal = html.substring(html.indexOf("id=\"aiPreviewModal\""), html.indexOf("id=\"editStaffModal\""));
        // Midday is a configured window, so it is one of the real options (rendered from the time windows)
        assertThat(modal).contains("<option value=\"Midday (11:00 AM - 02:00 PM)\" selected=\"selected\">Midday (11:00 AM - 02:00 PM)</option>");
        assertThat(modal).doesNotContain("<option value=\"Morning (0800H-1200H)\" selected");
    }

    @Test
    void legacyShiftsKeepTheirOldPreviewRendering() throws Exception {
        StaffSchedule s = new StaffSchedule();
        s.setStaffName("");
        s.setRole("Gate Guard");
        s.setDutyDate(MONDAY);
        s.setShiftTime("Afternoon (1300H-1700H)");
        String html = mvc.perform(get("/admin/staff").flashAttr("groupedShifts", new TreeMap<>(Map.of(MONDAY, List.of(s))))
                .flashAttr("activeStaff", List.of()).flashAttr("previewMode", true))
            .andReturn().getResponse().getContentAsString();
        String modal = html.substring(html.indexOf("id=\"aiPreviewModal\""), html.indexOf("id=\"editStaffModal\""));
        assertThat(modal).contains("<option value=\"Afternoon (1300H-1700H)\" selected=\"selected\">");
        assertThat(modal).doesNotContain("<option value=\"Afternoon (1300H-1700H)\" selected>");
    }

    @Test
    void gateOnDutyCheckReadsWindowLabelsByTheirHoursAndLegacyShiftsAsBefore() {
        assertThat(GuardOperationController.shiftCoversNow(MIDDAY, LocalTime.of(10, 59))).isFalse();
        assertThat(GuardOperationController.shiftCoversNow(MIDDAY, LocalTime.of(11, 0))).isTrue();
        assertThat(GuardOperationController.shiftCoversNow(MIDDAY, LocalTime.of(14, 0))).isTrue();
        assertThat(GuardOperationController.shiftCoversNow(MIDDAY, LocalTime.of(14, 1))).isFalse();
        // the original roster strings keep the old Morning (before 13:00) / Afternoon (from 12:00) / Full Day rule
        assertThat(GuardOperationController.shiftCoversNow("Morning (0800H-1200H)", LocalTime.of(12, 30))).isTrue();
        assertThat(GuardOperationController.shiftCoversNow("Morning (0800H-1200H)", LocalTime.of(13, 0))).isFalse();
        assertThat(GuardOperationController.shiftCoversNow("Afternoon (1300H-1700H)", LocalTime.of(12, 0))).isTrue();
        assertThat(GuardOperationController.shiftCoversNow("Full Day (0800H-1700H)", LocalTime.of(21, 0))).isTrue();
        assertThat(GuardOperationController.shiftCoversNow(null, LocalTime.NOON)).isFalse();
    }

    // --- Feature 2: free-text primary role (Gate Guard / Tour Guide / Admin are datalist suggestions) ---

    @Test
    void addAcceptsACustomRoleAsTyped() throws Exception {
        mvc.perform(post("/admin/employee/add").with(csrf()).param("name", "Ms. Garcia").param("role", "  Museum Curator  "))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attributeExists("success"));
        MuseumEmployee saved = savedEmployee();
        assertThat(saved.getRole()).isEqualTo("Museum Curator");
        assertThat(saved.getJobTitle()).isNull();
    }

    @Test
    void theStandardRolesStillWork() throws Exception {
        mvc.perform(post("/admin/employee/add").with(csrf()).param("name", "Sgt. Reyes").param("role", "Gate Guard"))
            .andExpect(redirectedUrl("/admin/staff"));
        assertThat(savedEmployee().getRole()).isEqualTo("Gate Guard");
    }

    @Test
    void aBlankRoleIsRefusedAndNothingIsSaved() throws Exception {
        mvc.perform(post("/admin/employee/add").with(csrf()).param("name", "Sgt. Lim").param("role", "   "))
            .andExpect(flash().attribute("error", "Add failed: the officer's primary role is required."));
        org.mockito.Mockito.verify(employeeRepo, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void editChangesTheRoleAndNeverTouchesAnOldJobTitle() throws Exception {
        MuseumEmployee existing = employee("Mr. Tan", "Admin", "Archivist", null, null);
        when(employeeRepo.findById(7L)).thenReturn(Optional.of(existing));

        mvc.perform(post("/admin/employee/edit/7").with(csrf()).param("name", "Mr. Tan").param("role", "Senior Archivist"))
            .andExpect(flash().attribute("success", "✅ Officer Mr. Tan updated."));
        assertThat(existing.getRole()).isEqualTo("Senior Archivist");
        assertThat(existing.getJobTitle()).isEqualTo("Archivist"); // column left alone

        mvc.perform(post("/admin/employee/edit/7").with(csrf()).param("name", "Mr. Tan").param("role", ""))
            .andExpect(flash().attribute("error", "Update failed: the officer's primary role is required."));
        assertThat(existing.getRole()).isEqualTo("Senior Archivist");
    }

    @Test
    void bothFormsUseAFreeTextRoleWithTheThreeSuggestionsAndTheTableShowsJustTheRole() throws Exception {
        when(employeeRepo.findAll()).thenReturn(List.of(
            employee("Ms. Garcia", "Museum Curator", "Old title", null, null),
            employee("Sgt. Reyes", "Gate Guard", null, null, null)));
        String html = page();
        assertThat(html).contains("<input type=\"text\" id=\"newEmployeeRole\" name=\"role\" list=\"roleOptions\"",
            "<input type=\"text\" id=\"editEmployeeRole\" name=\"role\" list=\"roleOptions\"");
        assertThat(html).containsSubsequence("<datalist id=\"roleOptions\">", "<option value=\"Gate Guard\"></option>",
            "<option value=\"Tour Guide\"></option>", "<option value=\"Admin\"></option>", "</datalist>");
        assertThat(html.split("<datalist id=\"roleOptions\">", -1)).hasSize(2); // one shared list
        assertThat(html).doesNotContain("id=\"newEmployeeRole\" name=\"role\" class", "<select id=\"editEmployeeRole\"");
        // no job title anywhere in the directory
        assertThat(html).doesNotContain("JobTitle", "jobTitle", "data-job-title", "Old title");
        assertThat(html).contains("<th>Designated Role</th>");
        assertThat(html).containsSubsequence("Ms. Garcia", "<td><span class=\"av-badge av-badge--info\">Museum Curator</span></td>",
            "Sgt. Reyes", "<td><span class=\"av-badge av-badge--info\">Gate Guard</span></td>");
        // the duty-shift forms keep their role dropdown
        assertThat(html).contains("<select id=\"staffRole\" class=\"form-control-custom\" name=\"role\" required>",
            "<select id=\"editStaffRole\" name=\"role\" class=\"form-control-custom\" required>");
    }

    @Test
    void roleIsTrimmedAndCappedAtTheColumnSize() {
        assertThat(ManagementController.role("  Curator ")).isEqualTo("Curator");
        assertThat(ManagementController.role(" ")).isNull();
        assertThat(ManagementController.role(null)).isNull();
        assertThat(ManagementController.role("x".repeat(300))).hasSize(255);
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardsStillCannotAddOrEditStaff() throws Exception {
        mvc.perform(post("/admin/employee/add").with(csrf()).param("name", "X").param("role", "Curator"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/employee/edit/7").with(csrf()).param("name", "X").param("role", "Curator"))
            .andExpect(status().isForbidden());
    }
}
