package com.paf.aerovisit.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

/** Pending Approval tab: grouped by date with seat headers and chips, VERIFIED actionable, and Approve / Reject return
 *  to the officer's Pending view (only that view; anything else still goes to /admin). No database. */
@WebMvcTest({GuardOperationController.class, ManagementController.class, AdminDashboardController.class})
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR")
class PendingApprovalViewTest {

    private static final String AM = "Morning (08:00 AM - 12:00 PM)";
    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private MuseumEmployeeRepository employeeRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private PredictiveAdvisorService aiService;
    @MockitoBean private TimeWindowConfigRepository timeWindowRepo;
    @MockitoBean private MuseumClosureRepository closureRepo;

    private static Visitor v(long id, String status, LocalDate date, int size) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("Leisure");
        v.setStatus(status);
        v.setVisitDate(date);
        v.setTimeWindow(AM);
        v.setGroupSize(size);
        v.setOcrNameMatch(true);
        return v;
    }

    private static BookingSlot slot(LocalDate date, int max) {
        BookingSlot s = new BookingSlot();
        s.setSlotDate(date);
        s.setTimeWindow(AM);
        s.setMaxCapacity(max);
        return s;
    }

    private String pendingPage(String... params) throws Exception {
        var request = get("/admin").param("filter", "pending");
        for (int i = 0; i + 1 < params.length; i += 2) request.param(params[i], params[i + 1]);
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    @Test
    void pendingTabReadsOnlyRequestsAwaitingReviewNeverTheWholeTable() throws Exception {
        when(repo.findPendingQueue()).thenReturn(new ArrayList<>(List.of(v(1, "PENDING", TODAY, 1))));
        pendingPage();
        verify(repo).findPendingQueue();
        verify(repo, never()).findAll();
    }

    @Test
    void requestsAreGroupedByDateWithSeatsLeftChipsAndLaterDatesCollapsed() throws Exception {
        LocalDate later = TODAY.plusDays(6);
        Visitor today4 = v(2, "PENDING", TODAY, 4);
        when(repo.findPendingQueue()).thenReturn(new ArrayList<>(List.of(
            v(1, "PENDING", later, 2), today4, v(3, "PENDING", TODAY.minusDays(1), 1))));
        when(bookingRepo.findAll()).thenReturn(List.of(slot(TODAY, 10), slot(later, 30)));
        // 7 of today's 10 morning seats are already approved and the pending group of 4 holds its own seats too:
        // 11 held for 10, a seat conflict (a pending request now counts in the seats held, so it can't fit "again")
        when(repo.findByVisitDateBetweenAndStatusIn(TODAY, later, Visitor.SEAT_HOLDING_STATUSES))
            .thenReturn(List.of(v(9, "APPROVED", TODAY, 7), today4));

        String html = pendingPage();
        assertThat(html).containsSubsequence(
            "data-group-head=\"overdue\"", "Overdue: visit date has passed", "<span>Visitor 3</span>",
            "data-group-head=\"" + TODAY + "\"", "Today · ", "1 request · 4 people", "Morning FULL", "<span>Visitor 2</span>",
            "data-group-head=\"" + later + "\"", "Morning 30 left", "<span>Visitor 1</span>");
        // later dates start collapsed (server-side, so nothing flashes before the script runs); no tags = no data-tags
        assertThat(html).containsPattern("<tr data-group=\"" + later + "\" data-search=\"[^\"]*\" data-visitor-id=\"\\d+\" hidden=\"hidden\">");
        assertThat(html).containsPattern("<tr data-group=\"" + TODAY + "\" data-tags=\"conflict soon\" data-search=\"[^\"]*\" data-visitor-id=\"\\d+\">");
        // chips with counts, and the Pending tab skips DataTables (it would page through the date headers)
        assertThat(html).contains("data-triage=\"conflict\"", "Seat conflict", "data-triage-search",
            "if (document.querySelector('#visitorTable [data-group-toggle], #visitorTable .av-empty-row')) return;");
    }

    @Test
    void emptyQueueSaysSoInsteadOfAnEmptyTable() throws Exception {
        when(repo.findPendingQueue()).thenReturn(new ArrayList<>());
        assertThat(pendingPage()).contains("No requests are waiting for approval.").doesNotContain("data-triage=\"manual\"");
    }

    @Test
    void verifiedRequestsGetTheBadgeApproveAndRejectButNotForceApprove() throws Exception {
        when(repo.findPendingQueue()).thenReturn(new ArrayList<>(List.of(v(4, "VERIFIED", TODAY, 1))));
        String html = pendingPage();
        assertThat(html).contains("Pending Review", "action=\"/admin/approve/4\"", "data-bs-target=\"#rejectModal4\"")
            .doesNotContain("/admin/approve-override/4");
    }

    @Test
    void approveAndRejectFormsCarryTheOfficersPendingView() throws Exception {
        when(repo.findPendingQueue()).thenReturn(new ArrayList<>());
        when(repo.findByVisitDate(TODAY)).thenReturn(new ArrayList<>(List.of(v(5, "PENDING", TODAY, 1))));
        String html = pendingPage("sort", "desc", "dateFilter", TODAY.toString(), "capacityDate", TODAY.toString());
        String back = "/admin?filter=pending&amp;sort=desc&amp;dateFilter=" + TODAY + "&amp;capacityDate=" + TODAY;
        // Approve, Force Approve and the reject dialog each carry it
        assertThat(html.split(java.util.regex.Pattern.quote("name=\"returnTo\" value=\"" + back + "\""), -1).length - 1).isEqualTo(3);
        // Other tabs render no returnTo field: shell.js adds the current address when one of their forms is submitted
        when(repo.findByVisitDate(TODAY)).thenReturn(new ArrayList<>(List.of(v(5, "PENDING", TODAY, 1))));
        assertThat(mvc.perform(get("/admin")).andReturn().getResponse().getContentAsString()).doesNotContain("name=\"returnTo\"");
    }

    @Test
    void approveAndRejectGoBackToThePendingViewOnlyWhenItIsOneOfOurs() throws Exception {
        String back = "/admin?filter=pending&sort=asc&dateFilter=2026-10-20&capacityDate=2026-10-20";
        when(repo.findById(77L)).thenReturn(Optional.empty()); // unknown id: nothing changes, only the redirect is tested
        mvc.perform(post("/admin/approve/77").with(csrf()).param("returnTo", back)).andExpect(redirectedUrl(back));
        mvc.perform(post("/admin/approve-override/77").with(csrf()).param("returnTo", back)).andExpect(redirectedUrl(back));
        mvc.perform(post("/admin/reject-visitor").with(csrf()).param("id", "77").param("presetReason", "Other").param("returnTo", back))
            .andExpect(redirectedUrl(back));

        // ↩️ Keep my place: the other dashboard tabs and the Guard Dashboard are accepted too (shell.js sends them)
        for (String ours : new String[] {"/admin", "/admin?filter=all", "/admin?filter=today&range=week", "/guard/dashboard",
                "/guard/dashboard?tab=inside", "/guard/dashboard?tab=expected&date=2026-10-20"}) {
            mvc.perform(post("/admin/approve/77").with(csrf()).param("returnTo", ours)).andExpect(redirectedUrl(ours));
        }
        for (String bad : new String[] {"https://evil.example/admin?filter=pending", "//evil.example", "/admin/settings",
                "/admin?filter=pending&sort=asc&next=//evil", "/admin?filter=pending\r\nX-Evil: 1", "", "/admin?filter=all%0d%0a",
                "/guard/dashboard?tab=x&next=//evil", "/admin?filter=<script>", "/admin/../logout", "/guard/dashboard/../../x"}) {
            mvc.perform(post("/admin/approve/77").with(csrf()).param("returnTo", bad)).andExpect(redirectedUrl("/admin"));
        }
        mvc.perform(post("/admin/approve/77").with(csrf())).andExpect(redirectedUrl("/admin")); // no field: as before
    }

    // --- 💳 One tab: Pending Approval only (no "Awaiting Payment Verification" tab) ---

    @Test
    void theOldPaymentTabAddressOpensPendingApproval() throws Exception {
        mvc.perform(get("/admin").param("filter", "payment")).andExpect(redirectedUrl("/admin?filter=pending"));
        when(repo.findPendingQueue()).thenReturn(new ArrayList<>());
        assertThat(pendingPage()).doesNotContain("Awaiting Payment Verification", "filter=payment");
    }

    @Test
    void aRowWithItsReceiptShowsTheIdPhotoReceiptBillingNumberAndAmountAndApproveMarksPaid() throws Exception {
        Visitor paid = v(21, "PENDING", TODAY.plusDays(2), 3);
        paid.setIdCardImageUrl("photo-21.jpg");
        paid.setDiscountedCount(1);
        paid.setRegularCount(2);
        paid.setFreeCount(0);
        paid.setDiscountedPriceCentavos(10_000L);
        paid.setRegularPriceCentavos(15_000L);
        paid.setAdmissionTotalCentavos(40_000L);
        paid.setBillingNumber("000555");
        paid.setPaymentStatus(Visitor.PAY_PROOF_SUBMITTED);
        paid.setReceiptImageUrl("receipt-21.jpg");
        Visitor free = v(22, "PENDING", TODAY.plusDays(2), 2);
        free.setDiscountedCount(0);
        free.setRegularCount(0);
        free.setFreeCount(2);
        free.setAdmissionTotalCentavos(0L);
        free.setPaymentStatus(Visitor.PAY_NOT_REQUIRED);
        when(repo.findPendingQueue()).thenReturn(new ArrayList<>(List.of(paid, free)));

        String html = pendingPage();
        assertThat(html).contains("src=\"/uploads/photo-21.jpg\"", "src=\"/admin/payments/21/receipt\"", "000555", "PHP 400.00",
                "1 Discounted · 2 Regular", "Approve &amp; Mark Paid", "action=\"/admin/payments/21/reject\"", "3 visitors · ₱400.00", "Proof submitted", "2 visitors · Free");
        assertThat(html).doesNotContain("ID verified", "ID unverified", "Possible duplicate");
    }

    @Test
    void bookingsWaitingForAReceiptAreNotListed() throws Exception {
        Visitor awaiting = v(31, Visitor.STATUS_AWAITING_RECEIPT, TODAY.plusDays(2), 1);
        awaiting.setAdmissionTotalCentavos(15_000L);
        awaiting.setPaymentStatus(Visitor.PAY_AWAITING);
        Visitor rejected = v(32, "PENDING", TODAY.plusDays(2), 1);
        rejected.setAdmissionTotalCentavos(15_000L);
        rejected.setPaymentStatus(Visitor.PAY_REJECTED);
        Visitor ready = v(33, "PENDING", TODAY.plusDays(2), 1);
        // even if the query returned them, the tab lists only what is ready for review
        when(repo.findPendingQueue()).thenReturn(new ArrayList<>(List.of(awaiting, rejected, ready)));

        String html = pendingPage();
        assertThat(html).contains("<span>Visitor 33</span>").doesNotContain("<span>Visitor 31</span>", "<span>Visitor 32</span>");
    }
}
