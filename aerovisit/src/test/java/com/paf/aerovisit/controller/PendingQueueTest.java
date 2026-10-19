package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.Visitor;

/** Pending Approval queue grouping, seat headers and triage tags (PendingQueue). Pure Java, no Spring. */
class PendingQueueTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15); // a Thursday
    private static final String AM = "Morning (08:00 AM - 12:00 PM)";
    private static final String PM = "Afternoon (01:00 PM - 05:00 PM)";

    private static Visitor v(long id, LocalDate date) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("Leisure");
        v.setStatus("PENDING");
        v.setVisitDate(date);
        v.setTimeWindow(AM);
        v.setGroupSize(1);
        v.setOcrNameMatch(true);
        v.setSpecialConsideration("Not Applicable");
        return v;
    }

    // Morning has 40 seats left on every date except tomorrow (3 left); Afternoon is full
    private static final Function<LocalDate, List<PendingQueue.SeatWindow>> SEATS = d -> List.of(
        new PendingQueue.SeatWindow(AM, "Morning", 50, d.equals(TODAY.plusDays(1)) ? 3 : 40),
        new PendingQueue.SeatWindow(PM, "Afternoon", 20, 0));

    private static PendingQueue.View build(List<Visitor> requests) {
        return PendingQueue.build(requests, TODAY, SEATS, Set.of(), false);
    }

    private static String tags(Visitor v) {
        return build(new ArrayList<>(List.of(v))).rowInfo().get(v.getId()).tags();
    }

    @Test
    void overdueFirstThenEachDateThenUndatedKeepingTheQueueOrderInsideEachGroup() {
        PendingQueue.View view = build(List.of(
            v(1, TODAY.plusDays(5)), v(2, TODAY), v(3, TODAY.minusDays(2)), v(4, TODAY.plusDays(1)),
            v(5, null), v(6, TODAY), v(7, TODAY.minusDays(1))));

        assertThat(view.rows()).extracting(Visitor::getId).containsExactly(3L, 7L, 2L, 6L, 4L, 1L, 5L);
        assertThat(view.groupStarts()).containsOnlyKeys(3L, 2L, 4L, 1L, 5L);
        assertThat(view.groupStarts().get(3L)).extracting("tone", "label", "open", "requests")
            .containsExactly("overdue", "Overdue: visit date has passed", true, 2);
        assertThat(view.groupStarts().get(2L)).extracting("tone", "label", "open", "requests")
            .containsExactly("today", "Today · Thu, Oct 15", true, 2);
        assertThat(view.groupStarts().get(4L)).extracting("tone", "label", "open")
            .containsExactly("tomorrow", "Tomorrow · Fri, Oct 16", true);
        assertThat(view.groupStarts().get(1L)).extracting("tone", "label", "open")
            .containsExactly("later", "Tue, Oct 20", false);
        assertThat(view.groupStarts().get(5L)).extracting("tone", "label", "open")
            .containsExactly("nodate", "No visit date", true);

        // Later dates start collapsed; every row knows its group
        assertThat(view.rowInfo().get(1L).collapsed()).isTrue();
        assertThat(view.rowInfo().get(2L).collapsed()).isFalse();
        assertThat(view.rowInfo().get(7L).group()).isEqualTo("overdue");
        assertThat(view.rowInfo().get(6L).group()).isEqualTo("2026-10-15");
    }

    @Test
    void headersCountPeopleAndCarrySeatsOnlyForUpcomingDates() {
        Visitor big = v(2, TODAY);
        big.setGroupSize(4);
        Visitor zero = v(6, TODAY);
        zero.setGroupSize(0); // counted as one person, like the capacity check
        PendingQueue.View view = build(List.of(big, zero, v(3, TODAY.minusDays(1))));

        PendingQueue.Group today = view.groupStarts().get(2L);
        assertThat(today.people()).isEqualTo(5);
        assertThat(today.windows()).extracting(PendingQueue.SeatWindow::name).containsExactly("Morning", "Afternoon");
        assertThat(view.groupStarts().get(3L).windows()).isEmpty(); // overdue: no seat counts
    }

    @Test
    void aClosedDayIsFlaggedAndAPickedDateOpensEvenALaterGroup() {
        LocalDate later = TODAY.plusDays(9);
        PendingQueue.View view = PendingQueue.build(List.of(v(1, later)), TODAY, SEATS, Set.of(later), true);
        assertThat(view.groupStarts().get(1L).closed()).isTrue();
        assertThat(view.groupStarts().get(1L).open()).isTrue();
        assertThat(view.rowInfo().get(1L).collapsed()).isFalse();
    }

    @Test
    void triageTags() {
        Visitor manual = v(1, TODAY.plusDays(3));
        manual.setStatus("PENDING_MANUAL_REVIEW");
        manual.setOcrNameMatch(false);
        assertThat(tags(manual)).isEqualTo("manual unverified");

        Visitor fits = v(2, TODAY.plusDays(1));
        fits.setGroupSize(3);   // exactly the 3 seats left tomorrow morning
        assertThat(tags(fits)).isEqualTo("soon");
        Visitor tooBig = v(3, TODAY.plusDays(1));
        tooBig.setGroupSize(4);
        assertThat(tags(tooBig)).isEqualTo("conflict soon");

        Visitor fullWindow = v(4, TODAY.plusDays(3));
        fullWindow.setTimeWindow(PM);
        assertThat(tags(fullWindow)).isEqualTo("conflict");
        Visitor noSlot = v(5, TODAY.plusDays(3));
        noSlot.setTimeWindow("Evening (06:00 PM - 08:00 PM)");
        assertThat(tags(noSlot)).isEqualTo("conflict");

        Visitor master = v(6, TODAY);
        master.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        master.setTimeWindow(PM);
        master.setGroupSize(50);
        assertThat(tags(master)).isEqualTo("large master soon"); // Master Bookings skip the seat count

        Visitor fifteen = v(7, TODAY.plusDays(3));
        fifteen.setGroupSize(15);
        assertThat(tags(fifteen)).isEmpty();
        fifteen.setGroupSize(16);
        assertThat(tags(fifteen)).isEqualTo("large");

        Visitor special = v(8, TODAY.plusDays(3));
        special.setSpecialConsideration("Wheelchair user");
        assertThat(tags(special)).isEqualTo("special");

        Visitor overdue = v(9, TODAY.minusDays(1));
        overdue.setTimeWindow(PM); // a full window on a past day is not a seat conflict
        assertThat(tags(overdue)).isEqualTo("overdue");
    }

    @Test
    void chipsListEveryKindInOrderWithItsCount() {
        Visitor manual = v(1, TODAY);
        manual.setStatus("PENDING_MANUAL_REVIEW");
        PendingQueue.View view = build(List.of(manual, v(2, TODAY), v(3, TODAY.minusDays(4))));
        assertThat(view.chips()).extracting(PendingQueue.Chip::key)
            .containsExactly("manual", "unverified", "duplicate", "conflict", "large", "master", "special", "soon", "overdue");
        assertThat(view.chips()).extracting(PendingQueue.Chip::count).containsExactly(1, 0, 0, 0, 0, 0, 0, 2, 1);
        assertThat(view.chips().get(4).label()).isEqualTo("Large group (16+)");
    }

    @Test
    void searchTextCoversIdNameEmailPhoneReferenceAndAffiliation() {
        Visitor x = v(42, TODAY);
        x.setName("Juan DELA Cruz");
        x.setContactNumber("0917-123-4567");
        x.setReferenceId("PAF-A7X92K");
        x.setAffiliation("PAF Academy");
        assertThat(build(List.of(x)).rowInfo().get(42L).search())
            .isEqualTo("42 juan dela cruz v42@example.com 0917-123-4567 paf-a7x92k paf academy");
    }

    @Test
    void emptyQueueAndWindowNames() {
        PendingQueue.View view = build(List.of());
        assertThat(view.rows()).isEmpty();
        assertThat(view.groupStarts()).isEmpty();
        assertThat(PendingQueue.windowName(AM)).isEqualTo("Morning");
        assertThat(PendingQueue.windowName("Full Day")).isEqualTo("Full Day");
    }
}
