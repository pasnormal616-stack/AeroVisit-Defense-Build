package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Holiday;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.HolidayRepository;
import com.paf.aerovisit.repository.VisitorRepository;

/** 🎌 No walk-ins on a listed holiday: the walk-in tab says why, and /save's window check refuses it the same way. */
class WalkInHolidayTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 30);
    private static final String MORNING = "Morning (09:30 AM - 10:30 AM)";

    private final BookingSlotRepository bookingRepo = mock(BookingSlotRepository.class);
    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final HolidayRepository holidayRepo = mock(HolidayRepository.class);
    private final WalkInService service = new WalkInService(bookingRepo, repo);

    @BeforeEach
    void aSlotToday() {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(TODAY);
        s.setTimeWindow(MORNING);
        s.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(TODAY)).thenReturn(List.of(s));
        when(bookingRepo.findBySlotDateAndTimeWindow(TODAY, MORNING)).thenReturn(List.of(s));
        when(repo.findClosureDates()).thenReturn(List.of());
        service.holidays = new Holidays(holidayRepo);
    }

    @Test
    void walkInIsUnavailableOnAHolidayWithItsName() {
        when(holidayRepo.findAllByOrderByHolidayDateAsc()).thenReturn(List.of(new Holiday(LocalDate.of(2020, 11, 30), "Bonifacio Day", true)));

        WalkInService.Availability a = service.today(TODAY.atTime(9, 0));
        assertThat(a.open()).isFalse();
        assertThat(a.windows()).isEmpty();
        assertThat(a.reason()).isEqualTo("Closed: Bonifacio Day. Please book a visit for another day.");
        assertThat(service.windowError(MORNING, TODAY.atTime(9, 0))).isEqualTo("Closed: Bonifacio Day. Please book a visit for another day.");
    }

    @Test
    void anOrdinaryDayStillOffersItsWindows() {
        when(holidayRepo.findAllByOrderByHolidayDateAsc()).thenReturn(List.of(new Holiday(TODAY.plusDays(1), "Tomorrow's Holiday", false)));

        WalkInService.Availability a = service.today(TODAY.atTime(9, 0));
        assertThat(a.open()).isTrue();
        assertThat(service.windowError(MORNING, TODAY.atTime(9, 0))).isNull();
    }
}
