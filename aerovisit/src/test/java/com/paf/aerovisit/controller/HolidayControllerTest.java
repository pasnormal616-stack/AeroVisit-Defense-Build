package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.paf.aerovisit.FixedClock;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Holiday;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.HolidayRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.Holidays;

/** 🎌 Holidays & Closed Days in Slots & Staff: add, edit, remove, yearly repeats, the bookings warning and Director-only. No database. */
@WebMvcTest(HolidayController.class)
@Import({SecurityConfig.class, FixedClock.class})
@WithMockUser(roles = "DIRECTOR")
class HolidayControllerTest {

    private static final LocalDate TODAY = FixedClock.TODAY;
    private static final LocalDate SOON = TODAY.plusDays(10);
    private static final String WINDOW = "Morning (09:30 AM - 10:30 AM)";

    @Autowired
    private MockMvc mvc;

    @MockitoBean private HolidayRepository holidayRepo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private VisitorRepository visitorRepo;

    private ResultActions add(LocalDate date, String label, boolean yearly, boolean confirm) throws Exception {
        var request = post("/admin/slots/holidays/add").with(csrf()).param("holidayDate", date.toString()).param("label", label);
        if (yearly) request.param("repeatsYearly", "true");
        if (confirm) request.param("confirm", "true");
        return mvc.perform(request);
    }

    private static Holiday holiday(long id, LocalDate date, String label, boolean yearly) {
        Holiday h = new Holiday(date, label, yearly);
        h.setId(id);
        return h;
    }

    private Holiday saved() {
        ArgumentCaptor<Holiday> saved = ArgumentCaptor.forClass(Holiday.class);
        verify(holidayRepo).save(saved.capture());
        return saved.getValue();
    }

