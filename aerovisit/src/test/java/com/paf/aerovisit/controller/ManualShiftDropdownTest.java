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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

/** The manual Add Shift form and Edit Shift modal list the configured time windows (like the AI preview), not three hard-coded shifts. No database. */
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

    // option values of <select id="..."> on the rendered staff page
    private List<String> optionValues(String html, String selectId) {
        Matcher select = Pattern.compile("<select id=\"" + selectId + "\"[^>]*>(.*?)</select>", Pattern.DOTALL).matcher(html);
        assertThat(select.find()).as(selectId).isTrue();
        Matcher option = Pattern.compile("<option value=\"([^\"]*)\"").matcher(select.group(1));
        List<String> values = new ArrayList<>();
        while (option.find()) values.add(option.group(1));
        return values;
    }

    private String staffPage() throws Exception {
        return mvc.perform(get("/admin/staff")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void bothDropdownsListTheConfiguredWindowsInStartOrder() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY, EVENING));
        String html = staffPage();

        List<String> expected = List.of("Morning (0800H-1200H)", MIDDAY.getLabel(), EVENING.getLabel());
        assertThat(optionValues(html, "shiftTime")).containsExactlyElementsOf(expected);
        assertThat(optionValues(html, "editShiftTime")).containsExactlyElementsOf(expected);
        assertThat(optionValues(html, "shiftTime")).doesNotContain("Afternoon (1300H-1700H)", "Full Day (0800H-1700H)");
    }

    @Test
    void withNoWindowsConfiguredTheThreeOriginalShiftsAreOffered() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of());
        List<String> legacy = List.of("Morning (0800H-1200H)", "Afternoon (1300H-1700H)", "Full Day (0800H-1700H)");
        String html = staffPage();
        assertThat(optionValues(html, "shiftTime")).containsExactlyElementsOf(legacy);
        assertThat(optionValues(html, "editShiftTime")).containsExactlyElementsOf(legacy);
    }

    @Test
    void theEditModalNoLongerForcesAnUnknownShiftOntoAHardCodedOne() throws Exception {
        String html = staffPage();
        assertThat(html).doesNotContain("select.value = \"Morning (0800H-1200H)\"")
                        .doesNotContain("select.value = \"Full Day (0800H-1700H)\"");
    }

    @Test
    void addingAShiftOutsideTheConfiguredWindowsIsRefused() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY));
        LocalDate day = LocalDate.now().plusDays(2);
        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", "Sgt. Reyes").param("role", "Gate Guard")
                .param("startDate", day.toString()).param("endDate", day.toString()).param("shiftTime", "Afternoon (1300H-1700H)"))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("error", "Shift time \"Afternoon (1300H-1700H)\" is not one of the configured time windows."));
        verify(staffRepo, never()).save(any());
    }

    @Test
    void addingAShiftInAConfiguredWindowStillWorks() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY));
        LocalDate day = LocalDate.now().plusDays(2);
        BookingSlot open = new BookingSlot();
        open.setSlotDate(day);
        when(bookingRepo.findAll()).thenReturn(List.of(open));

        mvc.perform(post("/admin/staff/add").with(csrf()).param("staffName", "Sgt. Reyes").param("role", "Gate Guard")
                .param("startDate", day.toString()).param("endDate", day.toString()).param("shiftTime", MIDDAY.getLabel()))
            .andExpect(flash().attributeExists("success"));
        verify(staffRepo).save(org.mockito.ArgumentMatchers.argThat((StaffSchedule s) -> MIDDAY.getLabel().equals(s.getShiftTime())));
    }

    @Test
    void editingKeepsAnOlderShiftValueButRefusesAMadeUpNewOne() throws Exception {
        when(timeWindowRepo.findAllByOrderByStartTimeAsc()).thenReturn(List.of(MORNING, MIDDAY));
        LocalDate day = LocalDate.now().plusDays(2);
        BookingSlot open = new BookingSlot();
        open.setSlotDate(day);
        when(bookingRepo.findAll()).thenReturn(List.of(open));
        StaffSchedule old = new StaffSchedule();
        old.setId(1L);
        old.setStaffName("Sgt. Reyes");
        old.setDutyDate(day);
        old.setShiftTime("Afternoon (1300H-1700H)"); // its window was since removed
        when(staffRepo.findById(1L)).thenReturn(Optional.of(old));

        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "1").param("staffName", "Sgt. Reyes").param("role", "Admin")
                .param("dutyDate", day.toString()).param("shiftTime", "Afternoon (1300H-1700H)"))
            .andExpect(flash().attributeExists("success"));
        assertThat(old.getRole()).isEqualTo("Admin");

        mvc.perform(post("/admin/staff/update").with(csrf()).param("id", "1").param("staffName", "Sgt. Reyes").param("role", "Admin")
                .param("dutyDate", day.toString()).param("shiftTime", "Graveyard (0000H-0600H)"))
            .andExpect(flash().attribute("error", "Shift time \"Graveyard (0000H-0600H)\" is not one of the configured time windows."));
        assertThat(old.getShiftTime()).isEqualTo("Afternoon (1300H-1700H)");
    }
}
