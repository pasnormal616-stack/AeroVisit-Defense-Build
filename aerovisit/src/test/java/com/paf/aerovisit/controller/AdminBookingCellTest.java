package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

/** 🗂️ The compact booking cell on the Director's admin tables: 4 short lines, everything else in a collapsed "Details"
 *  (nothing removed), the terms line without its version code (still in the Excel export), and "Pay by" only while it
 *  applies (red within 24 hours or past). No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class AdminBookingCellTest {

    private static final String AM = "Morning (08:00 AM - 12:00 PM)";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor booking(long id, String status, String paymentStatus, long totalCentavos) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("EDUCATIONAL_TOUR");
        v.setAffiliation("PAF Air Education and Training Command");
        v.setStatus(status);
        v.setVisitDate(LocalDate.now().plusDays(3));
        v.setTimeWindow(AM);
        v.setGroupSize(4);
        v.setDiscountedCount(1);
        v.setRegularCount(totalCentavos == 0 ? 0 : 2);
        v.setFreeCount(totalCentavos == 0 ? 3 : 1);
        v.setDiscountedPriceCentavos(totalCentavos == 0 ? null : 10_000L);
        v.setRegularPriceCentavos(totalCentavos == 0 ? null : 15_000L);
        v.setAdmissionTotalCentavos(totalCentavos);
        v.setPaymentStatus(paymentStatus);
        v.setBillingNumber("0001" + id);
        return v;
    }

    private static Visitor full() {
        Visitor v = booking(21, "PENDING", Visitor.PAY_PROOF_SUBMITTED, 40_000L);
        v.setBillingNumber("000123");
        v.setSpecialConsideration("PWD");
        v.setTermsVersion("2026-11c");
        v.setTermsAcceptedAt(LocalDateTime.of(2026, 11, 5, 9, 12));
        v.setPaymentDueAt(LocalDateTime.now().plusDays(1));
        v.setPossibleBanMatch("Ban #12: Juan Dela Cruz");
        return v;
    }

    private String page(String filter) throws Exception {
        return mvc.perform(get("/admin").param("filter", filter)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    }

    private String entireDatabase(Visitor... visitors) throws Exception {
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(visitors)));
        return page("all");
    }

    /** The booking's row, from its <tr> to the next </tr> */
    private static String row(String html, long id) {
        int start = html.indexOf("data-visitor-id=\"" + id + "\"");
        assertThat(start).as("row of visitor " + id).isPositive();
        return html.substring(start, html.indexOf("</tr>", start));
    }

    /** The always-visible part of a row's booking cell (everything before the collapsed Details) */
    private static String shown(String row, long id) {
        int details = row.indexOf("id=\"bookingDetails" + id + "\"");
        return details < 0 ? row : row.substring(0, details);
    }

    /** The collapsed Details block of a row ("" when the row has none) */
    private static String details(String row, long id) {
        int details = row.indexOf("id=\"bookingDetails" + id + "\"");
        return details < 0 ? "" : row.substring(details);
    }

    // --- 1. Four short lines; the rest collapsed, not removed ---

    @Test
    void theDefaultLinesAreShortAndTheCollapsedDetailsKeepBillingBreakdownAndTerms() throws Exception {
        String row = row(entireDatabase(full()), 21);
        String shown = shown(row, 21), details = details(row, 21);

        // line 1: weekday date and time window; line 2: affiliation · purpose · chip; line 3: count · total + ONE chip
        LocalDate date = LocalDate.now().plusDays(3);
        assertThat(shown).contains(date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", java.util.Locale.ENGLISH)),
            "· " + AM, "PAF Air Education and Training Command", "educational tour", ">PWD<",
            "4 visitors · ₱400.00", ">Proof submitted<", "Possible banned visitor (Ban #12: Juan Dela Cruz)");
        // no duplicates: no "+3 Guests" badge, the amount once, no billing line among the default lines
        assertThat(row).doesNotContain("Guests", "PHP 400.00");
        assertThat(shown).doesNotContain("000123", "Agreed to the Terms");

        // the toggle: a real button, collapsed, pointing at the hidden details in the same cell
        assertThat(shown).contains("<button type=\"button\" class=\"av-details-toggle\" aria-expanded=\"false\" aria-controls=\"bookingDetails21\">");
        assertThat(row).containsPattern("<div class=\"av-details\" id=\"bookingDetails21\" hidden");
        assertThat(details).contains("Billing", "000123", "1 Discounted · 2 Regular · 1 Free", "PHP 100.00", "PHP 150.00",
            "Agreed to the Terms on", "Nov 5, 2026 09:12 AM");
    }

    @Test
    void theNameCellNoLongerCarriesTheFlagsTheBookingCellShowsThem() throws Exception {
        Visitor walkIn = booking(51, "APPROVED", Visitor.PAY_PAID, 15_000L);
        walkIn.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        String row = row(entireDatabase(walkIn), 51);
        String nameCell = row.substring(0, row.indexOf("av-booking-cell"));
        assertThat(nameCell).doesNotContain("Walk-in", "Possible banned visitor");
        assertThat(shown(row, 51)).contains("av-badge--walkin", "Walk-in</span>");
    }

    // --- 2. Terms line: no version code on screen, still in the Excel export ---

    @Test
    void theTermsVersionIsHiddenOnScreenButStaysInTheExcelExport() throws Exception {
        Visitor v = full();
        assertThat(entireDatabase(v)).contains("Agreed to the Terms on").doesNotContain("2026-11c");

        LocalDate today = LocalDate.now();
        when(repo.findRealVisitorsForExport(today.minusDays(29), today)).thenReturn(new ArrayList<>(List.of(v)));
        MvcResult result = mvc.perform(get("/admin/export-excel")).andExpect(status().isOk()).andReturn();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()))) {
            Sheet sheet = book.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            assertThat(fmt.formatCellValue(sheet.getRow(0).getCell(24))).isEqualTo("Terms Version");
            assertThat(fmt.formatCellValue(sheet.getRow(1).getCell(24))).isEqualTo("2026-11c");
        }
    }

    // --- 3. "Pay by": only while it applies, red within 24 hours or past ---

    @Test
    void payByShowsWhileAReceiptIsAwaitedAndTurnsRedWithin24HoursOrPast() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Visitor awaiting = booking(41, Visitor.STATUS_AWAITING_RECEIPT, Visitor.PAY_AWAITING, 40_000L);
        awaiting.setPaymentDueAt(now.plusDays(3));
        Visitor rejected = booking(42, "PENDING", Visitor.PAY_REJECTED, 40_000L);
        rejected.setPaymentDueAt(now.plusHours(5));
        rejected.setPaymentRejectionReason("The amount does not match.");
        Visitor overdue = booking(43, Visitor.STATUS_AWAITING_RECEIPT, Visitor.PAY_AWAITING, 40_000L);
        overdue.setPaymentDueAt(now.minusHours(1));

        String html = entireDatabase(awaiting, rejected, overdue);
        String a = shown(row(html, 41), 41), r = shown(row(html, 42), 42), o = shown(row(html, 43), 43);
        assertThat(a).contains("class=\"av-payby\"", "Pay by ", ">Awaiting receipt<").doesNotContain("av-payby--urgent");
        assertThat(r).contains("class=\"av-payby av-payby--urgent\"", "Pay by ", ">Receipt rejected<");
        assertThat(o).contains("class=\"av-payby av-payby--urgent\"", "Pay by ");
        // shown above, so not repeated in Details; the rejection reason is in Details
        assertThat(details(row(html, 41), 41)).doesNotContain("Pay by");
        assertThat(details(row(html, 42), 42)).contains("Receipt rejected: The amount does not match.");
    }

    @Test
    void payByIsHiddenOnceItNoLongerAffectsAnythingAndKeptInDetailsForTheRecord() throws Exception {
        LocalDateTime soon = LocalDateTime.now().plusHours(2);
        Visitor proof = booking(44, "PENDING", Visitor.PAY_PROOF_SUBMITTED, 40_000L);
        Visitor paid = booking(45, "PENDING", Visitor.PAY_PAID, 40_000L);
        paid.setPaymentVerifiedBy("director");
        paid.setPaymentVerifiedAt(LocalDateTime.of(2026, 11, 6, 10, 2));
        paid.setPaymentVerificationNote("seen in the Landbank report");
        Visitor free = booking(46, "PENDING", Visitor.PAY_NOT_REQUIRED, 0L);
        Visitor approved = booking(47, "APPROVED", Visitor.PAY_AWAITING, 40_000L); // an advance booking approved earlier
        for (Visitor v : List.of(proof, paid, free, approved)) v.setPaymentDueAt(soon);

        String html = entireDatabase(proof, paid, free, approved);
        for (long id : new long[] {44, 45, 46, 47}) {
            String row = row(html, id);
            assertThat(shown(row, id)).as("visitor " + id).doesNotContain("av-payby", "Pay by");
            assertThat(details(row, id)).as("visitor " + id).contains("Pay by");
        }
        assertThat(shown(row(html, 44), 44)).contains(">Proof submitted<");
        assertThat(shown(row(html, 45), 45)).contains(">Paid<");
        assertThat(shown(row(html, 46), 46)).contains("4 visitors · Free").doesNotContain("av-badge--ok\">Free<");
        assertThat(details(row(html, 45), 45)).contains("by <span>director</span>", "Nov 6, 2026 10:02 AM",
            "Note: seen in the Landbank report");
    }

    // --- 4. The same cell on every admin tab that lists bookings ---

    @Test
    void everyBookingTabShowsTheSameCompactCell() throws Exception {
        Visitor v = booking(61, "PENDING", Visitor.PAY_PROOF_SUBMITTED, 40_000L);
        v.setVisitDate(LocalDate.now());
        v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        v.setTermsVersion("2026-11c");
        v.setTermsAcceptedAt(LocalDateTime.now().minusHours(1));
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(List.of(v)));
        when(repo.findPendingQueue()).thenAnswer(inv -> new ArrayList<>(List.of(v)));
        when(repo.findByBookingCategory(Visitor.CATEGORY_WALK_IN)).thenAnswer(inv -> new ArrayList<>(List.of(v)));
        when(repo.findByVisitDate(LocalDate.now())).thenAnswer(inv -> new ArrayList<>(List.of(v)));

        for (String filter : new String[] {"pending", "all", "walkin", "today"}) {
            String row = row(page(filter), 61);
            assertThat(row).as(filter).contains("av-booking-cell", "4 visitors · ₱400.00", "aria-controls=\"bookingDetails61\"",
                "Agreed to the Terms on").doesNotContain("Guests", "2026-11c");
        }
    }

    // --- Helpers ---

    @Test
    void chipCountAndTotalHelpers() {
        Visitor v = booking(1, Visitor.STATUS_AWAITING_RECEIPT, Visitor.PAY_AWAITING, 65_000L);
        assertThat(v.totalShort()).isEqualTo("₱650.00");
        assertThat(v.paymentChipLabel()).isEqualTo("Awaiting receipt");
        assertThat(v.paymentChipTone()).isEqualTo("warn");
        v.setGroupSize(1);
        assertThat(v.visitorsText()).isEqualTo("1 visitor");
        v.setGroupSize(0);
        assertThat(v.visitorsText()).isEqualTo("1 visitor");

        v.setStatus(Visitor.STATUS_PAYMENT_EXPIRED);
        assertThat(v.paymentChipLabel()).isEqualTo("Payment expired");
        assertThat(v.paymentChipTone()).isEqualTo("danger");
        v.setStatus(Visitor.STATUS_RESCHEDULE_REQUIRED);
        assertThat(v.paymentChipLabel()).isEqualTo("Reschedule needed");
        assertThat(v.paymentChipTone()).isEqualTo("warn");

        v.setStatus("PENDING");
        v.setPaymentStatus(Visitor.PAY_PROOF_SUBMITTED);
        assertThat(v.paymentChipTone()).isEqualTo("info");
        v.setPaymentStatus(Visitor.PAY_PAID);
        assertThat(v.paymentChipLabel()).isEqualTo("Paid");
        assertThat(v.paymentChipTone()).isEqualTo("ok");
        v.setPaymentStatus(Visitor.PAY_REJECTED);
        assertThat(v.paymentChipLabel()).isEqualTo("Receipt rejected");
        assertThat(v.paymentChipTone()).isEqualTo("danger");

        Visitor free = booking(2, "PENDING", Visitor.PAY_NOT_REQUIRED, 0L);
        assertThat(free.totalShort()).isEqualTo("Free");
        assertThat(free.paymentChipLabel()).isEqualTo("Free");
        assertThat(free.paymentChipTone()).isEqualTo("ok");

        Visitor older = new Visitor(); // made before admission counts: no total, no chip
        assertThat(older.totalShort()).isEmpty();
        assertThat(older.paymentChipLabel()).isEmpty();
        assertThat(older.payByApplies()).isFalse();
    }

    @Test
    void payByRules() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 5, 12, 0);
        Visitor walkIn = booking(3, "APPROVED", Visitor.PAY_AWAITING, 15_000L);
        walkIn.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        walkIn.setPaymentDueAt(now.plusMinutes(15));
        assertThat(walkIn.payByApplies()).isTrue(); // released at the deadline, so it still matters
        assertThat(walkIn.payByUrgent(now)).isTrue();

        Visitor awaiting = booking(4, Visitor.STATUS_AWAITING_RECEIPT, Visitor.PAY_AWAITING, 15_000L);
        awaiting.setPaymentDueAt(now.plusHours(24));
        assertThat(awaiting.payByUrgent(now)).isTrue(); // exactly 24 hours is red
        awaiting.setPaymentDueAt(now.plusHours(24).plusMinutes(1));
        assertThat(awaiting.payByUrgent(now)).isFalse();
        awaiting.setPaymentDueAt(null);
        assertThat(awaiting.payByApplies()).isFalse();
    }
}
