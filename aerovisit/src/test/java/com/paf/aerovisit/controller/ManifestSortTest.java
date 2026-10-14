package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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

/** Manifest sort (Oldest First / Newest First by createdAt, null-safe), Submitted column and NEW badge. No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class ManifestSortTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 10, 12, 10, 30);

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor v(long id, String status, LocalDateTime createdAt) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("Leisure");
        v.setStatus(status);
        v.setVisitDate(LocalDate.now());
        v.setCreatedAt(createdAt);
        return v;
    }

    // Two legacy rows without a timestamp (ids 7 and 2), a timestamp tie (3 and 6), and later requests
    private static List<Visitor> mixed() {
        return new ArrayList<>(List.of(
            v(5, "PENDING", T.plusHours(2)), v(3, "PENDING", T), v(7, "PENDING", null),
            v(2, "PENDING", null), v(4, "PENDING", T.plusMinutes(5)), v(6, "PENDING", T)));
    }

    private void stubAllTabs(List<Visitor> rows) {
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(rows));
        when(repo.findByStatusIn(org.mockito.ArgumentMatchers.anyCollection())).thenAnswer(inv -> new ArrayList<>(rows));
        when(repo.findByVisitDate(LocalDate.now())).thenAnswer(inv -> new ArrayList<>(rows));
    }

    @SuppressWarnings("unchecked")
    private List<Long> order(String filter, String sort) throws Exception {
        var request = get("/admin").param("filter", filter);
        if (sort != null) request.param("sort", sort);
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return ((List<Visitor>) result.getModelAndView().getModel().get("listVisitors")).stream().map(Visitor::getId).toList();
    }

    @Test
    void todayAndPendingDefaultToOldestFirstWithUntimestampedRowsOnTop() throws Exception {
        stubAllTabs(mixed());
        for (String filter : new String[] {"today", "pending"}) {
            assertThat(order(filter, null)).as(filter).containsExactly(2L, 7L, 3L, 6L, 4L, 5L);
            assertThat(order(filter, "asc")).as(filter).containsExactly(2L, 7L, 3L, 6L, 4L, 5L);
        }
    }

    @Test
    void theEntireDatabaseDefaultsToNewestFirstButOldestFirstCanStillBeChosen() throws Exception {
        stubAllTabs(mixed());
        assertThat(order("all", null)).containsExactly(5L, 4L, 6L, 3L, 7L, 2L);
        assertThat(order("all", "asc")).containsExactly(2L, 7L, 3L, 6L, 4L, 5L);
        String html = mvc.perform(get("/admin").param("filter", "all"))
            .andExpect(model().attribute("sort", "desc"))
            .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<option value=\"desc\" selected=\"selected\">Newest First</option>");
    }

    @Test
    void newestFirstIsTheExactReverse() throws Exception {
        stubAllTabs(mixed());
        for (String filter : new String[] {"today", "pending", "all"}) {
            assertThat(order(filter, "desc")).as(filter).containsExactly(5L, 4L, 6L, 3L, 7L, 2L);
        }
    }

    @Test
    void legacyDataWithNoTimestampsAtAllSortsByIdWithoutErrors() throws Exception {
        stubAllTabs(List.of(v(9, "PENDING", null), v(1, "APPROVED", null), v(4, "PENDING", null)));
        assertThat(order("all", "asc")).containsExactly(1L, 4L, 9L);
        assertThat(order("all", "desc")).containsExactly(9L, 4L, 1L);
        assertThat(order("today", null)).containsExactly(1L, 4L, 9L);
    }

    @Test
    void unknownSortValueFallsBackToOldestFirst() throws Exception {
        stubAllTabs(mixed());
        mvc.perform(get("/admin").param("filter", "pending").param("sort", "<script>"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("sort", "asc"))
            .andExpect(content().string(not(containsString("<script>\""))));
        mvc.perform(get("/admin").param("filter", "all").param("sort", "bogus")).andExpect(model().attribute("sort", "desc"));
    }

    @Test
    void sorterRemembersTheChoiceAndKeepsTabAndDate() throws Exception {
        stubAllTabs(mixed());
        String html = mvc.perform(get("/admin").param("filter", "all").param("sort", "desc").param("capacityDate", "2026-12-25"))
            .andExpect(model().attribute("sort", "desc"))
            .andReturn().getResponse().getContentAsString();

        // The manifest filter form carries the tab, the sort and the tracker date, so changing any of them keeps the other two
        var form = Pattern.compile("(?s)<form action=\"/admin\" method=\"get\" id=\"manifestFilters\"[^>]*>(.*?)</form>").matcher(html);
        assertThat(form.find()).isTrue();
        assertThat(form.group(1))
            .contains("<input type=\"hidden\" name=\"filter\" value=\"all\">")
            .contains("<select name=\"sort\" class=\"form-select form-select-sm w-auto\" onchange=\"this.form.submit()\" aria-label=\"Sort visitors\">")
            .contains("<option value=\"asc\">Oldest First (FIFO)</option>")
            .contains("<option value=\"desc\" selected=\"selected\">Newest First</option>")
            .containsPattern("name=\"capacityDate\"[^>]*value=\"2026-12-25\"");
    }

    @Test
    void submittedColumnShowsTheTimeOrNaOnTodayAndEntireDatabase() throws Exception {
        stubAllTabs(List.of(v(1, "APPROVED", T), v(2, "APPROVED", null)));
        for (String filter : new String[] {"today", "all"}) {
            mvc.perform(get("/admin").param("filter", filter))
                .andExpect(content().string(containsString("<th>Submitted</th>")))
                .andExpect(content().string(containsString("Oct 12, 10:30 AM")))
                .andExpect(content().string(containsString(">N/A</div>")))
                .andExpect(content().string(not(containsString("<th>Requested On</th>"))));
        }
        // The Pending tab keeps its Requested On column (same timestamp), so the time is never shown twice
        mvc.perform(get("/admin").param("filter", "pending"))
            .andExpect(content().string(containsString("<th>Requested On</th>")))
            .andExpect(content().string(not(containsString("<th>Submitted</th>"))));
    }

    @Test
    void newBadgeOnlyForPendingRequestsFromTheLast24Hours() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        stubAllTabs(List.of(
            v(1, "PENDING", now.minusHours(2)),     // NEW
            v(2, "PENDING", now.minusHours(25)),    // too old
            v(3, "APPROVED", now.minusHours(1)),    // not pending
            v(4, "PENDING", null),                  // legacy, no timestamp
            v(5, "PENDING_MANUAL_REVIEW", now.minusHours(1)))); // only status PENDING gets the badge
        for (String filter : new String[] {"today", "pending", "all"}) {
            String html = mvc.perform(get("/admin").param("filter", filter)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            assertThat(html.split("av-badge--new", -1).length - 1).as(filter + ": exactly one NEW badge").isEqualTo(1);
            assertThat(html).as(filter).containsPattern("<span>Visitor 1</span>\\s*<span class=\"av-badge av-badge--new ms-1\">NEW</span>");
        }
    }

    @Test
    void denyEntryButtonOnlyOnTodaysCheckInsAndDenialIsShownWithItsAuditTrail() throws Exception {
        Visitor today = v(1, "CHECKED-IN", T);
        Visitor yesterday = v(8, "CHECKED-IN", T);
        yesterday.setVisitDate(LocalDate.now().minusDays(1));
        Visitor denied = v(2, "DENIED_ENTRY", T);
        denied.setEntryDeniedBy("Sgt. Ramos");
        denied.setEntryDeniedAt(LocalDateTime.now());
        denied.setEntryDenialReason("Group does not match manifest");
        stubAllTabs(List.of(today, yesterday, denied));

        mvc.perform(get("/admin").param("filter", "all"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("data-bs-target=\"#denyModal1\"")))
            .andExpect(content().string(containsString("action=\"/admin/deny-entry\"")))
            .andExpect(content().string(not(containsString("data-bs-target=\"#denyModal8\""))))
            .andExpect(content().string(not(containsString("data-bs-target=\"#denyModal2\""))))
            .andExpect(content().string(containsString("Entry Denied")))
            .andExpect(content().string(containsString("by Sgt. Ramos")))
            .andExpect(content().string(containsString("Group does not match manifest")));
    }
}
