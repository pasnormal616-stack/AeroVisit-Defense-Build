package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.AdmissionPricing;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** 💱 Admission prices on the Settings page: the Director edits them (saved as exact centavos); a Guard cannot. No database. */
@WebMvcTest({AdmissionPriceController.class, AdminDashboardController.class})
@Import(SecurityConfig.class)
class AdmissionPriceControllerTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private AdmissionPricing admissionPricing;

    @BeforeEach
    void prices() {
        when(admissionPricing.current()).thenReturn(new AdmissionPricing.Prices(10_000L, 15_000L));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void theDirectorSavesNewPricesAsExactCentavos() throws Exception {
        mvc.perform(post("/admin/settings/admission-prices").with(csrf()).param("regularPrice", "175.50").param("discountedPrice", "0"))
           .andExpect(redirectedUrl("/admin/settings#admissionPrices"))
           .andExpect(flash().attribute("success", "Admission prices saved: Regular PHP 175.50, Discounted PHP 0.00. Bookings already made keep their prices."));
        verify(admissionPricing).save(17_550L, 0L);

        mvc.perform(post("/admin/settings/admission-prices").with(csrf()).param("regularPrice", "100000").param("discountedPrice", "99.9"))
           .andExpect(flash().attributeExists("success"));
        verify(admissionPricing).save(10_000_000L, 9_990L);
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void invalidPricesAreRefused() throws Exception {
        for (String bad : new String[] {"-1", "100000.01", "12.345", "abc", "", "1e9"}) {
            mvc.perform(post("/admin/settings/admission-prices").with(csrf()).param("regularPrice", bad).param("discountedPrice", "100"))
               .andExpect(flash().attribute("error", AdmissionPriceController.INVALID_PRICE));
        }
        verify(admissionPricing, never()).save(anyLong(), anyLong());
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void aGuardCannotChangeThePrices() throws Exception {
        mvc.perform(post("/admin/settings/admission-prices").with(csrf()).param("regularPrice", "1").param("discountedPrice", "1"))
           .andExpect(status().isForbidden());
        verify(admissionPricing, never()).save(anyLong(), anyLong());
    }

    @Test
    void anAnonymousVisitorCannotChangeThePrices() throws Exception {
        mvc.perform(post("/admin/settings/admission-prices").with(csrf()).param("regularPrice", "1").param("discountedPrice", "1"))
           .andExpect(status().is3xxRedirection());
        verify(admissionPricing, never()).save(anyLong(), anyLong());
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void theSettingsPageShowsThePricePanelToTheDirector() throws Exception {
        String html = mvc.perform(get("/admin/settings")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("id=\"admissionPrices\"", "action=\"/admin/settings/admission-prices\"",
                "name=\"regularPrice\"", "value=\"150.00\"", "name=\"discountedPrice\"", "value=\"100.00\"");
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void theSettingsPageHidesThePricePanelFromTheGuard() throws Exception {
        String html = mvc.perform(get("/admin/settings")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("id=\"admissionPrices\"", "/admin/settings/admission-prices");
    }
}
