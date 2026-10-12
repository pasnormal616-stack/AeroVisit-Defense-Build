package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Date ranges (?range=today|week|month) for the PDF report and the Excel export, picked on the Analytics page; the Civilian
 * Manifest itself has no range or export controls (operations vs reporting). The dashboard still accepts ?range=. No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class ManifestRangeTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor v(long id, String name, String purpose, LocalDate visitDate) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName(name);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose(purpose);
        v.setStatus("APPROVED");
        v.setVisitDate(visitDate);
        return v;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> ids(MvcResult result) {
        return ((List<Visitor>) result.getModelAndView().getModel().get("listVisitors")).stream().map(Visitor::getId).toList();
    }

    private static String pdfText(MvcResult result) throws Exception {
        PdfReader reader = new PdfReader(result.getResponse().getContentAsByteArray());
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(extractor.getTextFromPage(page)).append('\n');
        }
        return text.toString();
    }

    // --- Date maths (fixed dates, so the result never depends on the day the suite runs) ---

    @Test
    void weekRunsMondayToSundayAndMonthCoversTheWholeCalendarMonth() {
        LocalDate sunday = LocalDate.of(2026, 10, 11);
        LocalDate monday = LocalDate.of(2026, 10, 5);
        assertThat(AdminDashboardController.rangeStart("week", sunday)).isEqualTo(monday);
        assertThat(AdminDashboardController.rangeEnd("week", sunday)).isEqualTo(sunday);
        assertThat(AdminDashboardController.rangeStart("week", monday)).isEqualTo(monday);
        assertThat(AdminDashboardController.rangeEnd("week", monday)).isEqualTo(sunday);

        LocalDate leapFeb = LocalDate.of(2028, 2, 15);
        assertThat(AdminDashboardController.rangeStart("month", leapFeb)).isEqualTo(LocalDate.of(2028, 2, 1));
        assertThat(AdminDashboardController.rangeEnd("month", leapFeb)).isEqualTo(LocalDate.of(2028, 2, 29));

        assertThat(AdminDashboardController.rangeStart("today", sunday)).isEqualTo(sunday);
        assertThat(AdminDashboardController.rangeEnd("today", sunday)).isEqualTo(sunday);
        assertThat(AdminDashboardController.rangeLabel("week", sunday)).isEqualTo("THIS WEEK (2026-10-05 to 2026-10-11)");
    }

    @Test
    void unknownOrMissingRangeFallsBackToToday() {
        for (String bad : new String[] {null, "", "year", "WEEK", "week'; DROP TABLE visitor;--"}) {
            assertThat(AdminDashboardController.manifestRange(bad)).as(String.valueOf(bad)).isEqualTo("today");
        }
        assertThat(AdminDashboardController.manifestRange("week")).isEqualTo("week");
        assertThat(AdminDashboardController.manifestRange("month")).isEqualTo("month");
    }

    // --- Dashboard ---

    @Test
    void defaultTodayViewIsUnchangedAndHasNoRangeOrExportControls() throws Exception {
        when(repo.findByVisitDate(LocalDate.now())).thenAnswer(inv -> new ArrayList<>(List.of(v(1, "Today One", "Leisure", LocalDate.now()))));

        MvcResult result = mvc.perform(get("/admin"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("currentFilter", "today"))
            .andExpect(model().attribute("range", "today"))
            .andReturn();

        assertThat(ids(result)).containsExactly(1L);
        verify(repo, never()).findByVisitDateBetween(any(), any());
        String html = result.getResponse().getContentAsString();
        // reporting lives on the Analytics page: the manifest has no range picker and no export links
        assertThat(html).contains("Filter: today")
            .doesNotContain("aria-label=\"Manifest range\"", "name=\"range\"", "/admin/report", "/admin/export-excel", "This Week");
    }

    @Test
    void thisWeekLoadsMondayToSundayAndDropsBaselineRows() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate from = AdminDashboardController.rangeStart("week", today);
        LocalDate to = AdminDashboardController.rangeEnd("week", today);
        when(repo.findByVisitDateBetween(from, to)).thenAnswer(inv -> new ArrayList<>(List.of(
            v(2, "Monday Visitor", "Leisure", from), v(3, "Sunday Visitor", "Education", to),
            v(4, "Archive", "Historical Baseline", from))));

        MvcResult result = mvc.perform(get("/admin").param("filter", "today").param("range", "week"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("currentFilter", "today"))
            .andExpect(model().attribute("range", "week"))
            .andReturn();

        assertThat(ids(result)).containsExactly(2L, 3L); // (the Daily Capacity Tracker still reads today's date on its own)
        assertThat(result.getResponse().getContentAsString()).doesNotContain("/admin/report", "/admin/export-excel");
    }

    @Test
    void thisMonthLoadsTheWholeCalendarMonth() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate from = today.withDayOfMonth(1);
        LocalDate to = today.withDayOfMonth(today.lengthOfMonth());
        when(repo.findByVisitDateBetween(from, to)).thenAnswer(inv -> new ArrayList<>(List.of(v(5, "First Of Month", "Leisure", from))));

        MvcResult result = mvc.perform(get("/admin").param("range", "month"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("range", "month"))
            .andReturn();

        assertThat(ids(result)).containsExactly(5L);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("/admin/report", "/admin/export-excel");
    }

    @Test
    void unknownRangeOnTheDashboardShowsToday() throws Exception {
        when(repo.findByVisitDate(LocalDate.now())).thenAnswer(inv -> new ArrayList<>(List.of(v(1, "Today One", "Leisure", LocalDate.now()))));

        MvcResult result = mvc.perform(get("/admin").param("range", "<script>alert(1)</script>"))
            .andExpect(status().isOk())
            .andExpect(model().attribute("range", "today"))
            .andReturn();

        assertThat(ids(result)).containsExactly(1L);
        verify(repo, never()).findByVisitDateBetween(any(), any());
        assertThat(result.getResponse().getContentAsString()).doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void pendingAndEntireDatabaseTabsIgnoreTheRange() throws Exception {
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(v(7, "Any", "Leisure", LocalDate.now().plusDays(40)))));
        for (String tab : new String[] {"pending", "all"}) {
            String html = mvc.perform(get("/admin").param("filter", tab).param("range", "week"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("currentFilter", tab))
                .andReturn().getResponse().getContentAsString();
            assertThat(html).as(tab).doesNotContain("aria-label=\"Manifest range\"", "name=\"range\"");
        }
        verify(repo, never()).findByVisitDateBetween(any(), any());
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardIsStillSentToTheGuardDashboardAndCannotExportARange() throws Exception {
        mvc.perform(get("/admin").param("range", "week")).andExpect(redirectedUrl("/guard/dashboard"));
        mvc.perform(get("/admin/report").param("range", "week")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/export-excel").param("range", "month")).andExpect(status().isForbidden());
        verify(repo, never()).findByVisitDateBetween(any(), any());
        verify(repo, never()).findRealVisitorsForExport(any(), any());
    }

    // --- Exports ---

    @Test
    void pdfForThisWeekListsTheWeeksRosterAndNamesTheRange() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate from = AdminDashboardController.rangeStart("week", today);
        LocalDate to = AdminDashboardController.rangeEnd("week", today);
        when(repo.findByVisitDateBetween(from, to)).thenAnswer(inv -> new ArrayList<>(List.of(
            v(2, "Monday Visitor", "Leisure", from), v(4, "Archive Row", "Historical Baseline", from))));
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>());

        MvcResult result = mvc.perform(get("/admin/report").param("range", "week")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        String text = pdfText(result);

        assertThat(text).contains("Report Filter: THIS WEEK (" + from + " to " + to + ")", "Operational Roster", "Monday Visitor")
            .doesNotContain("Archive Row");
        verify(repo, never()).findByVisitDate(today);
    }

    @Test
    void excelForThisMonthAsksForTheMonthAndNamesTheSheet() throws Exception {
        LocalDate today = LocalDate.now();
        LocalDate from = today.withDayOfMonth(1);
        LocalDate to = today.withDayOfMonth(today.lengthOfMonth());
        when(repo.findRealVisitorsForExport(from, to)).thenReturn(new ArrayList<>(List.of(v(5, "Month Visitor", "Leisure", from))));

        MvcResult result = mvc.perform(get("/admin/export-excel").param("range", "month")).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Visitors (This Month)");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("Month Visitor");
        }
        verify(repo).findRealVisitorsForExport(from, to);
    }

    @Test
    void excelWithoutARangeStillCoversTheLast30Days() throws Exception {
        LocalDate today = LocalDate.now();
        when(repo.findRealVisitorsForExport(any(), any())).thenReturn(new ArrayList<>());

        MvcResult result = mvc.perform(get("/admin/export-excel")).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Visitors (Last 30 Days)");
        }
        verify(repo).findRealVisitorsForExport(today.minusDays(29), today);
    }
}
