package com.paf.aerovisit.controller;

import static com.paf.aerovisit.controller.KycNonceSupport.kycNonce;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.KycService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.OtpService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * Master Booking guest-list upload, hardened: a downloadable CSV template, Excel workbooks refused with a how-to,
 * row-numbered errors, and all-or-nothing import. No database.
 */
class GuestListUploadSafetyTest {

    private static final String TEMPLATE = "static/downloads/guest-list-template.csv";

    private static MockMultipartFile upload(String name, byte[] bytes) {
        return new MockMultipartFile("companionCsv", name, "text/csv", bytes);
    }

    private static MockMultipartFile upload(String name, String text) {
        return upload(name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] template() throws Exception {
        return new ClassPathResource(TEMPLATE).getContentAsByteArray();
    }

    // --- 1. Template ---

    @Test
    void theTemplateIsAUtf8CsvWithOneHeaderThatTheImportSkips() throws Exception {
        byte[] bytes = template();
        // UTF-8 byte-order mark, so Excel opens names like "Peña" correctly
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("Last Name,First Name,Middle Name");

        // the blank template imports as nothing...
        List<String> names = new ArrayList<>();
        assertThat(VisitorPortalController.addMasterManifestNames(names, null, upload("guest-list-template.csv", bytes)))
            .startsWith("Guest list upload: no guest names were found");
        // ...and filled in (as Excel saves it, one name part per column), only the guests are read
        byte[] filled = (new String(bytes, StandardCharsets.UTF_8) + "Dela Cruz,Juan,P.\r\nSantos,Maria,L.\r\n").getBytes(StandardCharsets.UTF_8);
        assertThat(VisitorPortalController.addMasterManifestNames(names, null, upload("guest-list-template.csv", filled))).isNull();
        assertThat(names).containsExactly("Dela Cruz, Juan P.", "Santos, Maria L.");
    }

    @Test
    void theBrowserCounterSkipsTheSameTemplateHeader() throws Exception {
        String page = new String(new ClassPathResource("templates/register.html").getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(page).contains("'" + VisitorPortalController.MANIFEST_TEMPLATE_HEADER + "'");
    }

    // --- 2. File type ---

    @Test
    void excelWorkbooksAreRefusedWithHowToSaveAsCsv() {
        List<String> names = new ArrayList<>();
        byte[] xlsx = {'P', 'K', 3, 4, 20, 0, 6, 0};
        byte[] xls = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        for (MockMultipartFile f : List.of(upload("guests.xlsx", xlsx), upload("guests.xls", xls),
                                           upload("renamed.csv", xlsx), upload("renamed.txt", xls))) {
            assertThat(VisitorPortalController.addMasterManifestNames(names, null, f)).as(f.getOriginalFilename())
                .isEqualTo(VisitorPortalController.EXCEL_WORKBOOK_MESSAGE)
                .startsWith("Guest list upload: please choose a .csv");
        }
        assertThat(names).isEmpty();
    }

    // --- 4. Row-level errors, all or nothing ---

    @Test
    void aBadRowIsReportedByItsSpreadsheetRowNumberAndNothingIsImported() {
        List<String> names = new ArrayList<>(List.of("Pasted Guest"));
        // row 1 header, row 2 ok, row 3 blank, row 4 bad
        String error = VisitorPortalController.addMasterManifestNames(names, null,
                upload("guests.csv", "Name\n\"Dela Cruz, Juan P.\"\n\n=HYPERLINK(\"http://x\")\nSantos, Maria\n"));

        assertThat(error).startsWith("Guest list upload, row 4: \"=HYPERLINK(")
            .endsWith("may only contain letters, numbers, spaces and . , ' ( ) / -");
        assertThat(names).containsExactly("Pasted Guest"); // row 2 was valid but is not kept either
    }

    @Test
    void anOverlongNameIsReportedByRow() {
        List<String> names = new ArrayList<>();
        String tooLong = "A".repeat(com.paf.aerovisit.model.Visitor.MAX_COMPANION_NAME_LENGTH + 1);
        assertThat(VisitorPortalController.addMasterManifestNames(names, null, upload("guests.txt", "Ana Reyes\n" + tooLong + "\n")))
            .isEqualTo("Guest list upload, row 2: the name is longer than " + com.paf.aerovisit.model.Visitor.MAX_COMPANION_NAME_LENGTH + " characters.");
        assertThat(names).isEmpty();
    }

    @Test
    void binaryJunkIsARowErrorNotACrash() {
        byte[] junk = {0x00, 0x01, (byte) 0xFF, (byte) 0xFE, 0x7F, 0x10, '\n', 0x02};
        String error = VisitorPortalController.addMasterManifestNames(new ArrayList<>(), null, upload("junk.csv", junk));
        assertThat(error).startsWith("Guest list upload, row 1:");
    }

    @Test
    void theSharedNameRuleIsTheOneEveryBookingUses() {
        assertThat(VisitorPortalController.isValidCompanionName("Dela Cruz, Juan P.")).isTrue();
        assertThat(VisitorPortalController.isValidCompanionName("Peña-O'Brien (Jr.)")).isTrue();
        assertThat(VisitorPortalController.isValidCompanionName("=1+1")).isFalse();
        assertThat(VisitorPortalController.isValidCompanionName("<script>")).isFalse();
    }

    // --- Over HTTP: the template download, and /save refusing a bad file with a friendly message ---

    @Nested
    @WebMvcTest({VisitorPortalController.class, OtpController.class})
    @Import(SecurityConfig.class)
    class OverHttp {

        private final LocalDate day = LocalDate.now().plusDays(3);
        private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
        private static final String EMAIL = "school@example.com";

        @Autowired private MockMvc mvc;

        @MockitoBean private VisitorRepository repo;
        @MockitoBean private BookingSlotRepository bookingRepo;
        @MockitoBean private EmailService emailService;
        @MockitoBean private OcrService ocrService;
        @MockitoBean private PredictiveAdvisorService aiService;
        @MockitoBean private OtpService otpService;
        @MockitoBean private KycService kycService;

        @Test
        void anyoneCanDownloadTheTemplateAndTheFormLinksToIt() throws Exception {
            byte[] body = mvc.perform(get("/downloads/guest-list-template.csv"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
            assertThat(body).isEqualTo(template());

            String page = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(page).contains("href=\"/downloads/guest-list-template.csv\"", "download=\"guest-list-template.csv\"", "Download Template (.csv)");
            // the link sits in the CSV Upload panel, before the file input
            assertThat(page).containsSubsequence("id=\"manifestCsv\"", "id=\"csvTemplateLink\"", "id=\"companionCsvInput\"");
        }

        @Test
        void aMasterBookingWithABadRowIsRefusedWithTheRowNumberAndNothingIsSaved() throws Exception {
            BookingSlot slot = new BookingSlot();
            slot.setSlotDate(day);
            slot.setTimeWindow(WINDOW);
            slot.setMaxCapacity(50);
            when(bookingRepo.findBySlotDateAndTimeWindow(day, WINDOW)).thenReturn(List.of(slot));
            when(otpService.isVerified(EMAIL)).thenReturn(true);

            var req = multipart("/save");
            req.with(csrf()).with(kycNonce());
            req.file(new MockMultipartFile("livenessImage", "selfie.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 9}));
            req.file(new MockMultipartFile("highResIdImage", "id.jpg", "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 4}));
            req.file(upload("guests.csv", "Name\nAna Reyes\nBen <b>Cruz</b>\n"));
            req.param("fullName", "Dela Cruz, Juan P.").param("email", EMAIL).param("visitDate", day.toString())
               .param("timeWindow", WINDOW).param("groupSize", "3")
               .param("isMasterBooking", "true").param("eventJustification", "School field trip");

            mvc.perform(req)
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("error",
                    "Guest list upload, row 3: \"Ben <b>Cruz</b>\" may only contain letters, numbers, spaces and . , ' ( ) / -"));

            verify(repo, never()).save(any());
            verify(kycService, never()).screen(any(), any(), anyString(), anyString());
            verify(otpService, never()).consume(anyString());
        }

        @Test
        void theRowErrorEchoesTheNameAsTextNeverAsHtml() throws Exception {
            String page = mvc.perform(get("/").flashAttr("error", "Guest list upload, row 3: \"<img src=x onerror=alert(1)>\" may only contain letters"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(page).contains("&lt;img src=x onerror=alert(1)&gt;").doesNotContain("<img src=x");
        }
    }
}
