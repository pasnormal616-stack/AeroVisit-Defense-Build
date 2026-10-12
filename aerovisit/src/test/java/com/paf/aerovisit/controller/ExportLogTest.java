package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.PaneInformation;
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

/** Export Log: Excel raw rows (last 30 days) with feedback + AI columns, PDF report with the Root Cause Matrix. No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class ExportLogTest {

    private static final List<String> HEADERS = List.of("Registry ID", "Civilian Name", "Group Size", "Email Contact", "Phone Number",
        "Visit Date", "Declared Purpose", "Live Status", "Time of Entry", "Assigned Guard", "Feedback Rating", "Time of Exit",
        "Feedback Zone", "Topic Category", "AI Sentiment", "Feedback Comment");

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor visitor(long id, String name, String status, LocalDate date) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName(name);
        v.setEmail("v" + id + "@example.com");
        v.setPhone("+63 917-555-010" + id);
        v.setStatus(status);
        v.setPurpose("Leisure");
        v.setVisitDate(date);
        return v;
    }

    private static Visitor reviewed(long id, String name, int rating, String zone, String topic, String sentiment, String comment) {
        Visitor v = visitor(id, name, "CHECKED-OUT", LocalDate.now().minusDays(id));
        v.setCheckInTime(LocalDateTime.of(2026, 9, 20, 9, 15));
        v.setCheckOutTime(LocalDateTime.of(2026, 9, 20, 11, 45));
        v.setCheckedInBy("guard");
        v.setRating(rating);
        v.setZoneName(zone);
        v.setTopicCategory(topic);
        v.setFeedbackSentiment(sentiment);
        v.setFeedback(comment);
        return v;
    }

    // --- EXCEL ---

    private static List<String> rowText(Row row) {
        DataFormatter fmt = new DataFormatter();
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < HEADERS.size(); i++) {
            cells.add(fmt.formatCellValue(row.getCell(i)));
        }
        return cells;
    }

    @Test
    void excelKeepsAll16ColumnsFormattedAndAsksOnlyForTheLast30Days() throws Exception {
        Visitor tricky = reviewed(3, "=HYPERLINK(\"http://x\")", 1, "Restrooms/Facilities", "Facilities", "NEGATIVE",
            "Mabaho ang CR, walang tubig.\nPlease fix \"agad\" 😡 ñ");
        Visitor pending = visitor(2, "Santos, Ana", "PENDING", null);
        pending.setPhone(null);
        pending.setPurpose(null);
        Visitor offline = reviewed(1, "Lim, Eve", 2, null, null, null, null); // reviewed while the AI was offline
        LocalDate today = LocalDate.now();
        when(repo.findRealVisitorsForExport(today.minusDays(29), today)).thenReturn(new ArrayList<>(List.of(tricky, pending, offline)));

        MvcResult result = mvc.perform(get("/admin/export-excel")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(result.getResponse().getHeader("Content-Disposition")).startsWith("attachment; filename=PAF_AeroVisit_Data_").endsWith(".xlsx");
        // scoped query only: the whole history is never loaded
        verify(repo).findRealVisitorsForExport(today.minusDays(29), today);
        verify(repo, never()).findAll();

        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = book.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(3);

            // header: the same 16 columns, bold, top row frozen
            assertThat(rowText(sheet.getRow(0))).isEqualTo(HEADERS);
            assertThat(book.getFontAt(sheet.getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            PaneInformation pane = sheet.getPaneInformation();
            assertThat(pane.isFreezePane()).isTrue();
            assertThat(pane.getHorizontalSplitTopRow()).isEqualTo((short) 1);
            assertThat(pane.getVerticalSplitLeftColumn()).isEqualTo((short) 0);

            // comment column: fixed width with wrap text; the other columns are auto-sized away from the default
            assertThat(sheet.getColumnWidth(15)).isEqualTo(10_000);
            CellStyle commentStyle = sheet.getRow(1).getCell(15).getCellStyle();
            assertThat(commentStyle.getWrapText()).isTrue();
            assertThat(sheet.getColumnWidth(1)).isNotEqualTo(sheet.getDefaultColumnWidth() * 256);

            // reviewed row: numbers and dates are real Excel values; the formula-looking name stays plain text
            Row first = sheet.getRow(1);
            assertThat(first.getCell(0).getNumericCellValue()).isEqualTo(3);
            assertThat(first.getCell(1).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(first.getCell(5).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(today.minusDays(3));
            assertThat(rowText(first)).containsExactly("3", "=HYPERLINK(\"http://x\")", "1", "v3@example.com", "+63 917-555-0103",
                today.minusDays(3).toString(), "Leisure", "CHECKED-OUT", "2026-09-20 09:15", "guard", "1 Stars", "2026-09-20 11:45",
                "Restrooms/Facilities", "Facilities", "NEGATIVE", "Mabaho ang CR, walang tubig.\nPlease fix \"agad\" 😡 ñ");
            // not reviewed: old placeholders kept, nulls are empty (no literal "null"), feedback columns blank
            assertThat(rowText(sheet.getRow(2))).containsExactly("2", "Santos, Ana", "1", "v2@example.com", "", "", "", "PENDING",
                "N/A", "N/A", "No Review", "N/A", "", "", "", "");
            // reviewed without AI result: same labels as the Analytics page
            assertThat(rowText(sheet.getRow(3)).subList(10, 16)).containsExactly("2 Stars", "2026-09-20 11:45", "Unspecified",
                "Unclassified", "Not Analysed", "");
        }
    }

    @Test
    void excelWithNoVisitsIsJustTheHeaderRow() throws Exception {
        when(repo.findRealVisitorsForExport(any(), any())).thenReturn(new ArrayList<>());

        MvcResult result = mvc.perform(get("/admin/export-excel")).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            assertThat(book.getSheetAt(0).getLastRowNum()).isZero();
            assertThat(rowText(book.getSheetAt(0).getRow(0))).isEqualTo(HEADERS);
        }
    }

    @Test
    void oldCsvEndpointIsGone() throws Exception {
        mvc.perform(get("/admin/export-csv")).andExpect(status().isNotFound());
    }

    // --- PDF ---

    private String pdfText(MvcResult result) throws Exception {
        PdfReader reader = new PdfReader(result.getResponse().getContentAsByteArray());
        PdfTextExtractor extractor = new PdfTextExtractor(reader);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(extractor.getTextFromPage(page)).append('\n');
        }
        return text.toString();
    }

    @Test
    void pdfKeepsTodaysRosterAndAddsTheAllTimeRootCauseMatrix() throws Exception {
        Visitor today = visitor(10, "Reyes, Ben", "APPROVED", LocalDate.now());
        when(repo.findByVisitDate(LocalDate.now())).thenReturn(new ArrayList<>(List.of(today)));
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(
            today,
            reviewed(1, "Old Visitor One", 1, "Restrooms/Facilities", "Facilities", "NEGATIVE", "mabaho"),
            reviewed(2, "Old Visitor Two", 4, "Restrooms/Facilities", "Facilities", "NEGATIVE", "dirty cr"),
            reviewed(3, "Old Visitor Three", 2, "Aircraft Park", "Exhibits", "NEUTRAL", "sira"),
            reviewed(4, "Old Visitor Four", 5, "Aircraft Park", "General", "POSITIVE", "great"))));

        MvcResult result = mvc.perform(get("/admin/report")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentType()).isEqualTo("application/pdf");
        String text = pdfText(result);

        // existing sections and today-only roster are unchanged
        assertThat(text).contains("PAF Aerospace Museum - Official Visitor Report", "Report Filter: TODAY",
            "Command Analytics Summary", "AI Predictive Forecast (Next 7 Days)", "Operational Roster", "Reyes, Ben");
        assertThat(text).doesNotContain("Old Visitor One");
        // new section
        assertThat(text).contains("Feedback Insights - Root Cause Matrix (All Time)", "3 complaint(s) out of 4 review(s).");
        assertThat(text).contains("Zone", "Facilities", "Staff", "Exhibits", "Ticketing/Entry", "General", "Total");
        // the text extractor sometimes runs table cells together, so spaces between cells are optional
        assertThat(text).containsPattern("Restrooms/Facilities\\s*2\\s*-\\s*-\\s*-\\s*-\\s*2");
        assertThat(text).containsPattern("Aircraft Park\\s*-\\s*-\\s*1\\s*-\\s*-\\s*1");
        assertThat(text).containsPattern("All zones\\s*2\\s*0\\s*1\\s*0\\s*0\\s*3");
        assertThat(text.indexOf("Operational Roster")).isLessThan(text.indexOf("Feedback Insights"));
    }

    @Test
    void pdfWithNoComplaintsSaysSoAndSkipsTheTable() throws Exception {
        when(repo.findByVisitDate(LocalDate.now())).thenReturn(new ArrayList<>());
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(reviewed(1, "Happy", 5, "Aircraft Park", "General", "POSITIVE", "great"))));

        String text = pdfText(mvc.perform(get("/admin/report")).andExpect(status().isOk()).andReturn());
        assertThat(text).contains("Feedback Insights - Root Cause Matrix (All Time)", "0 complaint(s) out of 1 review(s).")
            .doesNotContain("All zones");
    }

    @Test
    @WithMockUser(username = "guard", roles = "GUARD")
    void guardCannotDownloadEitherExport() throws Exception {
        mvc.perform(get("/admin/export-excel")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/report")).andExpect(status().isForbidden());
    }

    @Test
    void analyticsPageHasBothExportMenusWithEveryRange() throws Exception {
        String html = mvc.perform(get("/admin/analytics")).andReturn().getResponse().getContentAsString();
        // PDF: Today / This Week / This Month; Excel: the same three plus the original Last 30 Days (no range)
        assertThat(html).containsSubsequence("id=\"exportPdfMenu\"", "Export Log (PDF)",
            "href=\"/admin/report?range=today\"", "href=\"/admin/report?range=week\"", "href=\"/admin/report?range=month\"");
        assertThat(html).containsSubsequence("id=\"exportExcelMenu\"", "Export Excel",
            "href=\"/admin/export-excel?range=today\"", "href=\"/admin/export-excel?range=week\"",
            "href=\"/admin/export-excel?range=month\"", "href=\"/admin/export-excel\"", "Last 30 Days");
        assertThat(html).contains("bi-file-earmark-spreadsheet", "data-bs-toggle=\"dropdown\"").doesNotContain("export-csv");
    }
}
