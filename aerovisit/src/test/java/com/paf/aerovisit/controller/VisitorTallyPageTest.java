package com.paf.aerovisit.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.VisitorTallyArchive;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.repository.VisitorTallyArchiveRepository;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Visitor Tally panel on the Entire Database tab and its CSV export. No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
class VisitorTallyPageTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private VisitorTallyArchiveRepository tallyArchive;

    // Paper records last year (shown) and five years ago (outside the rolling span: hidden), 12 visitors this month
    private final int thisYear = LocalDate.now().getYear();
    private final int lastYear = thisYear - 1;

    @BeforeEach
    void records() {
        when(tallyArchive.findAll()).thenReturn(List.of(
                new VisitorTallyArchive(lastYear, 3, 1508), new VisitorTallyArchive(lastYear, 5, 2536), new VisitorTallyArchive(lastYear, 6, 0),
                new VisitorTallyArchive(thisYear - 5, 12, 467)));
        List<Object[]> live = List.<Object[]>of(new Object[] {thisYear, LocalDate.now().getMonthValue(), 12L});
        when(repo.tallyVisitorsByMonth()).thenReturn(live);
        when(repo.findFirstRealVisitDate()).thenReturn(LocalDate.now().withDayOfMonth(1));
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void entireDatabaseShowsTheTallyWithFormattedNumbersAndTotals() throws Exception {
        mvc.perform(get("/admin").param("filter", "all"))
            .andExpect(status().isOk())
            .andExpect(model().attributeExists("visitorTally"))
            .andExpect(content().string(containsString("Visitor Tally")))
            .andExpect(content().string(containsString("Year " + (thisYear - 4))))
            .andExpect(content().string(not(containsString("Year " + (thisYear - 5)))))   // older than the span: hidden
            .andExpect(content().string(containsString("1,508")))
            .andExpect(content().string(containsString("4,044")))     // last year's total: 1,508 + 2,536 + 0
            .andExpect(content().string(containsString("4,056")))     // grand total with this month's 12 (not the hidden 467)
            .andExpect(content().string(containsString("—")))
            .andExpect(content().string(containsString("href=\"/admin/visitor-tally.csv\"")));
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void todaysManifestDoesNotShowTheTally() throws Exception {
        mvc.perform(get("/admin"))
            .andExpect(status().isOk())
            .andExpect(model().attributeDoesNotExist("visitorTally"))
            .andExpect(content().string(not(containsString("id=\"visitorTallyTable\""))));
    }

    @Test
    @WithMockUser(roles = "DIRECTOR")
    void theDirectorDownloadsTheTallyAsCsv() throws Exception {
        mvc.perform(get("/admin/visitor-tally.csv"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", containsString("attachment; filename=PAF_AeroVisit_Visitor_Tally_")))
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(content().string(containsString("Month,Year " + (thisYear - 4) + ",Year " + (thisYear - 3) + ",Year "
                    + (thisYear - 2) + ",Year " + lastYear + ",Year " + thisYear + ",Total")))
            .andExpect(content().string(containsString("March,,,,1508,")))
            .andExpect(content().string(containsString("TOTAL,,,,4044,12,4056")));
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void aGuardCannotDownloadTheTally() throws Exception {
        mvc.perform(get("/admin/visitor-tally.csv")).andExpect(status().isForbidden());
    }
}
