package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * Master Booking guest list: Manual rows + Bulk Paste + CSV Upload. The parser is plain Java (no CSV library);
 * the 3-option UI is only shown in Master Booking mode. No database.
 */
class MasterManifestImportTest {

    private static MockMultipartFile file(String name, String content, Charset charset) {
        return new MockMultipartFile("companionCsv", name, "text/csv", content.getBytes(charset));
    }

    private static List<String> importNames(List<String> pasted, List<String> manual, MockMultipartFile csv) {
        List<String> names = new ArrayList<>(pasted);
        String error = VisitorPortalController.addMasterManifestNames(names, manual, csv);
        assertThat(error).as("import error").isNull();
        return names;
    }

    @Test
    void manualRowsAndCsvNamesJoinThePastedList() {
        List<String> names = importNames(List.of("Santos, Maria L."), List.of(" Reyes, Jose ", "", "  "),
                file("guests.csv", "Name\nDela Cruz, Juan P.\nBautista, Ana\n", StandardCharsets.UTF_8));
        // the unquoted comma splits the CSV row: only the first column is the name
        assertThat(names).containsExactly("Santos, Maria L.", "Reyes, Jose", "Dela Cruz", "Bautista");
    }

    @Test
    void excelStyleQuotedNamesKeepTheirCommasAndOtherColumnsAreIgnored() {
        String csv = "\"Full Name\",Age\r\n\"Dela Cruz, Juan P.\",25\r\n\"O'Brien, Sean\",31\r\n\r\n\"Santos, Maria L.\"\r\n";
        assertThat(importNames(List.of(), null, file("Guests.CSV", csv, StandardCharsets.UTF_8)))
            .containsExactly("Dela Cruz, Juan P.", "O'Brien, Sean", "Santos, Maria L.");
        // a doubled quote is still unescaped to one ", which no guest name may contain: refused by its row (row-by-row check)
        assertThat(VisitorPortalController.addMasterManifestNames(new ArrayList<>(), null,
                file("Guests.CSV", "\"Full Name\",Age\r\n\"O\"\"Brien, Sean\",31\r\n", StandardCharsets.UTF_8)))
            .startsWith("Guest list upload, row 2: \"O\"Brien, Sean\"");
    }

    @Test
    void utf8WithByteOrderMarkAndWindowsExcelEncodingBothReadPena() {
        assertThat(importNames(List.of(), null, file("bom.csv", "﻿Name\n\"Peña, Jose\"\n", StandardCharsets.UTF_8)))
            .containsExactly("Peña, Jose");
        // Excel "CSV (Comma delimited)" on Windows saves Windows-1252, not UTF-8
        assertThat(importNames(List.of(), null, file("ansi.csv", "Peña, Jose\nNuñez, Ana\n", Charset.forName("windows-1252"))))
            .containsExactly("Peña", "Nuñez");
        assertThat(importNames(List.of(), null, file("ansi.csv", "\"Peña, Jose\"\n", Charset.forName("windows-1252"))))
            .containsExactly("Peña, Jose");
    }

    @Test
    void aTextFileUsesTheWholeLineAsTheName() {
        assertThat(importNames(List.of(), null, file("guests.txt", "Dela Cruz, Juan P.\nSantos, Maria L.\n", StandardCharsets.UTF_8)))
            .containsExactly("Dela Cruz, Juan P.", "Santos, Maria L.");
    }

    @Test
    void onlyTheFirstRowCanBeAHeader() {
        assertThat(importNames(List.of(), null, file("g.csv", "Juan\nName\nAna\n", StandardCharsets.UTF_8)))
            .containsExactly("Juan", "Name", "Ana");
    }

    @Test
    void noFileOrAnEmptyUploadChangesNothing() {
        assertThat(importNames(List.of("A"), null, null)).containsExactly("A");
        assertThat(importNames(List.of("A"), null, new MockMultipartFile("companionCsv", "", "application/octet-stream", new byte[0])))
            .containsExactly("A");
    }

    @Test
    void wrongFileTypesAndOversizedFilesAreRefusedWithAClearMessage() {
        List<String> names = new ArrayList<>();
        assertThat(VisitorPortalController.addMasterManifestNames(names, null,
                new MockMultipartFile("companionCsv", "guests.xlsx", "application/vnd.ms-excel", new byte[] {1, 2, 3})))
            .startsWith("Guest list upload: please choose a .csv");
        byte[] big = new byte[VisitorPortalController.MAX_MANIFEST_CSV_BYTES + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        assertThat(VisitorPortalController.addMasterManifestNames(names, null, new MockMultipartFile("companionCsv", "big.csv", "text/csv", big)))
            .startsWith("Guest list upload: the file is too large");
        assertThat(names).isEmpty();
    }

    // --- The booking page: 3-option UI exists for Master Booking, starts hidden and disabled ---

    @Nested
    @WebMvcTest(VisitorPortalController.class)
    @Import(SecurityConfig.class)
    class BookingPage {

        @Autowired private MockMvc mvc;

        @MockitoBean private VisitorRepository repo;
        @MockitoBean private BookingSlotRepository bookingRepo;
        @MockitoBean private EmailService emailService;
        @MockitoBean private OcrService ocrService;
        @MockitoBean private PredictiveAdvisorService aiService;

        private String page() throws Exception {
            return mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        }

        private static String tag(String html, String id) {
            Matcher m = Pattern.compile("<[^>]*\\bid=\"" + id + "\"[^>]*>").matcher(html);
            assertThat(m.find()).as(id).isTrue();
            return m.group();
        }

        @Test
        void theFormStillPostsMultipartToSave() throws Exception {
            assertThat(tag(page(), "mainRegForm")).contains("action=\"/save\"", "enctype=\"multipart/form-data\"");
        }

        @Test
        void theMasterOnlyGuestListOptionsStartHiddenAndDisabled() throws Exception {
            String html = page();
            assertThat(tag(html, "manifestModes")).contains("d-none");
            assertThat(tag(html, "companionCsvInput")).contains("name=\"companionCsv\"", "disabled");
            assertThat(html).containsSubsequence("data-manifest-mode=\"manual\"", "Manual",
                    "data-manifest-mode=\"paste\"", "Bulk Paste", "data-manifest-mode=\"csv\"", "CSV Upload");
            // the standard textarea is unchanged and stays the visible default
            assertThat(tag(html, "companionInput")).contains("name=\"companionNames\"").doesNotContain("disabled").doesNotContain("d-none");
        }

        @Test
        void thePdfManifestButtonIsDisabledAndMarkedComingInV2() throws Exception {
            assertThat(page()).contains("<button type=\"button\" class=\"nav-link\" disabled aria-disabled=\"true\" title=\"Coming in v2.0\">"
                    + "<i class=\"bi bi-file-earmark-pdf\"></i>Upload PDF Manifest <span class=\"badge text-bg-secondary ms-1\">Coming in v2.0</span></button>");
        }
    }
}
