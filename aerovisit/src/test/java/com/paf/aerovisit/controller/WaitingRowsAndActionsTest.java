package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.MuseumClosureRepository;
import com.paf.aerovisit.repository.MuseumEmployeeRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.TimeWindowConfigRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * ⌛ Bookings still waiting for the visitor's payment receipt are shown to the Director for review only (Pending Approval,
 * after the ready requests; Today's Manifest), never approvable; and every admin row has ONE main button, quick-view
 * icons and a ⋮ menu with only the actions that apply (destructive ones last, in red). No database.
 */
@WebMvcTest({GuardOperationController.class, ManagementController.class, AdminDashboardController.class})
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class WaitingRowsAndActionsTest {

    private static final String AM = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate LATER = TODAY.plusDays(4);

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    @BeforeEach
    void slots() {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(LATER);
        s.setTimeWindow(AM);
        s.setMaxCapacity(50);
        when(bookingRepo.findBySlotDateAndTimeWindow(LATER, AM)).thenReturn(List.of(s));
    }

    private static Visitor v(long id, String status, LocalDate date) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("Leisure");
        v.setStatus(status);
        v.setVisitDate(date);
        v.setTimeWindow(AM);
        v.setGroupSize(2);
        return v;
    }

    // A paid booking (2 regular, PHP 300) in the given payment state, with its billing number and pay-by deadline
    private static Visitor paid(long id, String status, LocalDate date, String paymentStatus, LocalDateTime due) {
        Visitor v = v(id, status, date);
        v.setRegularCount(2);
        v.setDiscountedCount(0);
        v.setFreeCount(0);
        v.setRegularPriceCentavos(15_000L);
        v.setAdmissionTotalCentavos(30_000L);
        v.setBillingNumber(String.format("%06d", id));
        v.setPaymentToken("tok-" + id);
        v.setPaymentStatus(paymentStatus);
        v.setPaymentDueAt(due);
        return v;
    }

    private Visitor awaitingReceipt(long id, LocalDate date, LocalDateTime due) {
        return paid(id, Visitor.STATUS_AWAITING_RECEIPT, date, Visitor.PAY_AWAITING, due);
    }

    private Visitor receiptRejected(long id, LocalDate date, LocalDateTime due) {
        Visitor v = paid(id, "PENDING", date, Visitor.PAY_REJECTED, due);
        v.setPaymentRejectionReason("The amount does not match.");
        return v;
    }

    private String page(String... params) throws Exception {
        var request = get("/admin");
        for (int i = 0; i + 1 < params.length; i += 2) request.param(params[i], params[i + 1]);
        return mvc.perform(request).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // The <tr> of one booking row (from its data-visitor-id to the end of the row)
    private static String row(String html, long id) {
        int start = html.indexOf("data-visitor-id=\"" + id + "\"");
        assertThat(start).as("row " + id).isPositive();
        return html.substring(start, html.indexOf("</tr>", start));
    }

    // The ⋮ menu of one booking row
    private static String menu(String html, long id) {
        String row = row(html, id);
        int start = row.indexOf("av-row-menu");
        return row.substring(start, row.indexOf("</ul>", start));
    }

    // --- Pending Approval: ready first, then the review-only "Waiting for payment receipt" group ---

    @Test
    void waitingBookingsAreListedAfterTheReadyOnesWithTheirChipDeadlineAndNoApprove() throws Exception {
        Visitor ready = paid(1, "PENDING", LATER, Visitor.PAY_PROOF_SUBMITTED, null);
        ready.setReceiptImageUrl("receipt-1.png");
        Visitor dueLater = receiptRejected(2, LATER, LocalDateTime.now().plusDays(3));
        Visitor dueSoon = awaitingReceipt(3, LATER, LocalDateTime.now().plusHours(3)); // red: within 24 hours
        when(repo.findPendingQueue()).thenReturn(new ArrayList<>(List.of(ready)));
        when(repo.findWaitingForReceipt()).thenReturn(new ArrayList<>(List.of(dueLater, dueSoon)));
        when(repo.countPendingQueue()).thenReturn(1L);
        when(repo.countWaitingForReceipt()).thenReturn(2L);

        String html = page("filter", "pending");

        assertThat(html).contains("Pending Approval<span class=\"av-tab-count\"> (1 ready · 2 awaiting receipt)</span>");
        // Ready group first, then the waiting group, soonest deadline first
        assertThat(html).containsSubsequence("Ready for approval · 1", "data-visitor-id=\"1\"",
                "data-group-head=\"awaiting\"", "Waiting for payment receipt", "2 bookings · 4 people · review only",
                "data-visitor-id=\"3\"", "data-visitor-id=\"2\"");
        for (long id : new long[] {2, 3}) {
            String row = row(html, id);
            assertThat(row).contains("data-main-action=\"waiting\"", "Waiting for receipt", "disabled")
                    .doesNotContain("action=\"/admin/approve/" + id + "\"").doesNotContain("/admin/approve-override/" + id);
            assertThat(html).containsPattern("<tr data-group=\"awaiting\" data-tags=\"awaiting\"[^>]*data-visitor-id=\"" + id + "\" class=\"av-row--waiting\"");
        }
        assertThat(row(html, 3)).contains("Awaiting receipt", "av-payby av-payby--urgent", "Pay by ");
        assertThat(row(html, 2)).contains("Receipt rejected", "Pay by ").doesNotContain("av-payby--urgent");
        // The ready row keeps its Approve (and marks the receipt paid)
        assertThat(row(html, 1)).contains("data-main-action=\"approve\"", "action=\"/admin/approve/1\"", "Approve &amp; Mark Paid")
                .doesNotContain("av-row--waiting");
        // The "Awaiting receipt" chip filters the waiting group; the other chips count ready rows only
        assertThat(html).containsPattern("data-triage=\"awaiting\">\\s*<span>Awaiting receipt</span> <span class=\"av-chip-count\">2</span>");
        assertThat(html).contains("data-triage=\"soon\" disabled=\"disabled\">");
    }

    @Test
    void thePickedVisitDateFiltersBothGroups() throws Exception {
        when(repo.findByVisitDate(LATER)).thenReturn(new ArrayList<>(List.of(v(1, "PENDING", LATER),
                awaitingReceipt(2, LATER, LocalDateTime.now().plusDays(1)), v(3, "APPROVED", LATER))));

        String html = page("filter", "pending", "dateFilter", LATER.toString());

        assertThat(html).containsSubsequence("Ready for approval · 1", "data-visitor-id=\"1\"", "Waiting for payment receipt",
                "data-visitor-id=\"2\"").doesNotContain("data-visitor-id=\"3\"");
        verify(repo, never()).findWaitingForReceipt();
        verify(repo, never()).findPendingQueue();
    }

    @Test
    void emptyStatesSayWhetherAnythingIsWaiting() throws Exception {
        when(repo.findPendingQueue()).thenReturn(new ArrayList<>());
        when(repo.findWaitingForReceipt()).thenReturn(new ArrayList<>(List.of(awaitingReceipt(5, LATER, LocalDateTime.now().plusDays(2)))));
        assertThat(page("filter", "pending")).contains("Ready for approval · 0", "No requests are ready for approval yet.",
                "Waiting for payment receipt").doesNotContain("No requests are waiting for approval.");

        when(repo.findWaitingForReceipt()).thenReturn(new ArrayList<>());
        assertThat(page("filter", "pending")).contains("No requests are waiting for approval.").doesNotContain("Ready for approval ·");
    }

    // --- Today's Manifest shows the day's waiting bookings too ---

    @Test
    void todaysManifestListsWaitingBookingsWithTheChip() throws Exception {
        when(repo.findByVisitDate(TODAY)).thenReturn(new ArrayList<>(List.of(awaitingReceipt(7, TODAY, LocalDateTime.now().plusHours(1)),
                receiptRejected(8, TODAY, LocalDateTime.now().plusHours(2)))));

        String html = page("filter", "today");

        assertThat(row(html, 7)).contains("Awaiting receipt", "Pay by ", "data-main-action=\"waiting\"");
        assertThat(row(html, 8)).contains("Receipt rejected", "data-main-action=\"waiting\"");
        assertThat(html).containsPattern("data-visitor-id=\"7\" class=\"av-row--waiting\"");
    }

    // --- Approval stays impossible without a receipt (Approve and Force Approve) ---

    @Test
    void approvingABookingWithoutItsReceiptIsRefused() throws Exception {
        Visitor noReceipt = awaitingReceipt(11, LATER, LocalDateTime.now().plusDays(1));
        Visitor pendingAwaiting = paid(12, "PENDING", LATER, Visitor.PAY_AWAITING, LocalDateTime.now().plusDays(1));
        Visitor rejected = receiptRejected(13, LATER, LocalDateTime.now().plusDays(1));
        for (Visitor v : List.of(noReceipt, pendingAwaiting, rejected)) when(repo.findById(v.getId())).thenReturn(Optional.of(v));

        mvc.perform(post("/admin/approve/11").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.AWAITING_RECEIPT_APPROVAL));
        mvc.perform(post("/admin/approve/12").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.AWAITING_RECEIPT_APPROVAL));
        mvc.perform(post("/admin/approve/13").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.RECEIPT_REJECTED_APPROVAL));
        mvc.perform(post("/admin/approve-override/12").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.AWAITING_RECEIPT_APPROVAL));
        mvc.perform(post("/admin/approve-override/13").with(csrf())).andExpect(flash().attribute("error", GuardOperationController.RECEIPT_REJECTED_APPROVAL));
        mvc.perform(post("/admin/approve-override/11").with(csrf())).andExpect(flash().attribute("error",
                org.hamcrest.Matchers.startsWith("Force Approve only applies to a pending request")));

        verify(repo, never()).save(any(Visitor.class));
        for (Visitor v : List.of(noReceipt, pendingAwaiting, rejected)) assertThat(v.getQrCodeUrl()).isNull();
    }

    // --- One main button per state ---

    @Test
    void eachStateGetsItsMainButton() {
        assertThat(v(1, "PENDING", LATER).mainAction(TODAY)).isEqualTo("approve");                                  // free
        assertThat(paid(2, "PENDING", LATER, Visitor.PAY_PROOF_SUBMITTED, null).mainAction(TODAY)).isEqualTo("approve");
        assertThat(paid(3, "APPROVED", LATER, Visitor.PAY_PROOF_SUBMITTED, null).mainAction(TODAY)).isEqualTo("markPaid");
        assertThat(paid(4, "APPROVED", LATER, Visitor.PAY_AWAITING, null).mainAction(TODAY)).isEqualTo("markPaid");
        assertThat(awaitingReceipt(5, LATER, null).mainAction(TODAY)).isEqualTo("waiting");
        assertThat(receiptRejected(6, LATER, null).mainAction(TODAY)).isEqualTo("waiting");
        assertThat(v(7, Visitor.STATUS_PAYMENT_EXPIRED, LATER).mainAction(TODAY)).isEqualTo("reinstate");
        assertThat(v(8, "CHECKED-IN", TODAY).mainAction(TODAY)).isEqualTo("deny");
        assertThat(v(9, "CHECKED-IN", TODAY.minusDays(1)).mainAction(TODAY)).isEqualTo("none");
        assertThat(v(10, "CHECKED-OUT", TODAY).mainAction(TODAY)).isEqualTo("none");
        assertThat(paid(11, "APPROVED", LATER, Visitor.PAY_PAID, null).mainAction(TODAY)).isEqualTo("none");
    }

    @Test
    void theRenderedMainButtonFollowsTheState() throws Exception {
        when(repo.findAll()).thenReturn(List.of(v(1, "PENDING", LATER), paid(3, "APPROVED", LATER, Visitor.PAY_PROOF_SUBMITTED, null),
                awaitingReceipt(5, LATER, LocalDateTime.now().plusDays(1)), v(7, Visitor.STATUS_PAYMENT_EXPIRED, LATER),
                v(8, "CHECKED-IN", TODAY), v(10, "CHECKED-OUT", TODAY)));

        String html = page("filter", "all");

        assertThat(row(html, 1)).contains("data-main-action=\"approve\"", "action=\"/admin/approve/1\"");
        assertThat(row(html, 3)).contains("data-main-action=\"markPaid\"", "data-bs-target=\"#paidModal3\"");
        assertThat(row(html, 5)).contains("data-main-action=\"waiting\"", "Waiting for receipt");
        assertThat(row(html, 7)).contains("data-main-action=\"reinstate\"", "data-reinstate-id=\"7\"", ">Reinstate</button>");
        assertThat(row(html, 8)).contains("data-main-action=\"deny\"", "data-bs-target=\"#denyModal8\"", "Deny Entry");
        String done = row(html, 10);
        assertThat(done).contains("data-main-action=\"none\"").doesNotContain("av-main-btn");
        // Only one main button per row
        for (long id : new long[] {1, 3, 5, 7, 8}) {
            assertThat(java.util.regex.Pattern.compile("av-main-btn(?![-\\w])").matcher(row(html, id)).results().count())
                    .as("main buttons in row " + id).isEqualTo(1);
        }
    }

    // --- The ⋮ menu: only what applies, destructive last ---

    @Test
    void theMenuListsOnlyTheActionsThatApplyWithTheDestructiveOnesLast() throws Exception {
        Visitor ready = paid(1, "PENDING", LATER, Visitor.PAY_PROOF_SUBMITTED, null);
        ready.setReceiptImageUrl("receipt-1.png");
        ready.setIdCardImageUrl("card-1.jpg");
        Visitor waiting = awaitingReceipt(2, LATER, LocalDateTime.now().plusDays(1));
        Visitor done = v(3, "CHECKED-OUT", TODAY);
        when(repo.findAll()).thenReturn(List.of(ready, waiting, done));

        String html = page("filter", "all");

        String readyMenu = menu(html, 1);
        assertThat(readyMenu).containsSubsequence("Details", "Reject receipt", "Force Approve", "Reject booking", "Ban visitor", "Delete")
                .doesNotContain("Resend payment link").doesNotContain("Mark as Paid").doesNotContain("Reinstate");
        String waitingMenu = menu(html, 2);
        assertThat(waitingMenu).containsSubsequence("Details", "Resend payment link", "Mark as Paid", "Reject booking", "Ban visitor", "Delete")
                .contains("action=\"/admin/payments/2/resend-link\"")
                .doesNotContain("Force Approve").doesNotContain("Reject receipt").doesNotContain("Reinstate");
        String doneMenu = menu(html, 3);
        assertThat(doneMenu).contains("Ban visitor", "Delete").doesNotContain("Reject booking").doesNotContain("Force Approve")
                .doesNotContain("Resend payment link");
        for (String m : List.of(readyMenu, waitingMenu, doneMenu)) {
            String tail = m.substring(m.indexOf("text-danger"));
            assertThat(tail).as("nothing but red items after the first red one")
                    .doesNotContain("class=\"dropdown-item\"").doesNotContain("text-warning");
            // no stray dividers: never two in a row, never one at the end
            assertThat(m).doesNotContainPattern("dropdown-divider\"></li>\\s*<li><hr class=\"dropdown-divider\"");
            assertThat(m.strip()).doesNotEndWith("<li><hr class=\"dropdown-divider\"></li>");
        }
        // Quick-view icons (with labels) only when there is something to view
        assertThat(row(html, 1)).contains("aria-label=\"View ID photo of Visitor 1\"", "aria-label=\"View receipt for billing 000001\"");
        assertThat(row(html, 2)).doesNotContain("View ID photo").doesNotContain("View receipt for billing");
        // The menu escapes the scrolling table (fixed popper strategy)
        assertThat(row(html, 1)).containsPattern("data-bs-popper-config=(\"\\{&quot;strategy&quot;:&quot;fixed&quot;\\}\"|'\\{\"strategy\":\"fixed\"\\}')");
    }

    @Test
    void rejectingAnUnpaidBookingShowsNoNonRefundableWarning() throws Exception {
        Visitor unpaid = awaitingReceipt(1, LATER, LocalDateTime.now().plusDays(1));
        Visitor receiptIn = paid(2, "PENDING", LATER, Visitor.PAY_PROOF_SUBMITTED, null);
        receiptIn.setReceiptImageUrl("receipt-2.png");
        when(repo.findAll()).thenReturn(List.of(unpaid, receiptIn));

        String html = page("filter", "all");

        assertThat(dialog(html, "rejectModal1")).doesNotContain("non-refundable").doesNotContain("confirmPaid");
        assertThat(dialog(html, "rejectModal2")).contains("This visitor has paid; payments are non-refundable.", "confirmPaid");
    }

    private static String dialog(String html, String id) {
        int start = html.indexOf("id=\"" + id + "\"");
        assertThat(start).as(id).isPositive();
        int end = html.indexOf("class=\"modal fade\"", start + 1);
        return html.substring(start, end < 0 ? html.length() : end);
    }

    @Test
    void menusAndTheirDialogsSitOutsideTheTable() throws Exception {
        when(repo.findAll()).thenReturn(List.of(awaitingReceipt(1, LATER, LocalDateTime.now().plusDays(1)),
                paid(2, "APPROVED", LATER, Visitor.PAY_PROOF_SUBMITTED, null)));

        String html = page("filter", "all");
        int tableStart = html.indexOf("id=\"visitorTable\"");
        int tableEnd = html.indexOf("</table>", tableStart);
        String table = html.substring(tableStart, tableEnd);
        String after = html.substring(tableEnd);

        assertThat(table).contains("data-bs-target=\"#rejectModal1\"", "data-bs-target=\"#paidModal1\"", "data-bs-target=\"#paidModal2\"",
                "data-bs-target=\"#rejectReceiptModal2\"").doesNotContain("class=\"modal fade\"").doesNotContain("class=\"modal ");
        assertThat(after).contains("id=\"rejectModal1\"", "id=\"paidModal1\"", "id=\"paidModal2\"", "id=\"rejectReceiptModal2\"",
                "id=\"banModal\"", "id=\"kycModal\"");
    }
}
