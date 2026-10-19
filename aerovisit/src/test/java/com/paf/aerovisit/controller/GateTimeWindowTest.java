package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

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
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;

/** Gate check-in only inside the booked time window (opens 15 minutes early). No database: the repository is mocked. */
@WebMvcTest(GuardOperationController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "GUARD")
class GateTimeWindowTest {

    private static final String TOKEN = "3f1c2d9e-7b1a-4c55-9f0e-2a6b8d4c1e77";
    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BookingSlotRepository bookingRepo;

    // --- The rule itself, at fixed times ---

    private static String at(String window, int hour, int minute) {
        return GuardOperationController.timeWindowBlocker(window, LocalTime.of(hour, minute));
    }

    @Test
    void earlyArrivalsWaitUntil15MinutesBeforeTheStart() {
        assertThat(at(MORNING, 6, 0)).isEqualTo("ACCESS DENIED: Check-in opens at 07:45 AM.");
        assertThat(at(MORNING, 7, 44)).isEqualTo("ACCESS DENIED: Check-in opens at 07:45 AM.");
        assertThat(at(MORNING, 7, 45)).isNull();
        assertThat(at(MORNING, 8, 0)).isNull();
    }

    @Test
    void lateArrivalsAreFineUntilTheWindowEnds() {
        assertThat(at(MORNING, 10, 30)).isNull();
        assertThat(at(MORNING, 12, 0)).isNull();
        assertThat(GuardOperationController.timeWindowBlocker(MORNING, LocalTime.of(12, 0, 59))).isNull(); // the whole 12:00 minute
        assertThat(at(MORNING, 12, 1)).isEqualTo("ACCESS DENIED: Time window ended at 12:00 PM.");
        assertThat(at(MORNING, 17, 0)).isEqualTo("ACCESS DENIED: Time window ended at 12:00 PM.");
    }

    @Test
    void theUsersExample_2pmBookingArrivingAt10am() {
        String afternoon = "Afternoon (02:00 PM - 05:00 PM)";
        assertThat(at(afternoon, 10, 0)).isEqualTo("ACCESS DENIED: Check-in opens at 01:45 PM.");
        assertThat(at(afternoon, 13, 45)).isNull();
        assertThat(at(afternoon, 16, 59)).isNull();
        assertThat(at(afternoon, 17, 5)).isEqualTo("ACCESS DENIED: Time window ended at 05:00 PM.");
    }

    @Test
    void twelveOClockAndOddSpacingAreReadCorrectly() {
        assertThat(GuardOperationController.parseTimeWindow("Noon Tour (12:30 PM - 01:15 PM)"))
            .containsExactly(LocalTime.of(12, 30), LocalTime.of(13, 15));
        assertThat(GuardOperationController.parseTimeWindow("Early (12:10 AM - 2:00 am)"))
            .containsExactly(LocalTime.of(0, 10), LocalTime.of(2, 0));
        assertThat(GuardOperationController.parseTimeWindow("Custom (9:05AM-11:00AM)"))
            .containsExactly(LocalTime.of(9, 5), LocalTime.of(11, 0));
        // opening 15 minutes early never wraps back to the previous evening
        assertThat(at("Early (12:10 AM - 2:00 AM)", 0, 0)).isNull();
    }

    @Test
    void missingOrUnreadableLabelsAreNotBlocked() {
        String[] unreadable = { null, "", "Morning", "Morning (8AM-12PM)", "Bad (13:00 PM - 14:00 PM)", "Bad (08:75 AM - 12:00 PM)",
            "Backwards (12:00 PM - 08:00 AM)", "Zero (08:00 AM - 08:00 AM)" };
        for (String label : unreadable) {
            assertThat(GuardOperationController.parseTimeWindow(label)).as(String.valueOf(label)).isNull();
            assertThat(at(label, 3, 0)).as(String.valueOf(label)).isNull();
        }
    }

    // --- Through the gate endpoints (windows built around a fixed gate clock) ---