    private static BookingSlot slot(LocalDate date) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(WINDOW);
        s.setMaxCapacity(50);
        return s;
    }

    private static Visitor booking(LocalDate date, String status) {
        Visitor v = new Visitor();
        v.setVisitDate(date);
        v.setTimeWindow(WINDOW);
        v.setStatus(status);
        return v;
    }

    // --- Add, edit, remove ---

    @Test
    void addingAHolidayStoresTheTrimmedNameAndDate() throws Exception {
        add(SOON, "  Bonifacio   Day ", false, false)
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", "🎌 Holiday listed: " + SOON.format(Holidays.LONG)
                    + " (Bonifacio Day). New bookings, walk-ins and the Bulk Slot Generator skip it."));

        Holiday h = saved();
        assertThat(h.getHolidayDate()).isEqualTo(SOON);
        assertThat(h.getLabel()).isEqualTo("Bonifacio Day");
        assertThat(h.isRepeatsYearly()).isFalse();
    }

    @Test
    void aYearlyHolidayFallsOnTheSameDayEveryYear() throws Exception {
        LocalDate lastYear = LocalDate.of(TODAY.getYear() - 1, 12, 25);   // a yearly holiday may be entered with a past year
        add(lastYear, "Christmas Day", true, false)
            .andExpect(flash().attribute("success", "🎌 Holiday listed: Dec 25 every year (Christmas Day). New bookings, walk-ins and the Bulk Slot Generator skip it."));

        Holiday h = saved();
        assertThat(h.isRepeatsYearly()).isTrue();
        assertThat(h.fallsOn(LocalDate.of(TODAY.getYear(), 12, 25))).isTrue();
        assertThat(h.fallsOn(LocalDate.of(TODAY.getYear() + 1, 12, 25))).isTrue();
        assertThat(h.fallsOn(LocalDate.of(TODAY.getYear() + 1, 12, 24))).isFalse();
        assertThat(Holidays.occurrences(h, LocalDate.of(2027, 1, 1), LocalDate.of(2028, 12, 31)))
            .containsExactly(LocalDate.of(2027, 12, 25), LocalDate.of(2028, 12, 25));

        Holiday once = new Holiday(SOON, "Special Day", false);
        assertThat(once.fallsOn(SOON)).isTrue();
        assertThat(once.fallsOn(SOON.plusYears(1))).isFalse();
    }

    @Test
    void editingAHolidayChangesItsDateNameAndRepeat() throws Exception {
        Holiday h = holiday(4, SOON, "Bonifacio Day", false);
        when(holidayRepo.findById(4L)).thenReturn(Optional.of(h));
        when(holidayRepo.findAll()).thenReturn(List.of(h));

        mvc.perform(post("/admin/slots/holidays/4/update").with(csrf())
                .param("holidayDate", SOON.plusDays(1).toString()).param("label", "Bonifacio Day (observed)").param("repeatsYearly", "true"))
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("success", "🎌 Holiday updated: " + SOON.plusDays(1).format(Holidays.SHORT) + " every year (Bonifacio Day (observed))."));

        assertThat(h.getHolidayDate()).isEqualTo(SOON.plusDays(1));
        assertThat(h.getLabel()).isEqualTo("Bonifacio Day (observed)");
        assertThat(h.isRepeatsYearly()).isTrue();
        verify(holidayRepo).save(h);
    }

    @Test
    void removingAHolidayDeletesOnlyIt() throws Exception {
        Holiday h = holiday(4, SOON, "Bonifacio Day", false);
        when(holidayRepo.findById(4L)).thenReturn(Optional.of(h));

        mvc.perform(post("/admin/slots/holidays/4/delete").with(csrf()))
            .andExpect(flash().attribute("success", "Holiday removed: " + SOON.format(Holidays.LONG)
                    + " (Bonifacio Day). Its date can be booked again wherever it has open slots."));
        verify(holidayRepo).delete(h);
        verify(bookingRepo, never()).deleteById(any());

        mvc.perform(post("/admin/slots/holidays/99/delete").with(csrf()))
            .andExpect(flash().attribute("error", "That holiday no longer exists."));
    }

    @Test
    void aBadDateABlankNameAPastOneOffDateOrADuplicateIsRefused() throws Exception {
        mvc.perform(post("/admin/slots/holidays/add").with(csrf()).param("holidayDate", "not-a-date").param("label", "X"))
            .andExpect(flash().attribute("error", "Holiday not saved: please pick a valid date."));
        add(SOON, "   ", false, false)
            .andExpect(flash().attribute("error", "Holiday not saved: the name is required (up to 100 characters)."));
        add(SOON, "x".repeat(101), false, false)
            .andExpect(flash().attribute("error", "Holiday not saved: the name is required (up to 100 characters)."));
        add(TODAY.minusDays(1), "Yesterday", false, false)
            .andExpect(flash().attribute("error", "Holiday not saved: " + TODAY.minusDays(1).format(Holidays.LONG) + " has already passed."));

        when(holidayRepo.findAll()).thenReturn(List.of(holiday(1, LocalDate.of(2020, SOON.getMonth(), SOON.getDayOfMonth()), "Founding Day", true)));
        add(SOON, "Another", false, false)
            .andExpect(flash().attribute("error", "Holiday not saved: " + SOON.format(Holidays.SHORT) + " every year (Founding Day) is already listed on that day."));
        verify(holidayRepo, never()).save(any());
    }

    // --- A date that already has slots or bookings: warn first, save only after "Save anyway" ---

    @Test
    @SuppressWarnings("unchecked")
    void aDateWithBookingsIsNotSavedUntilTheDirectorConfirms() throws Exception {
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(TODAY)).thenReturn(List.of(slot(SOON), slot(SOON), slot(SOON.plusDays(1))));
        when(visitorRepo.findByVisitDate(SOON)).thenReturn(List.of(
            booking(SOON, "APPROVED"), booking(SOON, "PENDING"), booking(SOON, Visitor.STATUS_AWAITING_RECEIPT), booking(SOON, "CANCELLED")));

        var result = add(SOON, "Bonifacio Day", false, false)
            .andExpect(redirectedUrl("/admin/staff"))
            .andExpect(flash().attribute("warning", "⚠️ Not saved yet: " + SOON.format(Holidays.LONG) + " has 2 booking slots and 3 active bookings. "
                    + "Listing it as a holiday stops new bookings, but existing bookings stay and are not emailed. "
                    + "To cancel them and notify the visitors, use Emergency Closure for that date."))
            .andReturn();
        verify(holidayRepo, never()).save(any());
        Map<String, Object> pending = (Map<String, Object>) result.getFlashMap().get("holidayPending");
        assertThat(pending).containsEntry("action", "/admin/slots/holidays/add").containsEntry("holidayDate", SOON.toString())
            .containsEntry("label", "Bonifacio Day").containsEntry("repeatsYearly", false);

        // "Save anyway": the same form with confirm=true lists it; nobody is cancelled or emailed
        add(SOON, "Bonifacio Day", false, true).andExpect(flash().attributeExists("success"));
        assertThat(saved().getHolidayDate()).isEqualTo(SOON);
        verify(visitorRepo, never()).save(any());
        verify(visitorRepo, never()).saveAll(any());
    }

    @Test
    void aDateWithOnlySlotsAlsoWarnsButAQuietDateSavesAtOnce() throws Exception {
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(TODAY)).thenReturn(List.of(slot(SOON)));
        add(SOON, "Bonifacio Day", false, false)
            .andExpect(flash().attribute("warning", org.hamcrest.Matchers.startsWith("⚠️ Not saved yet: " + SOON.format(Holidays.LONG) + " has 1 booking slot and 0 active bookings.")));
        verify(holidayRepo, never()).save(any());

        add(SOON.plusDays(3), "Quiet Day", false, false).andExpect(flash().attributeExists("success"));
        assertThat(saved().getHolidayDate()).isEqualTo(SOON.plusDays(3));
    }

    // --- Director-only ---

    @Test
    @WithMockUser(roles = "GUARD")
    void guardsCannotAddEditOrRemoveHolidays() throws Exception {
        add(SOON, "Bonifacio Day", false, true).andExpect(status().isForbidden());
        mvc.perform(post("/admin/slots/holidays/4/update").with(csrf()).param("holidayDate", SOON.toString()).param("label", "X"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/admin/slots/holidays/4/delete").with(csrf())).andExpect(status().isForbidden());
        verify(holidayRepo, never()).save(any());
        verify(holidayRepo, never()).delete(any());
    }
}
