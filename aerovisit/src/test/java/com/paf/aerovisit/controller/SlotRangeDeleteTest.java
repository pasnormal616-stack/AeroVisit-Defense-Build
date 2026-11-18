package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.FixedClock;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.HolidayRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.Holidays;
import com.paf.aerovisit.service.SlotRangeDeletion;

/**
 * 🗑️ Delete Slots (Slots & Staff): Preview, then type DELETE. Only slots with no booking at all (any status) on their
 * date + time window are deleted; the others are kept and listed with the reason. The delete works the bookings out
 * again under the slot lock, so a booking made after the preview still keeps its slot. Director only. No database.
 */
@WebMvcTest({ManagementController.class, SlotRangeController.class})
@Import({SecurityConfig.class, FixedClock.class, Holidays.class, SlotRangeDeletion.class})
class SlotRangeDeleteTest {

    private static final RequestPostProcessor DIRECTOR = user("director").roles("DIRECTOR");
    private static final RequestPostProcessor GUARD = user("guard").roles("GUARD");
    private static final LocalDate FROM = LocalDate.of(2026, 12, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 3);
    private static final String MORNING = "Morning (09:00 AM - 12:00 PM)";
    private static final String AFTERNOON = "Afternoon (01:00 PM - 04:00 PM)";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;
    @MockitoBean private HolidayRepository holidayRepo;

