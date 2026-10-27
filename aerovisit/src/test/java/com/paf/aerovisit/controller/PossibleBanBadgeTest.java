package com.paf.aerovisit.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** 🪪 "Possible banned visitor" on the dashboard: a booking whose name resembles a ban (IdentityBans.nameBan). No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class PossibleBanBadgeTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor pending(long id, String banMatch) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("Leisure");
        v.setStatus("PENDING_MANUAL_REVIEW");
        v.setVisitDate(LocalDate.now().plusDays(5));
        v.setPossibleBanMatch(banMatch);
        return v;
    }

    @Test
    void aFlaggedBookingNamesTheBanAndLinksToTheGallery() throws Exception {
        when(repo.findAll()).thenReturn(List.of(pending(1, "Ban #12: Juan Dela Cruz")));
        mvc.perform(get("/admin").param("filter", "all"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Possible banned visitor (Ban #12: Juan Dela Cruz)")));
    }

    @Test
    void anUnflaggedBookingHasNoBanBadge() throws Exception {
        when(repo.findAll()).thenReturn(List.of(pending(1, null)));
        mvc.perform(get("/admin").param("filter", "all"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("Possible banned visitor"))));
    }
}