    // A fixed gate clock (10:00 AM on 20 Oct 2026): these tests no longer depend on when the suite runs, so none of
    // them has to skip itself near midnight
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final LocalTime NOW = LocalTime.of(10, 0);

    @Autowired private GuardOperationController controller;

    @BeforeEach
    void fixTheGateClock() {
        ZoneId zone = ZoneId.systemDefault();
        controller.clock = Clock.fixed(LocalDateTime.of(TODAY, NOW).atZone(zone).toInstant(), zone);
    }

    @AfterEach
    void realClockAgain() {
        controller.clock = null;
    }

    private Visitor approvedToday(String window) {
        Visitor v = new Visitor();
        v.setId(5L);
        v.setName("Juan Dela Cruz");
        v.setEmail("juan@example.com");
        v.setStatus("APPROVED");
        v.setVisitDate(TODAY);
        v.setTimeWindow(window);
        v.setSecureToken(TOKEN);
        when(repo.findBySecureToken(TOKEN)).thenReturn(Optional.of(v));
        when(repo.findById(5L)).thenReturn(Optional.of(v));
        when(repo.findByReferenceId("PAF-A7X92K")).thenReturn(Optional.of(v));
        return v;
    }

    private static String window(LocalTime start, LocalTime end) {
        return "Test (" + start.format(LABEL) + " - " + end.format(LABEL) + ")";
    }

    @Test
    void scanConfirmAndManualCheckInAllRefuseAnEarlyArrival() throws Exception {
        LocalTime now = NOW;
        Visitor v = approvedToday(window(now.plusHours(2), now.plusHours(3)));

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value(Matchers.startsWith("ACCESS DENIED: Check-in opens at ")));
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value(Matchers.startsWith("ACCESS DENIED: Check-in opens at ")));
        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-A7X92K"))
            .andExpect(redirectedUrl("/admin"))
            .andExpect(flash().attribute("error", Matchers.startsWith("ACCESS DENIED: Check-in opens at ")));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        verify(repo, never()).save(any());
    }

    @Test
    void scanConfirmAndManualCheckInAllRefuseAMissedWindow() throws Exception {
        LocalTime now = NOW;
        Visitor v = approvedToday(window(now.minusHours(3), now.minusHours(1)));
        String ended = "ACCESS DENIED: Time window ended at " + now.minusHours(1).format(LABEL) + ".";

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("error"))
            .andExpect(jsonPath("$.message").value(ended));
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.message").value(ended));
        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-A7X92K"))
            .andExpect(flash().attribute("error", ended));

        assertThat(v.getStatus()).isEqualTo("APPROVED");
        verify(repo, never()).save(any());
    }

    @Test
    void anArrivalWithinTheEarlyGraceIsVerifiedAndCheckedIn() throws Exception {
        LocalTime now = NOW;
        Visitor v = approvedToday(window(now.plusMinutes(10), now.plusHours(1)));

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("ready"));
        mvc.perform(post("/admin/api/confirm-check-in").with(csrf()).param("visitorId", "5"))
            .andExpect(jsonPath("$.status").value("success"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
    }

    @Test
    void aLateArrivalInsideTheWindowIsCheckedInManually() throws Exception {
        LocalTime now = NOW;
        Visitor v = approvedToday(window(now.minusHours(1), now.plusHours(1)));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-A7X92K"))
            .andExpect(flash().attribute("success", Matchers.startsWith("🟢 Checked-In via Manual Override")));
        assertThat(v.getStatus()).isEqualTo("CHECKED-IN");
    }

    @Test
    void checkOutIsNeverBlockedByTheWindow() throws Exception {
        LocalTime now = NOW;
        Visitor v = approvedToday(window(now.minusHours(3), now.minusHours(1)));
        v.setStatus("CHECKED-IN");
        v.setCheckInTime(LocalDateTime.of(TODAY, NOW).minusHours(2));

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", TOKEN))
            .andExpect(jsonPath("$.status").value("success"));
        assertThat(v.getStatus()).isEqualTo("CHECKED-OUT");
    }
}