    private final List<BookingSlot> slots = new ArrayList<>();
    private final List<Object[]> bookings = new ArrayList<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        when(bookingRepo.findBySlotDateBetween(FROM, TO)).thenReturn(slots);
        when(bookingRepo.lockBySlotDateBetween(FROM, TO)).thenReturn(slots);
        when(repo.countByDateWindowAndStatus(FROM, TO)).thenReturn(bookings);
    }

    private BookingSlot slot(LocalDate date, String window) {
        BookingSlot s = new BookingSlot();
        s.setId(nextId++);
        s.setSlotDate(date);
        s.setTimeWindow(window);
        s.setMaxCapacity(50);
        slots.add(s);
        return s;
    }

    private void booked(LocalDate date, String window, String status, long count) {
        bookings.add(new Object[] {date, window, status, count});
    }

    private static MockHttpServletRequestBuilder preview() {
        return post("/admin/slots/delete-range/preview").with(csrf()).with(DIRECTOR)
                .param("startDate", FROM.toString()).param("endDate", TO.toString());
    }

    private static MockHttpServletRequestBuilder delete(String confirm) {
        MockHttpServletRequestBuilder req = post("/admin/slots/delete-range").with(csrf()).with(DIRECTOR)
                .param("startDate", FROM.toString()).param("endDate", TO.toString());
        return confirm == null ? req : req.param("confirm", confirm);
    }

    @SuppressWarnings("unchecked")
    private List<BookingSlot> deleted() {
        ArgumentCaptor<Iterable<BookingSlot>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(bookingRepo).deleteAllInBatch(captor.capture());
        List<BookingSlot> list = new ArrayList<>();
        captor.getValue().forEach(list::add);
        return list;
    }

    // --- Deleting ---

    @Test
    void aRangeWithNoBookingsDeletesExactlyThoseSlots() throws Exception {
        BookingSlot a = slot(FROM, MORNING), b = slot(FROM, AFTERNOON), c = slot(TO, MORNING);

        MvcResult result = mvc.perform(delete("DELETE"))
           .andExpect(redirectedUrl("/admin/staff#delete-slots"))
           .andExpect(flash().attribute("success", "🗑️ Deleted 3 slots (2026-12-01 to 2026-12-03)."))
           .andReturn();

        assertThat(deleted()).containsExactly(a, b, c);
        SlotRangeDeletion.Result r = (SlotRangeDeletion.Result) result.getFlashMap().get("slotDeleteResult");
        assertThat(r.total()).isEqualTo(3);
        assertThat(r.deletable()).isEqualTo(3);
        assertThat(r.kept()).isEmpty();
        verify(bookingRepo).lockBySlotDateBetween(FROM, TO); // deleted under the slot lock
    }

    @Test
    void aSlotWithAnyBookingIsKeptAndReportedEvenIfTheBookingIsCancelled() throws Exception {
        slot(FROM, MORNING);
        slot(TO, AFTERNOON);
        booked(FROM, MORNING, "APPROVED", 2);
        booked(FROM, MORNING, "CANCELLED", 1);
        booked(TO, AFTERNOON, "CANCELLED_BY_VISITOR", 1); // only a cancelled booking: still kept

        MvcResult result = mvc.perform(delete("DELETE"))
           .andExpect(flash().attribute("success", "🗑️ Deleted 0 slots (2026-12-01 to 2026-12-03). 2 slots were kept because they have bookings (listed below)."))
           .andReturn();

        verify(bookingRepo, never()).deleteAllInBatch(anyList());
        SlotRangeDeletion.Result r = (SlotRangeDeletion.Result) result.getFlashMap().get("slotDeleteResult");
        assertThat(r.kept()).extracting(SlotRangeDeletion.Kept::date, SlotRangeDeletion.Kept::timeWindow, SlotRangeDeletion.Kept::bookings)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(FROM, MORNING, 3L), org.assertj.core.groups.Tuple.tuple(TO, AFTERNOON, 1L));
        assertThat(r.kept().get(0).reason()).isEqualTo("Has 3 bookings (2 Approved, 1 Cancelled): deleting it would orphan them.");
        assertThat(r.kept().get(1).reason()).isEqualTo("Has 1 booking (1 Cancelled by visitor): deleting it would orphan it.");
    }

    @Test
    void aMixedRangeDeletesTheCleanSlotsAndListsTheKeptOnesWithTheReason() throws Exception {
        BookingSlot clean1 = slot(FROM, AFTERNOON);
        slot(FROM, MORNING);
        BookingSlot clean2 = slot(FROM.plusDays(1), MORNING);
        booked(FROM, MORNING, "NO_SHOW", 1);
        booked(FROM.plusDays(1), AFTERNOON, "APPROVED", 4); // a booking with no slot in the range: nothing to keep

        MvcResult result = mvc.perform(delete("DELETE"))
           .andExpect(flash().attribute("success", "🗑️ Deleted 2 slots (2026-12-01 to 2026-12-03). 1 slot was kept because it has bookings (listed below)."))
           .andReturn();

        assertThat(deleted()).containsExactly(clean1, clean2);
        SlotRangeDeletion.Result r = (SlotRangeDeletion.Result) result.getFlashMap().get("slotDeleteResult");
        assertThat(r.kept()).singleElement().satisfies(k -> {
            assertThat(k.date()).isEqualTo(FROM);
            assertThat(k.timeWindow()).isEqualTo(MORNING);
            assertThat(k.reason()).isEqualTo("Has 1 booking (1 No show): deleting it would orphan it.");
        });

        // the result page lists the kept slot with its reason
        String page = mvc.perform(get("/admin/staff").with(DIRECTOR).flashAttr("slotDeleteResult", r).flashAttr("success", "done"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("id=\"deleteSlotsKept\"", "Kept (not deleted)", "2026-12-01", MORNING, "1 No show")
                .doesNotContain("id=\"deleteSlotsConfirm\"");
    }

    @Test
    void aBookingMadeAfterThePreviewStillKeepsItsSlot() throws Exception {
        BookingSlot clean = slot(FROM, AFTERNOON);
        slot(FROM, MORNING);
        mvc.perform(preview());
        booked(FROM, MORNING, "AWAITING_RECEIPT", 1); // booked between Preview and Delete

        mvc.perform(delete("DELETE"));
        assertThat(deleted()).containsExactly(clean);
    }

    // --- Preview and the DELETE confirmation ---

    @Test
    void previewShowsTheCountsAndTheKeptSlotsAndDeletesNothing() throws Exception {
        slot(FROM, MORNING);
        slot(FROM, AFTERNOON);
        slot(TO, MORNING);
        booked(TO, MORNING, "APPROVED", 1);

        MvcResult result = mvc.perform(preview()).andExpect(redirectedUrl("/admin/staff#delete-slots")).andReturn();
        verify(bookingRepo, never()).deleteAllInBatch(any());
        verify(bookingRepo, never()).lockBySlotDateBetween(any(), any());
        SlotRangeDeletion.Result p = (SlotRangeDeletion.Result) result.getFlashMap().get("slotDeletePreview");
        assertThat(p.total()).isEqualTo(3);
        assertThat(p.deletable()).isEqualTo(2);
        assertThat(p.kept()).hasSize(1);

        String page = mvc.perform(get("/admin/staff").with(DIRECTOR).flashAttr("slotDeletePreview", p))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("id=\"delete-slots\"", "id=\"deleteSlotsPreview\"", "id=\"deleteSlotsConfirm\"",
                "action=\"/admin/slots/delete-range\"", "Type DELETE to confirm", "Delete 2 slots", "Will be kept",
                "Has 1 booking (1 Approved): deleting it would orphan it.", "has bookings and will be kept.")
                .containsPattern("id=\"deleteSlotsSubmit\" disabled")
                .contains("value=\"2026-12-01\"", "value=\"2026-12-03\"");
    }

    @Test
    void previewWithNothingDeletableOffersNoConfirmation() throws Exception {
        slot(FROM, MORNING);
        booked(FROM, MORNING, "CHECKED-OUT", 1);
        SlotRangeDeletion.Result p = (SlotRangeDeletion.Result) mvc.perform(preview()).andReturn().getFlashMap().get("slotDeletePreview");
        String page = mvc.perform(get("/admin/staff").with(DIRECTOR).flashAttr("slotDeletePreview", p))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).contains("Nothing to delete in this range.").doesNotContain("id=\"deleteSlotsConfirm\"");
    }

    @Test
    void withoutTypingDeleteNothingIsDeleted() throws Exception {
        slot(FROM, MORNING);
        for (String word : new String[] {null, "", "delete", "DELET", "yes"}) {
            mvc.perform(delete(word))
               .andExpect(redirectedUrl("/admin/staff#delete-slots"))
               .andExpect(flash().attribute("error", SlotRangeController.NOT_CONFIRMED))
               .andExpect(flash().attributeExists("slotDeletePreview")); // the preview stays on screen
        }
        verify(bookingRepo, never()).deleteAllInBatch(any());
        verify(bookingRepo, never()).lockBySlotDateBetween(any(), any());
        mvc.perform(delete("  DELETE  ")).andExpect(flash().attributeExists("success")); // surrounding spaces are fine
    }

    @Test
    void invalidOrReversedDatesAreRefused() throws Exception {
        for (String[] range : new String[][] {{TO.toString(), FROM.toString()}, {"not-a-date", TO.toString()}, {"", ""}}) {
            mvc.perform(post("/admin/slots/delete-range/preview").with(csrf()).with(DIRECTOR).param("startDate", range[0]).param("endDate", range[1]))
               .andExpect(flash().attribute("error", SlotRangeController.BAD_DATES));
            mvc.perform(post("/admin/slots/delete-range").with(csrf()).with(DIRECTOR).param("startDate", range[0]).param("endDate", range[1])
                    .param("confirm", "DELETE"))
               .andExpect(flash().attribute("error", SlotRangeController.BAD_DATES));
        }
        verify(bookingRepo, never()).deleteAllInBatch(any());
    }

    // 🔒 Security review: at most 2 years per run (731 days); a range like year 1 to 9999 would lock every slot row at once
    @Test
    void aRangeLongerThanTwoYearsIsRefusedAndNothingIsLockedOrDeleted() throws Exception {
        String[][] tooLong = {{"0001-01-01", "9999-12-31"}, {FROM.toString(), FROM.plusDays(731).toString()}};
        for (String[] range : tooLong) {
            mvc.perform(post("/admin/slots/delete-range/preview").with(csrf()).with(DIRECTOR).param("startDate", range[0]).param("endDate", range[1]))
               .andExpect(flash().attribute("error", SlotRangeController.RANGE_TOO_LONG));
            mvc.perform(post("/admin/slots/delete-range").with(csrf()).with(DIRECTOR).param("startDate", range[0]).param("endDate", range[1])
                    .param("confirm", "DELETE"))
               .andExpect(flash().attribute("error", SlotRangeController.RANGE_TOO_LONG));
        }
        verify(bookingRepo, never()).lockBySlotDateBetween(any(), any());
        verify(bookingRepo, never()).deleteAllInBatch(any());

        // exactly 731 days is still allowed
        mvc.perform(post("/admin/slots/delete-range/preview").with(csrf()).with(DIRECTOR)
                .param("startDate", FROM.toString()).param("endDate", FROM.plusDays(730).toString()))
           .andExpect(flash().attribute("error", org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void theStaffPageOffersDeleteSlotsUnderTheGenerator() throws Exception {
        String page = mvc.perform(get("/admin/staff").with(DIRECTOR)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(page).containsSubsequence("Bulk Slot Generator", "Delete Slots", "action=\"/admin/slots/delete-range/preview\"")
                .doesNotContain("id=\"deleteSlotsPreview\"");
    }

    // --- Director only ---

    @Test
    void aGuardIsRefused() throws Exception {
        slot(FROM, MORNING);
        mvc.perform(post("/admin/slots/delete-range/preview").with(csrf()).with(GUARD)
                .param("startDate", FROM.toString()).param("endDate", TO.toString()))
           .andExpect(status().isForbidden());
        mvc.perform(post("/admin/slots/delete-range").with(csrf()).with(GUARD)
                .param("startDate", FROM.toString()).param("endDate", TO.toString()).param("confirm", "DELETE"))
           .andExpect(status().isForbidden());
        verify(bookingRepo, never()).deleteAllInBatch(any());
        verify(repo, never()).countByDateWindowAndStatus(any(), any());
    }

    @Test
    void withoutTheCsrfTokenNothingHappens() throws Exception {
        slot(FROM, MORNING);
        mvc.perform(post("/admin/slots/delete-range").with(DIRECTOR)
                .param("startDate", FROM.toString()).param("endDate", TO.toString()).param("confirm", "DELETE"))
           .andExpect(status().isForbidden());
        verify(bookingRepo, never()).deleteAllInBatch(any());
    }
}
