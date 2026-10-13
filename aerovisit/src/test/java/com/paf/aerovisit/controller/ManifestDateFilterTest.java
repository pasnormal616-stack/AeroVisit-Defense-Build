package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Civilian Manifest visit-date filter (?dateFilter=) on the Pending and Entire Database tabs, combined with the sort. No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class ManifestDateFilterTest {

    private static final LocalDate PICKED = LocalDate.of(2026, 12, 18);
    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 12, 10, 30);

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor v(long id, String status, LocalDate visitDate, LocalDateTime createdAt) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("Leisure");
        v.setStatus(status);
        v.setVisitDate(visitDate);
        v.setCreatedAt(createdAt);
        return v;
    }

    // Rows on the picked date: two pending (3 newer than 1), one approved, one archival baseline row
    private void stubPickedDate() {
        Visitor baseline = v(9, "CHECKED-OUT", PICKED, T);
        baseline.setPurpose("Historical Baseline");
        when(repo.findByVisitDate(PICKED)).thenAnswer(inv -> new ArrayList<>(List.of(
            v(3, "PENDING", PICKED, T.plusHours(1)), v(1, "PENDING_MANUAL_REVIEW", PICKED, T),
            v(2, "APPROVED", PICKED, T.plusMinutes(5)), baseline)));
        // Every other date: must never reach the table while a date is picked
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(
            v(50, "PENDING", PICKED.plusDays(1), T), v(51, "APPROVED", PICKED.minusDays(3), T))));
    }

    @SuppressWarnings("unchecked")
    private static List<Long> ids(MvcResult result) {
        return ((List<Visitor>) result.getModelAndView().getModel().get("listVisitors")).stream().map(Visitor::getId).toList();
    }

    @Test
    void entireDatabaseShowsOnlyVisitsOnThePickedDateWithoutBaselineRows() throws Exception {
        stubPickedDate();
        MvcResult result = mvc.perform(get("/admin").param("filter", "all").param("dateFilter", PICKED.toString()))
            .andExpect(status().isOk())
            .andExpect(model().attribute("dateFilter", PICKED))
            .andReturn();

        assertThat(ids(result)).containsExactly(3L, 2L, 1L); // Entire Database default: newest createdAt first; 9 (Historical Baseline) dropped
        verify(repo, never()).findAll();
    }

    @Test
    void pendingTabCombinesTheDateWithThePendingStatusesAndNewestFirst() throws Exception {
        stubPickedDate();
        MvcResult result = mvc.perform(get("/admin").param("filter", "pending").param("dateFilter", PICKED.toString()).param("sort", "desc"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("sort", "desc"))
            .andReturn();

        assertThat(ids(result)).containsExactly(3L, 1L); // approved 2 is not pending
        verify(repo, never()).findAll();
    }

    @Test
    void withoutADateEveryTabBehavesAsBefore() throws Exception {
        stubPickedDate();
        for (String blank : new String[] {null, ""}) {
            var request = get("/admin").param("filter", "all");
            if (blank != null) request.param("dateFilter", blank);
            MvcResult result = mvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(model().attribute("dateFilter", (Object) null))
                .andReturn();
            assertThat(ids(result)).as("dateFilter=" + blank).containsExactly(51L, 50L); // Entire Database default: newest first
        }
    }

    @Test
    void todaysManifestIgnoresTheDateAndHasNoDatePicker() throws Exception {
        LocalDate today = LocalDate.now();
        when(repo.findByVisitDate(today)).thenAnswer(inv -> new ArrayList<>(List.of(v(7, "APPROVED", today, T))));

        MvcResult result = mvc.perform(get("/admin").param("filter", "today").param("dateFilter", PICKED.toString()))
            .andExpect(status().isOk())
            .andExpect(model().attribute("dateFilter", (Object) null))
            .andExpect(content().string(not(containsString("id=\"dateFilter\""))))
            .andExpect(content().string(not(containsString("name=\"dateFilter\""))))
            .andReturn();

        assertThat(ids(result)).containsExactly(7L);
        verify(repo, never()).findByVisitDate(PICKED);
    }

    @Test
    void pickedDateStaysInThePickerAndEveryFormKeepsIt() throws Exception {
        stubPickedDate();
        String html = mvc.perform(get("/admin").param("filter", "all").param("dateFilter", PICKED.toString())
                .param("sort", "desc").param("capacityDate", "2026-12-25"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // Manifest form: the date picker shows the picked date beside the sort, with Apply + Clear
        var manifest = Pattern.compile("(?s)<form action=\"/admin\" method=\"get\" id=\"manifestFilters\"[^>]*>(.*?)</form>").matcher(html);
        assertThat(manifest.find()).isTrue();
        assertThat(manifest.group(1))
            .contains("<input type=\"hidden\" name=\"filter\" value=\"all\">")
            .contains("<input type=\"hidden\" name=\"capacityDate\" value=\"2026-12-25\">")
            .contains("<option value=\"desc\" selected=\"selected\">Newest First</option>")
            .containsPattern("<input type=\"date\" id=\"dateFilter\" name=\"dateFilter\"[^>]*value=\"2026-12-18\">\\s*<button type=\"submit\" class=\"btn btn-sm btn-primary\">Apply</button>")
            .contains("href=\"/admin?filter=all&amp;sort=desc&amp;capacityDate=2026-12-25\"");

        // Capacity Tracker form: picking a tracker date keeps the manifest's sort and date
        var tracker = Pattern.compile("(?s)<form action=\"/admin\" method=\"get\" class=\"d-flex[^>]*>(.*?)</form>").matcher(html);
        assertThat(tracker.find()).isTrue();
        assertThat(tracker.group(1))
            .contains("<input type=\"hidden\" name=\"sort\" value=\"desc\">")
            .contains("<input type=\"hidden\" name=\"dateFilter\" value=\"2026-12-18\">")
            .doesNotContain("<select name=\"sort\"");

        // The table keeps every column
        assertThat(html).contains("<th class=\"ps-4\">Civilian Profile</th>", "<th>Submitted</th>", "<th>Clearance Details</th>",
            "<th>Verification</th>", "<th>Live Status</th>", "<th class=\"text-end pe-4\">Actions</th>");
    }

    @Test
    void malformedDateIsRejectedWithoutQueryingVisitors() throws Exception {
        mvc.perform(get("/admin").param("filter", "all").param("dateFilter", "2026-13-45' OR 1=1"))
            .andExpect(status().isBadRequest());
        verify(repo, never()).findByVisitDate(any());
        verify(repo, never()).findAll();
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardsNeverSeeTheFullManifest() throws Exception {
        stubPickedDate();
        mvc.perform(get("/admin").param("filter", "all").param("dateFilter", PICKED.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl("/guard/dashboard"));
        verify(repo, never()).findByVisitDate(any());
        verify(repo, never()).findAll();
    }
}
