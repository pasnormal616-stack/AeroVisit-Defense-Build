package com.paf.aerovisit.controller;

import com.paf.aerovisit.FixedClock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * Regression tests for the security pass's Low findings on the staff side (the clock is fixed at noon, Manila):
 * L1 no approval after the visit's window ended; L3 a closed booking can't be "rejected" (no misleading email);
 * L7 Force Approve takes an older VERIFIED request; L8 the export's phone column has the form's contact number;
 * L9 a free walk-in past its check-in deadline is refused at manual check-in and in the Process panel. No database.
 */
@WebMvcTest({ManagementController.class, GuardOperationController.class, AdminDashboardController.class})
@Import({FixedClock.class, SecurityConfig.class})
@WithMockUser(username = "director", roles = "DIRECTOR")
class LowFindingsTest {

    private static final LocalDate TODAY = FixedClock.TODAY;
    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final String EARLY = "Early (06:00 AM - 08:00 AM)";       // over by noon
    private static final String AROUND_NOON = "Midday (11:00 AM - 01:00 PM)"; // open at noon

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    private final List<Long> ids = new ArrayList<>();

    @BeforeEach
    void anySlotExists() {
        BookingSlot slot = new BookingSlot();
        slot.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(any(), any())).thenReturn(List.of(slot));
    }

    @AfterEach
    void removeQrs() throws Exception {
        for (Long id : ids) Files.deleteIfExists(Paths.get("uploads", "QR_" + id + ".png"));
    }

    private Visitor booking(long id, String status, LocalDate date, String window) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setReferenceId("PAF-LOW" + id);
        v.setStatus(status);
        v.setVisitDate(date);
        v.setTimeWindow(window);
        v.setGroupSize(1);
        v.setSecureToken("token-" + id);
        when(repo.findById(id)).thenReturn(Optional.of(v));
        when(repo.findByReferenceId("PAF-LOW" + id)).thenReturn(Optional.of(v));
        ids.add(id);
        return v;
    }

    // --- L1 ---

    @Test
    void aRequestWhoseWindowHasEndedCannotBeApprovedOrForceApproved() throws Exception {
        Visitor yesterday = booking(101, "PENDING", TODAY.minusDays(1), MORNING);
        Visitor earlierToday = booking(102, "PENDING", TODAY, EARLY);
        for (Visitor v : List.of(yesterday, earlierToday)) {
            mvc.perform(post("/admin/approve/" + v.getId()).with(csrf()))
               .andExpect(flash().attribute("error", GuardOperationController.WINDOW_ENDED_APPROVAL));
            mvc.perform(post("/admin/approve-override/" + v.getId()).with(csrf()))
               .andExpect(flash().attribute("error", GuardOperationController.WINDOW_ENDED_APPROVAL));
            assertThat(v.getStatus()).isEqualTo("PENDING");
            assertThat(v.getQrCodeUrl()).isNull();
        }
        verify(repo, never()).save(any());
        verifyNoInteractions(emailService);
    }

    // --- L3 ---

    @Test
    void aClosedBookingCannotBeRejectedByEitherRejectAction() throws Exception {
        long id = 110;
        for (String closed : List.of("CANCELLED", "CANCELLED_BY_VISITOR", Visitor.STATUS_PAYMENT_EXPIRED, "REJECTED")) {
            Visitor v = booking(id++, closed, TODAY.plusDays(3), MORNING);
            String message = "This booking is already closed (status: " + closed + "); there is nothing to reject.";
            mvc.perform(post("/admin/reject/" + v.getId()).with(csrf())).andExpect(flash().attribute("error", message));
            mvc.perform(post("/admin/reject-visitor").with(csrf()).param("id", String.valueOf(v.getId()))
                            .param("presetReason", "Security clearance failed"))
               .andExpect(flash().attribute("error", message));
            assertThat(v.getStatus()).isEqualTo(closed);
        }
        verify(repo, never()).save(any());
        verifyNoInteractions(emailService); // no "your booking was not approved" email for a booking already closed
    }

    // --- L7 ---

    @Test
    void anOlderVerifiedRequestCanBeForceApproved() throws Exception {
        Visitor v = booking(120, "VERIFIED", TODAY.plusDays(3), MORNING);
        assertThat(v.awaitsApproval()).isTrue();
        mvc.perform(post("/admin/approve-override/120").with(csrf()))
           .andExpect(flash().attribute("success", Matchers.startsWith("⚠️ Force-approved over capacity")));
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        verify(repo).save(v);
    }

    // --- L8 ---

    @Test
    void theExportsPhoneColumnHasTheFormsContactNumber() throws Exception {
        Visitor newer = booking(130, "PENDING", TODAY.minusDays(1), MORNING);
        newer.setContactNumber("0917-555-0130");          // the booking form's field
        Visitor older = booking(131, "PENDING", TODAY.minusDays(2), MORNING);
        older.setPhone("+63 917-555-0131");               // an older booking: only the legacy field
        when(repo.findRealVisitorsForExport(any(), any())).thenReturn(new ArrayList<>(List.of(newer, older)));

        byte[] xlsx = mvc.perform(get("/admin/export-excel")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = book.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            assertThat(fmt.formatCellValue(sheet.getRow(0).getCell(13))).isEqualTo("Phone Number");
            assertThat(fmt.formatCellValue(sheet.getRow(1).getCell(13))).isEqualTo("0917-555-0130");
            assertThat(fmt.formatCellValue(sheet.getRow(2).getCell(13))).isEqualTo("+63 917-555-0131");
        }
    }

    // --- L9 ---

    private Visitor lateFreeWalkIn(long id) {
        Visitor v = booking(id, "APPROVED", TODAY, AROUND_NOON);
        v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        v.setFreeCount(1);
        v.setAdmissionTotalCentavos(0L);
        v.setPaymentStatus(Visitor.PAY_NOT_REQUIRED);
        v.setCheckInDueAt(FixedClock.NOW.minusMinutes(5)); // its 30 minutes are up; the sweep releases it shortly
        return v;
    }

    @Test
    void aFreeWalkInPastItsCheckInDeadlineIsRefusedAtManualCheckIn() throws Exception {
        Visitor v = lateFreeWalkIn(140);
        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-LOW140"))
           .andExpect(flash().attribute("error", Matchers.startsWith("ACCESS DENIED: This free walk-in was not checked in by its deadline")));
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getCheckInTime()).isNull();
        verify(repo, never()).save(any());
    }

    @Test
    void aFreeWalkInPastItsCheckInDeadlineIsRefusedInTheProcessPanel() throws Exception {
        Visitor v = lateFreeWalkIn(141);
        mvc.perform(post("/admin/walk-ins/141/process").with(csrf()).param("verifiedChecked", "true"))
           .andExpect(flash().attribute("error", Matchers.containsString("This free walk-in was not checked in by its deadline")));
        assertThat(v.getStatus()).isEqualTo("APPROVED");
        assertThat(v.getCheckInTime()).isNull();
    }
}
