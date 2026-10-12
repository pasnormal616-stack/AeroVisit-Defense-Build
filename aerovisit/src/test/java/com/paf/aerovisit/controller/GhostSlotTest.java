package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/** Ghost slots: no default capacity of 100. A booking or approval for a slot that doesn't exist is refused. No database. */
@WebMvcTest({GuardOperationController.class, VisitorPortalController.class})
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class GhostSlotTest {

    private static final String WINDOW = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate DATE = LocalDate.now().plusDays(3);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private VisitorPortalController portal;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;

    private Visitor pending(long id, String window) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setStatus("PENDING");
        v.setVisitDate(DATE);
        v.setTimeWindow(window);
        v.setGroupSize(2);
        when(repo.findById(id)).thenReturn(Optional.of(v));
        return v;
    }

    private void slot(LocalDate date, String window, int max) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(window);
        s.setMaxCapacity(max);
        when(bookingRepo.findBySlotDateAndTimeWindow(date, window)).thenReturn(List.of(s));
    }

    // --- Approval ---

    @Test
    void approvalOfABookingWithNoSlotIsRefused() throws Exception {
        // No slot stubbed: the old code counted this as 100 free seats
        Visitor v = pending(20, WINDOW);

        mvc.perform(post("/admin/approve/20").with(csrf()))
            .andExpect(redirectedUrl("/admin"))
            .andExpect(flash().attribute("error", GuardOperationController.INVALID_SLOT));
        mvc.perform(post("/admin/approve-override/20").with(csrf()))
            .andExpect(flash().attribute("error", GuardOperationController.INVALID_SLOT));

        assertThat(v.getStatus()).isEqualTo("PENDING");
        verify(repo, never()).save(any());
        verify(emailService, never()).sendApprovalEmailWithQR(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void aMadeUpTimeWindowIsRefusedEvenWhenTheDateHasSlots() throws Exception {
        slot(DATE, WINDOW, 50);
        Visitor v = pending(21, "Anytime");   // unreadable label: would also skip the gate's time check

        mvc.perform(post("/admin/approve/21").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.INVALID_SLOT));
        assertThat(v.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void approvalWithARealSlotStillWorks() throws Exception {
        slot(DATE, WINDOW, 10);
        long id = 990401L; // approval writes uploads/QR_<id>.png, deleted afterwards
        Visitor v = pending(id, WINDOW);
        when(repo.findByVisitDate(DATE)).thenReturn(List.of(v));
        try {
            mvc.perform(post("/admin/approve/" + id).with(csrf())).andExpect(flash().attribute("success", "Visitor approved and Email sent successfully!"));
        } finally {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + id + ".png"));
        }
        assertThat(v.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void aMasterBookingStillNeedsNoPublicSlot() throws Exception {
        // VIP buyouts can fall on days with no public slots; their time window is "Master Booking Bypass"
        long id = 990402L;
        Visitor master = pending(id, "Master Booking Bypass");
        master.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        try {
            mvc.perform(post("/admin/approve/" + id).with(csrf())).andExpect(flash().attributeExists("success"));
        } finally {
            Files.deleteIfExists(Paths.get("uploads", "QR_" + id + ".png"));
        }
        assertThat(master.getStatus()).isEqualTo("APPROVED");
    }

    // --- Public booking form (/save). The live reCAPTCHA call runs before the slot check, so the check is tested directly. ---

    private int bookable(LocalDate date, String window) {
        Integer capacity = ReflectionTestUtils.invokeMethod(portal, "bookableSlotCapacity", date, window);
        return capacity;
    }

    @Test
    void publicFormOnlyOffersRealUpcomingSlots() {
        slot(DATE, WINDOW, 40);
        slot(LocalDate.now(), WINDOW, 25);
        slot(LocalDate.now().minusDays(1), WINDOW, 30);

        assertThat(bookable(DATE, WINDOW)).isEqualTo(40);
        assertThat(bookable(LocalDate.now(), WINDOW)).as("today is still bookable").isEqualTo(25);
        assertThat(bookable(DATE, "Anytime")).as("made-up window").isZero();
        assertThat(bookable(DATE.plusDays(30), WINDOW)).as("no slot / deleted slot").isZero();
        assertThat(bookable(LocalDate.now().minusDays(1), WINDOW)).as("slot exists but the date has passed").isZero();
        assertThat(bookable(null, WINDOW)).isZero();
        assertThat(bookable(DATE, null)).isZero();
    }
}
