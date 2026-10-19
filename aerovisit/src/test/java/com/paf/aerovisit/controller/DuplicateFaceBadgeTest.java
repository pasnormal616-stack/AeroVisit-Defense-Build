package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
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

/** "Possible duplicate" on the Director's dashboard: the badge and the Pending tab chip. No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class DuplicateFaceBadgeTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor pending(long id, String duplicateOf) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("Leisure");
        v.setStatus("PENDING_MANUAL_REVIEW");
        v.setVisitDate(LocalDate.now().plusDays(5));
        v.setPossibleDuplicateOf(duplicateOf);
        return v;
    }

    @Test
    void aFlaggedBookingShowsWhichBookingItMayDuplicate() throws Exception {
        when(repo.findAll()).thenReturn(List.of(pending(1, "PAF-FIRST1")));
        mvc.perform(get("/admin").param("filter", "all"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("Possible duplicate of PAF-FIRST1")));
    }

    @Test
    void anUnflaggedBookingHasNoDuplicateBadge() throws Exception {
        when(repo.findAll()).thenReturn(List.of(pending(1, null)));
        mvc.perform(get("/admin").param("filter", "all"))
            .andExpect(status().isOk())
            .andExpect(content().string(not(containsString("Possible duplicate of"))));
    }

    @Test
    void thePendingTabHasAPossibleDuplicateChipCountingFlaggedRequests() throws Exception {
        when(repo.findByStatusIn(any())).thenReturn(List.of(pending(1, "PAF-FIRST1"), pending(2, null)));
        mvc.perform(get("/admin").param("filter", "pending"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-triage=\"duplicate\"")))
            .andExpect(content().string(containsString("Possible duplicate")));
    }

    @Test
    void onlyFlaggedRequestsGetTheDuplicateTag() {
        assertThat(PendingQueue.tags(pending(1, "PAF-FIRST1"), "later", List.of())).contains("duplicate");
        assertThat(PendingQueue.tags(pending(2, null), "later", List.of())).doesNotContain("duplicate");
    }
}
