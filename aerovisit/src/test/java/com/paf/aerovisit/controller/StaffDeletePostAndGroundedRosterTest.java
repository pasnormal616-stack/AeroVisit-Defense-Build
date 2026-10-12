package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
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

/** Staff deletes are POST-only, and the AI roster only ever uses the configured time windows. No database. */
@WebMvcTest(ManagementController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class StaffDeletePostAndGroundedRosterTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);
    private static final TimeWindowConfig MORNING = new TimeWindowConfig("Morning", LocalTime.of(8, 0), LocalTime.of(12, 0));
    private static final TimeWindowConfig MIDDAY = new TimeWindowConfig("Midday", LocalTime.of(11, 0), LocalTime.of(14, 0));
    private static final TimeWindowConfig AFTERNOON = new TimeWindowConfig("Afternoon", LocalTime.of(13, 0), LocalTime.of(17, 0));
    private static final TimeWindowConfig FULL_DAY = new TimeWindowConfig("Full Day", LocalTime.of(8, 0), LocalTime.of(17, 0));
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

    // --- Task 3: POST-only staff deletes ---

    @Test
    void staffDeletesNoLongerRunOnAGet() throws Exception {
        for (String path : new String[] {"/admin/staff/delete/1", "/admin/staff/ajax-delete/1", "/admin/employee/delete/1"}) {
            mvc.perform(get(path)).andExpect(status().isMethodNotAllowed());
        }
        mvc.perform(get("/admin/staff/ajax-bulk-delete").param("ids", "1,2")).andExpect(status().isMethodNotAllowed());
        verifyNoInteractions(staffRepo, employeeRepo);
    }

    @Test
    void thePostVersionsDeleteAsBefore() throws Exception {
        mvc.perform(post("/admin/staff/delete/1").with(csrf())).andExpect(redirectedUrl("/admin/staff"));
        verify(staffRepo).deleteById(1L);
        mvc.perform(post("/admin/staff/ajax-delete/2").with(csrf())).andExpect(content().string("OK"));
        verify(staffRepo).deleteById(2L);
        // the page's fetch() sends the ids as a form body
        mvc.perform(post("/admin/staff/ajax-bulk-delete").with(csrf()).contentType("application/x-www-form-urlencoded").content("ids=3%2C4"))
            .andExpect(content().string("OK"));
        verify(staffRepo).deleteAllById(List.of(3L, 4L));
        mvc.perform(post("/admin/employee/delete/5").with(csrf())).andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", "Officer removed from the official roster."));
        verify(employeeRepo).deleteById(5L);
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void staffDeletesStayDirectorOnly() throws Exception {
        for (String path : new String[] {"/admin/staff/delete/1", "/admin/staff/ajax-delete/1", "/admin/staff/ajax-bulk-delete", "/admin/employee/delete/1"}) {
            mvc.perform(post(path).with(csrf()).param("ids", "1")).andExpect(status().isForbidden());
        }
        verify(staffRepo, never()).deleteById(anyLong());
        verify(employeeRepo, never()).deleteById(anyLong());
    }

    @Test
    void theStaffPageUsesPostForEveryDelete() throws Exception {
        MuseumEmployee emp = new MuseumEmployee();
        emp.setId(7L);
        emp.setName("Sgt. Reyes");
        emp.setRole("Gate Guard");
        when(employeeRepo.findAll()).thenReturn(List.of(emp));

        String html = mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(html)
            .containsPattern("<form action=\"/admin/employee/delete/7\" method=\"post\" class=\"d-inline m-0\">" + PostOnlyActionsTest.CSRF_FIELD + "\\s*"
                    + "<button type=\"submit\" class=\"btn btn-sm btn-light text-danger p-1\" title=\"Remove\" aria-label=\"Remove employee\">")
            .doesNotContain("href=\"/admin/employee/delete/")
            .contains("fetch('/admin/staff/ajax-delete/' + id, { method: 'POST', headers: AeroCsrf.headers() })")
            .contains("fetch('/admin/staff/ajax-bulk-delete', {\n            method: 'POST',\n            headers: AeroCsrf.headers(")
            .doesNotContain("ajax-bulk-delete?ids=");
    }

    // --- Task 4: the AI roster is grounded in the configured time windows ---

    @SuppressWarnings("unchecked")
    private List<StaffSchedule> previewWith(TimeWindowConfig... windows) throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(windows));
        BookingSlot slot = new BookingSlot();
        slot.setSlotDate(MONDAY);
        when(bookingRepo.findAll()).thenReturn(List.of(slot));
        MuseumPrediction prediction = new MuseumPrediction();
        prediction.setPersonnel("Deploy minimum 1 Tour Guides and 2 Gate Guards.");
        when(aiService.fetchTurnoutPrediction(anyInt(), anyString(), anyInt())).thenReturn(prediction);

        MvcResult result = mvc.perform(post("/admin/staff/ai-preview").with(csrf()).param("startDate", MONDAY.toString()).param("endDate", MONDAY.toString()))
            .andExpect(flash().attribute("previewMode", true)).andReturn();
        return ((Map<LocalDate, List<StaffSchedule>>) result.getFlashMap().get("groupedShifts")).get(MONDAY);
    }

    @Test
    void aiShiftsUseTheLongestConfiguredWindowInsteadOfAHardCodedMorning() throws Exception {
        List<StaffSchedule> shifts = previewWith(MORNING, MIDDAY, AFTERNOON, FULL_DAY);
        assertThat(shifts).hasSize(3).allSatisfy(s -> assertThat(s.getShiftTime()).isEqualTo("Full Day (0800H-1700H)"));
    }

    @Test
    void withoutAFullDayWindowTheLongestRealOneIsUsed() throws Exception {
        assertThat(previewWith(MIDDAY, AFTERNOON)).allSatisfy(s -> assertThat(s.getShiftTime()).isEqualTo("Afternoon (1300H-1700H)"));
        assertThat(previewWith(MIDDAY, EVENING)).allSatisfy(s -> assertThat(s.getShiftTime()).isEqualTo("Evening (04:00 PM - 08:00 PM)"));
    }

    @Test
    void aTemplatePointingAtADeletedWindowSuggestsNobody() throws Exception {
        MuseumEmployee reyes = new MuseumEmployee();
        reyes.setName("Sgt. Reyes");
        reyes.setRole("Gate Guard");
        reyes.setWorkingDays("MONDAY");
        reyes.setDefaultShift("Morning"); // the Morning window no longer exists
        when(employeeRepo.findAll()).thenReturn(List.of(reyes));

        List<StaffSchedule> shifts = previewWith(MIDDAY, EVENING);
        assertThat(shifts).allSatisfy(s -> assertThat(s.getStaffName()).isEmpty());
        assertThat(shifts).noneSatisfy(s -> assertThat(s.getShiftTime()).isEqualTo("Morning (0800H-1200H)"));
    }

    @Test
    void thePreviewDropdownOffersOnlyTheConfiguredWindows() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MIDDAY, EVENING));
        StaffSchedule s = new StaffSchedule();
        s.setStaffName("");
        s.setRole("Gate Guard");
        s.setDutyDate(MONDAY);
        s.setShiftTime("Evening (04:00 PM - 08:00 PM)");

        String html = mvc.perform(get("/admin/staff").flashAttr("groupedShifts", new TreeMap<>(Map.of(MONDAY, List.of(s))))
                .flashAttr("activeStaff", List.of()).flashAttr("previewMode", true))
            .andReturn().getResponse().getContentAsString();
        String modal = html.substring(html.indexOf("id=\"aiPreviewModal\""), html.indexOf("id=\"editStaffModal\""));

        assertThat(modal)
            .contains("<option value=\"Midday (11:00 AM - 02:00 PM)\">Midday (11:00 AM - 02:00 PM)</option>")
            .contains("<option value=\"Evening (04:00 PM - 08:00 PM)\" selected=\"selected\">Evening (04:00 PM - 08:00 PM)</option>")
            .doesNotContain("0800H-1200H").doesNotContain("1300H-1700H").doesNotContain("0800H-1700H");
    }

    @Test
    void deployRefusesAShiftTimeThatIsNotAConfiguredWindow() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MIDDAY, EVENING));

        mvc.perform(post("/admin/staff/ai-deploy").with(csrf())
                .param("staffNames", "Sgt. Reyes", "Sgt. Cruz")
                .param("roles", "Gate Guard", "Gate Guard")
                .param("dutyDates", MONDAY.toString(), MONDAY.toString())
                .param("shiftTimes", "Midday (11:00 AM - 02:00 PM)", "Graveyard (0000H-0800H)"))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("error", "Shift time \"Graveyard (0000H-0800H)\" is not one of the configured time windows. "
                    + "Please pick a window from the list and deploy again."));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void deployRefusesMisalignedColumnsInsteadOfCrashing() throws Exception {
        mvc.perform(post("/admin/staff/ai-deploy").with(csrf())
                .param("staffNames", "Sgt. Reyes", "Sgt. Cruz")
                .param("roles", "Gate Guard")
                .param("dutyDates", MONDAY.toString(), MONDAY.toString())
                .param("shiftTimes", "Morning (0800H-1200H)", "Morning (0800H-1200H)"))
            .andExpect(flash().attribute("error", "The roster form was incomplete. Please generate the AI preview again."));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void deploySavesShiftsInConfiguredWindows() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MIDDAY, EVENING));

        mvc.perform(post("/admin/staff/ai-deploy").with(csrf())
                .param("staffNames", "Sgt. Reyes", "")
                .param("roles", "Gate Guard", "Tour Guide")
                .param("dutyDates", MONDAY.toString(), MONDAY.toString())
                .param("shiftTimes", "Midday (11:00 AM - 02:00 PM)", "Evening (04:00 PM - 08:00 PM)"))
            .andExpect(flash().attribute("success", "🤖 Successfully deployed 2 AI-planned shifts to the Master Duty Roster."));
        verify(staffRepo, org.mockito.Mockito.times(2)).save(any(StaffSchedule.class));
    }
}
