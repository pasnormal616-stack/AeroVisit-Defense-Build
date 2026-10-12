package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.MuseumZone;
import com.paf.aerovisit.repository.MuseumZoneRepository;

/** Museum zone REST API (Root Cause Matrix) and its security rules. No database. */
@WebMvcTest(MuseumZoneController.class)
@Import(SecurityConfig.class)
class MuseumZoneControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private MuseumZoneRepository zoneRepo;

    private static MuseumZone zone(long id, String name, boolean active) {
        MuseumZone z = new MuseumZone(name);
        z.setId(id);
        z.setActive(active);
        return z;
    }

    @Test
    void anonymousVisitorGetsOnlyActiveZones() throws Exception {
        when(zoneRepo.findByIsActiveTrueOrderByIdAsc()).thenReturn(List.of(zone(1, "General Experience", true), zone(3, "Aircraft Park", true)));
        mvc.perform(get("/api/zones"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].name").value("Aircraft Park"))
            .andExpect(jsonPath("$[1].isActive").value(true));
    }

    @Test
    void anonymousCannotListAllAddOrDisableZones() throws Exception {
        mvc.perform(get("/api/zones/all")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/api/zones").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hack\"}")).andExpect(status().is3xxRedirection());
        mvc.perform(patch("/api/zones/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().is3xxRedirection());
        verify(zoneRepo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCannotManageZones() throws Exception {
        mvc.perform(get("/api/zones/all")).andExpect(status().isForbidden());
        mvc.perform(post("/api/zones").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hack\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/zones/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isForbidden());
        verify(zoneRepo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void directorListsEveryZoneIncludingDisabled() throws Exception {
        when(zoneRepo.findAll()).thenReturn(List.of(zone(1, "General Experience", true), zone(2, "Old Hangar", false)));
        mvc.perform(get("/api/zones/all"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[1].isActive").value(false));
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void directorAddsATrimmedZone() throws Exception {
        when(zoneRepo.findByNameIgnoreCase("Hangar 2")).thenReturn(Optional.empty());
        when(zoneRepo.save(any())).thenAnswer(inv -> { MuseumZone z = inv.getArgument(0); z.setId(9L); return z; });

        mvc.perform(post("/api/zones").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  Hangar 2  \"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(9))
            .andExpect(jsonPath("$.name").value("Hangar 2"))
            .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void blankTooLongOrDuplicateNamesAreRefused() throws Exception {
        when(zoneRepo.findByNameIgnoreCase("aircraft park")).thenReturn(Optional.of(zone(3, "Aircraft Park", false)));
        mvc.perform(post("/api/zones").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"   \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/zones").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + "x".repeat(61) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/zones").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/zones").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"aircraft park\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").exists());
        verify(zoneRepo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void directorDisablesAndReEnablesAZone() throws Exception {
        MuseumZone park = zone(3, "Aircraft Park", true);
        when(zoneRepo.findById(3L)).thenReturn(Optional.of(park));
        when(zoneRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mvc.perform(patch("/api/zones/3").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isActive").value(false));
        ArgumentCaptor<MuseumZone> saved = ArgumentCaptor.forClass(MuseumZone.class);
        verify(zoneRepo).save(saved.capture());
        assertThat(saved.getValue().isActive()).isFalse();

        mvc.perform(patch("/api/zones/3").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
            .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void patchRefusesUnknownZoneAndNonBooleanBody() throws Exception {
        when(zoneRepo.findById(42L)).thenReturn(Optional.empty());
        when(zoneRepo.findById(3L)).thenReturn(Optional.of(zone(3, "Aircraft Park", true)));
        mvc.perform(patch("/api/zones/42").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/zones/3").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"active\":\"no\"}")).andExpect(status().isBadRequest());
        verify(zoneRepo, never()).save(any());
    }
}
