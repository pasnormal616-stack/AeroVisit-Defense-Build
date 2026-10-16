package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalTime;
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
import com.paf.aerovisit.model.TimeWindowConfig;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;

/** Director-managed operating time windows. No database: the repository is mocked. */
@WebMvcTest(TimeWindowController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class TimeWindowControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private TimeWindowConfigRepository windowRepo;

    private static TimeWindowConfig window(Long id, String name, int startHour, int endHour) {
        TimeWindowConfig w = new TimeWindowConfig(name, LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
        w.setId(id);
        return w;
    }

    @Test
    void labelKeepsTheFormatExistingSlotsUse() {
        assertThat(window(1L, "Afternoon", 13, 17).getLabel()).isEqualTo("Afternoon (01:00 PM - 05:00 PM)");
    }

    @Test
    void directorCanAddAWindow() throws Exception {
        mvc.perform(post("/admin/slots/windows/add").with(csrf()).param("name", " Twilight Tour ").param("startTime", "17:30").param("endTime", "19:00"))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", "Time window added: Twilight Tour (05:30 PM - 07:00 PM)"));
        ArgumentCaptor<TimeWindowConfig> saved = ArgumentCaptor.forClass(TimeWindowConfig.class);
        verify(windowRepo).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Twilight Tour");
    }

    @Test
    void endTimeMustBeAfterStartTime() throws Exception {
        mvc.perform(post("/admin/slots/windows/add").with(csrf()).param("name", "Backwards").param("startTime", "12:00").param("endTime", "08:00"))
            .andExpect(flash().attribute("error", "The end time must be later than the start time."));
        verify(windowRepo, never()).save(any());
    }

    // Deliberate constraint: shifts use time windows, and a window never crosses midnight (the gate check, the 11:59 PM
    // no-show sweep and auto check-out all assume a same-day window)
    @Test
    void anOvernightWindowIsRefusedSoNoShiftCanCrossMidnight() throws Exception {
        mvc.perform(post("/admin/slots/windows/add").with(csrf()).param("name", "Night Watch").param("startTime", "22:00").param("endTime", "06:00"))
            .andExpect(flash().attribute("error", "The end time must be later than the start time."));
        verify(windowRepo, never()).save(any());
    }

    @Test
    void duplicateWindowIsRefused() throws Exception {
        when(windowRepo.findAll()).thenReturn(List.of(window(1L, "Morning", 8, 12)));
        mvc.perform(post("/admin/slots/windows/add").with(csrf()).param("name", "morning").param("startTime", "08:00").param("endTime", "12:00"))
            .andExpect(flash().attribute("error", "A time window with that name and schedule already exists."));
        verify(windowRepo, never()).save(any());
    }

    @Test
    void directorCanEditAWindow() throws Exception {
        TimeWindowConfig morning = window(1L, "Morning", 8, 12);
        when(windowRepo.findById(1L)).thenReturn(Optional.of(morning));
        when(windowRepo.findAll()).thenReturn(List.of(morning));
        mvc.perform(post("/admin/slots/windows/1/update").with(csrf()).param("name", "Morning").param("startTime", "09:00").param("endTime", "12:00"))
            .andExpect(redirectedUrl("/admin/staff"));
        assertThat(morning.getLabel()).isEqualTo("Morning (09:00 AM - 12:00 PM)");
        verify(windowRepo).save(morning);
    }

    @Test
    void directorCanDeleteAWindow() throws Exception {
        when(windowRepo.existsById(1L)).thenReturn(true);
        mvc.perform(post("/admin/slots/windows/1/delete").with(csrf()))
            .andExpect(redirectedUrl("/admin/staff"));
        verify(windowRepo).deleteById(1L);
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardsCannotChangeTimeWindows() throws Exception {
        mvc.perform(post("/admin/slots/windows/add").with(csrf()).param("name", "Night").param("startTime", "20:00").param("endTime", "22:00"))
            .andExpect(status().isForbidden());
        verify(windowRepo, never()).save(any());
    }
}
