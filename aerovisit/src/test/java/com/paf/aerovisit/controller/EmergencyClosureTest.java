package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.MuseumClosure;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;


/** Emergency "red button" closure: cancellation sweep, safe email batch, access rules and the guard's gate checks. No database. */
@WebMvcTest({ManagementController.class, GuardOperationController.class})
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class EmergencyClosureTest {

    private static final String REASON = "Typhoon Signal No. 3";
    private static final LocalDate CLOSED = LocalDate.now().plusDays(3);

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    private static Visitor visitor(long id, String status) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setStatus(status);
        v.setVisitDate(CLOSED);
        v.setQrCodeUrl("QR_" + id + ".png");
        return v;
    }

    private void newClosure() {
        when(closureRepo.findByClosureDate(CLOSED)).thenReturn(Optional.empty());
        when(closureRepo.save(any(MuseumClosure.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private org.springframework.test.web.servlet.ResultActions declare(String date, String reason) throws Exception {
        return mvc.perform(post("/admin/slots/closure/execute").with(csrf()).param("closureDate", date).param("reason", reason));
    }

    @Test
    void closureCancelsApprovedAndWaitingBookingsAndEmailsEachVisitor() throws Exception {
        newClosure();
        List<Visitor> onDate = List.of(
            visitor(1, "APPROVED"), visitor(2, "PENDING"), visitor(3, "PENDING_MANUAL_REVIEW"), visitor(4, "VERIFIED"),
            visitor(5, "CHECKED-IN"), visitor(6, "REJECTED"), visitor(7, "CANCELLED_BY_VISITOR"));
        when(repo.findByVisitDate(CLOSED)).thenReturn(onDate);

        declare(CLOSED.toString(), "  " + REASON + "  ")
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", containsString("4 booking(s) cancelled, 4 visitor(s) notified by email.")))
            .andExpect(flash().attributeCount(1));

        ArgumentCaptor<MuseumClosure> saved = ArgumentCaptor.forClass(MuseumClosure.class);
        verify(closureRepo).save(saved.capture());
        assertThat(saved.getValue().getClosureDate()).isEqualTo(CLOSED);
        assertThat(saved.getValue().getReason()).isEqualTo(REASON);

        for (Visitor v : onDate.subList(0, 4)) {
            assertThat(v.getStatus()).isEqualTo("CANCELLED");
            assertThat(v.getQrCodeUrl()).isNull();
            verify(emailService).sendEmergencyCancellation(v, REASON);
        }
        assertThat(onDate.subList(4, 7)).extracting(Visitor::getStatus).containsExactly("CHECKED-IN", "REJECTED", "CANCELLED_BY_VISITOR");
        for (Visitor v : onDate.subList(4, 7)) {
            verify(emailService, never()).sendEmergencyCancellation(eq(v), anyString());
        }
        verify(repo).saveAll(onDate.subList(0, 4));
    }

    @Test
    void failedEmailsAreCountedAndDoNotStopTheBatch() throws Exception {
        newClosure();
        Visitor smtpDown = visitor(1, "APPROVED");
        Visitor badAddress = visitor(2, "APPROVED");
        Visitor ok1 = visitor(3, "PENDING");
        Visitor ok2 = visitor(4, "APPROVED");
        when(repo.findByVisitDate(CLOSED)).thenReturn(List.of(smtpDown, badAddress, ok1, ok2));
        doThrow(new EmailService.MailDeliveryException("Brevo request failed: 503 Service Unavailable")).when(emailService).sendEmergencyCancellation(eq(smtpDown), anyString());
        doThrow(new EmailService.MailDeliveryException("Brevo request failed: 400 Bad Request (invalid email)")).when(emailService).sendEmergencyCancellation(eq(badAddress), anyString());

        declare(CLOSED.toString(), REASON)
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", containsString("4 booking(s) cancelled, 2 visitor(s) notified by email.")))
            .andExpect(flash().attribute("error", startsWith("⚠️ 2 cancellation email(s) could not be sent.")));

        // Every visitor was still cancelled and every email was still attempted, including the ones after the failures
        assertThat(List.of(smtpDown, badAddress, ok1, ok2)).extracting(Visitor::getStatus).containsOnly("CANCELLED");
        verify(emailService).sendEmergencyCancellation(ok1, REASON);
        verify(emailService).sendEmergencyCancellation(ok2, REASON);
    }

    @Test
    void closingAnAlreadyClosedDateKeepsTheFirstReasonAndSweepsAgain() throws Exception {
        when(closureRepo.findByClosureDate(CLOSED)).thenReturn(Optional.of(new MuseumClosure(CLOSED, REASON)));
        Visitor straggler = visitor(1, "APPROVED");
        when(repo.findByVisitDate(CLOSED)).thenReturn(List.of(straggler));

        declare(CLOSED.toString(), "Something else").andExpect(redirectedUrl("/admin/staff"));

        verify(closureRepo, never()).save(any());
        assertThat(straggler.getStatus()).isEqualTo("CANCELLED");
        verify(emailService).sendEmergencyCancellation(straggler, REASON);
    }

    @Test
    void invalidRequestsChangeNothing() throws Exception {
        declare(LocalDate.now().minusDays(1).toString(), REASON)
            .andExpect(flash().attribute("error", containsString("has already passed")));
        declare(CLOSED.toString(), "   ")
            .andExpect(flash().attribute("error", containsString("the reason is required")));
        declare(CLOSED.toString(), "x".repeat(256))
            .andExpect(flash().attribute("error", containsString("the reason is required")));
        declare("not-a-date", REASON)
            .andExpect(flash().attribute("error", containsString("please pick a valid date")));

        verify(closureRepo, never()).save(any());
        verify(repo, never()).saveAll(any());
        verify(emailService, never()).sendEmergencyCancellation(any(), any());
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCannotDeclareAClosure() throws Exception {
        declare(CLOSED.toString(), REASON).andExpect(status().isForbidden());
        verify(closureRepo, never()).save(any());
        verify(repo, never()).saveAll(any());
    }

    @Test
    void staffPageShowsTheRedButtonAndUpcomingClosuresWithReopen() throws Exception {
        MuseumClosure closure = new MuseumClosure(CLOSED, REASON);
        closure.setId(9L);
        when(closureRepo.findByClosureDateGreaterThanEqualOrderByClosureDateAsc(LocalDate.now())).thenReturn(List.of(closure));

        mvc.perform(get("/admin/staff"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("action=\"/admin/slots/closure/execute\"")))
            .andExpect(content().string(containsString("Declare Emergency Closure")))
            .andExpect(content().string(containsString(REASON)))
            .andExpect(content().string(containsString("action=\"/admin/slots/closure/9/delete\"")));
    }

    // --- Reopen (undo a mistaken red button) ---

    @Test
    void reopenDeletesTheClosureButLeavesCancelledBookingsAlone() throws Exception {
        MuseumClosure closure = new MuseumClosure(CLOSED, REASON);
        closure.setId(9L);
        when(closureRepo.findById(9L)).thenReturn(Optional.of(closure));

        mvc.perform(post("/admin/slots/closure/9/delete").with(csrf()))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", startsWith("✅ " + CLOSED + " is open for public booking again.")));

        verify(closureRepo).delete(closure);
        verify(repo, never()).save(any());
        verify(repo, never()).saveAll(any());
        verify(repo, never()).findByVisitDate(any());
        verify(emailService, never()).sendEmergencyCancellation(any(), any());
    }

    @Test
    void reopenOfUnknownClosureChangesNothing() throws Exception {
        when(closureRepo.findById(404L)).thenReturn(Optional.empty());

        mvc.perform(post("/admin/slots/closure/404/delete").with(csrf()))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("error", "That closure no longer exists."));

        verify(closureRepo, never()).delete(any());
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void guardCannotReopenADate() throws Exception {
        mvc.perform(post("/admin/slots/closure/9/delete").with(csrf())).andExpect(status().isForbidden());
        verify(closureRepo, never()).delete(any());
    }

    // --- Gate: a closure-cancelled booking cannot get in ---

    @Test
    @WithMockUser(roles = "GUARD")
    void manualCheckInRefusesClosureCancelledBooking() throws Exception {
        Visitor v = visitor(5, "CANCELLED");
        v.setVisitDate(LocalDate.now());
        when(repo.findByReferenceId("PAF-A7X92K")).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/manual-checkin").with(csrf()).param("referenceId", "PAF-A7X92K"))
            .andExpect(flash().attribute("error", "ACCESS DENIED: Visitor rejected or booking cancelled."));

        assertThat(v.getStatus()).isEqualTo("CANCELLED");
        verify(repo, never()).save(any());
    }

    @Test
    @WithMockUser(roles = "GUARD")
    void qrScanRefusesClosureCancelledBooking() throws Exception {
        Visitor v = visitor(5, "CANCELLED");
        v.setVisitDate(LocalDate.now());
        v.setSecureToken("3f1c2d9e-7b1a-4c55-9f0e-2a6b8d4c1e77");
        when(repo.findBySecureToken(v.getSecureToken())).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/api/scan").with(csrf()).param("qrData", v.getSecureToken()))
            .andExpect(jsonPath("$.status").value("error"));

        assertThat(v.getStatus()).isEqualTo("CANCELLED");
        verify(repo, never()).save(any());
    }

    @Test
    void rejectModalCannotOverwriteAGateDenial() throws Exception {
        com.paf.aerovisit.model.Visitor v = new com.paf.aerovisit.model.Visitor();
        v.setId(40L);
        v.setEmail("juan@example.com");
        v.setStatus("DENIED_ENTRY");
        when(repo.findById(40L)).thenReturn(java.util.Optional.of(v));

        mvc.perform(post("/admin/reject-visitor").with(csrf()).param("id", "40").param("presetReason", "Security clearance failed"))
            .andExpect(redirectedUrl("/admin"))
            .andExpect(flash().attribute("error", "This visitor was denied entry at the gate; that record cannot be changed."));
        assertThat(v.getStatus()).isEqualTo("DENIED_ENTRY");
        verify(repo, never()).save(any());
        verify(emailService, never()).sendRejectionEmail(anyString(), anyString(), anyString(), any());
    }
}
