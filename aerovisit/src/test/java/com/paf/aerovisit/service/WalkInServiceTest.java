package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;

/** 🚶 Walk-in rules with a fixed clock: which of today's windows are offered, the preselection and the deadlines. */
class WalkInServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);
    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final String AFTERNOON = "Afternoon (01:00 PM - 05:00 PM)";

    private final BookingSlotRepository bookingRepo = mock(BookingSlotRepository.class);
    private final VisitorRepository repo = mock(VisitorRepository.class);
    private final WalkInService service = new WalkInService(bookingRepo, repo);
    private final List<BookingSlot> slots = new ArrayList<>();

    private void slot(String window, int capacity) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(TODAY);
        s.setTimeWindow(window);
        s.setMaxCapacity(capacity);
        slots.add(s);
        when(bookingRepo.findBySlotDateAndTimeWindow(TODAY, window)).thenReturn(List.of(s));
    }

    @BeforeEach
    void twoWindows() {
        when(bookingRepo.findBySlotDateGreaterThanEqualOrderBySlotDateAsc(TODAY)).thenReturn(slots);
        when(repo.findClosureDates()).thenReturn(List.of());
        slot(MORNING, 50);
        slot(AFTERNOON, 40);
    }

    private static LocalDateTime at(int hour, int minute) {
        return TODAY.atTime(hour, minute);
    }

    private static List<String> labels(WalkInService.Availability a) {
        return a.windows().stream().map(WalkInService.Window::label).toList();
    }

    private static String current(WalkInService.Availability a) {
        return a.windows().stream().filter(WalkInService.Window::current).map(WalkInService.Window::label).findFirst().orElse(null);
    }

    @Test
    void theWindowUnderWayIsPreselectedAndTheLaterOneIsOfferedToo() {
        WalkInService.Availability a = service.today(at(9, 15));
        assertThat(a.open()).isTrue();
        assertThat(labels(a)).containsExactly(MORNING, AFTERNOON);
        assertThat(current(a)).isEqualTo(MORNING);
    }

    @Test
    void aWindowWithLessThan20MinutesLeftIsNotOfferedAndThePreselectionSkipsToTheNextOne() {
        WalkInService.Availability a = service.today(at(11, 41)); // Morning ends at 12:00: 19 minutes left
        assertThat(labels(a)).containsExactly(AFTERNOON);
        assertThat(current(a)).isEqualTo(AFTERNOON);
        assertThat(labels(service.today(at(11, 40)))).containsExactly(MORNING, AFTERNOON); // exactly 20 minutes: still offered

        assertThat(service.windowError(MORNING, at(11, 41))).isEqualTo(
                "That time window ends in less than 20 minutes. Please choose a later window today or book in advance.");
        assertThat(service.windowError(MORNING, at(12, 1))).startsWith("That time window has already ended.");
        assertThat(service.windowError(AFTERNOON, at(11, 41))).isNull();
    }

    @Test
    void afterTheLastWindowOrOnAFullOrClosedDayWalkInsSayWhy() {
        assertThat(service.today(at(16, 45)).reason()).isEqualTo(WalkInService.ENDED);
        when(repo.sumSeatsHeld(eq(TODAY), any(), any(), isNull())).thenReturn(50L);
        assertThat(service.today(at(9, 0)).reason()).isEqualTo(WalkInService.FULL);
        when(repo.findClosureDates()).thenReturn(List.of(TODAY));
        assertThat(service.today(at(9, 0)).reason()).isEqualTo(WalkInService.CLOSED);
        slots.clear();
        when(repo.findClosureDates()).thenReturn(List.of());
        assertThat(service.today(at(9, 0)).reason()).isEqualTo(WalkInService.NO_SLOTS);
    }

    @Test
    void seatsLeftAreTheCapacityMinusTheSeatsHeld() {
        when(repo.sumSeatsHeld(eq(TODAY), eq(MORNING), any(), isNull())).thenReturn(45L);
        assertThat(service.today(at(9, 0)).windows().get(0).remaining()).isEqualTo(5);
    }

    private static Visitor walkIn(String window, long totalCentavos) {
        Visitor v = new Visitor();
        v.setVisitDate(TODAY);
        v.setTimeWindow(window);
        v.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        v.setAdmissionTotalCentavos(totalCentavos);
        return v;
    }

    @Test
    void thePaymentDeadlineIsTheEarlierOfNowPlusTheMinutesAndTheWindowEnd() {
        Visitor v = walkIn(MORNING, 15_000L);
        assertThat(WalkInService.paymentDeadline(v, at(9, 0), 30)).isEqualTo(at(9, 30));
        assertThat(WalkInService.paymentDeadline(v, at(11, 45), 30)).isEqualTo(at(12, 0)); // capped at the window end
        // a rejected receipt: the minutes again from now, still never past the window end
        v.setPaymentDueAt(at(9, 30));
        assertThat(WalkInService.deadlineAfterRejection(v, at(9, 20), 30)).isEqualTo(at(9, 50));
        assertThat(WalkInService.deadlineAfterRejection(v, at(11, 50), 30)).isEqualTo(at(12, 0));
    }

    @Test
    void aFreeWalkInMustCheckInWithinTheMinutesAndAPaidOneByTheWindowEnd() {
        Visitor free = walkIn(MORNING, 0L);
        assertThat(WalkInService.checkInDeadline(free, at(9, 0), 30)).isEqualTo(at(9, 30));
        assertThat(WalkInService.checkInDeadline(free, at(11, 45), 30)).isEqualTo(at(12, 0));
        // booked for a later window: counted from that window's start, when the gate opens for it
        Visitor later = walkIn(AFTERNOON, 0L);
        assertThat(WalkInService.checkInDeadline(later, at(9, 0), 30)).isEqualTo(at(13, 30));

        Visitor paid = walkIn(MORNING, 15_000L);
        assertThat(WalkInService.checkInDeadline(paid, at(9, 0), 30)).isEqualTo(at(12, 0));
    }

    @Test
    void settingsAreChecked() {
        assertThat(WalkInService.settingsError(20, 30)).isNull();
        assertThat(WalkInService.settingsError(0, 30)).startsWith("The walk-in maximum");
        assertThat(WalkInService.settingsError(101, 30)).startsWith("The walk-in maximum");
        assertThat(WalkInService.settingsError(20, 4)).startsWith("The walk-in payment time");
        assertThat(WalkInService.settingsError(20, 241)).startsWith("The walk-in payment time");
    }
}
