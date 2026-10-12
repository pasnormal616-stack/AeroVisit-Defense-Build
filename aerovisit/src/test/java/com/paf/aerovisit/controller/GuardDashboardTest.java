package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * Guard Dashboard (least privilege): only today's APPROVED / CHECKED-IN / CHECKED-OUT visitors, only the gate actions
 * (Deny Entry, Check-Out, Ban), and a Guard opening /admin lands here. No database.
 */
@WebMvcTest({GuardDashboardController.class, AdminDashboardController.class})
@Import(SecurityConfig.class)
@WithMockUser(username = "guard", roles = "GUARD")
class GuardDashboardTest {

    private static final LocalDate TODAY = LocalDate.now();
    private static final String FACE = "[0.1,0.2]";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor v(long id, String name, String status, String window) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName(name);
        v.setEmail(name.toLowerCase().replace(' ', '.') + "@example.com");
        v.setContactNumber("0917555" + String.format("%04d", id));
        v.setReferenceId("PAF-GATE" + id);
        v.setStatus(status);
        v.setVisitDate(TODAY);
        v.setTimeWindow(window);
        v.setGroupSize(1);
        v.setFaceEncoding(FACE);
        v.setIdImageUrl("selfie-" + id + ".jpg");
        v.setIdCardImageUrl("card-" + id + ".jpg");
        return v;
    }

    private static final String MORNING = "Morning (08:00 AM - 12:00 PM)";
    private static final String AFTERNOON = "Afternoon (01:00 PM - 05:00 PM)";

    // Today's bookings in every status, plus one visitor still inside
    private void stubToday() {
        Visitor left = v(6, "Left Visitor", "CHECKED-OUT", MORNING);
        left.setCheckInTime(LocalDateTime.now().minusHours(3));
        left.setCheckOutTime(LocalDateTime.now().minusHours(1));
        when(repo.findByVisitDate(TODAY)).thenReturn(new ArrayList<>(List.of(
                v(1, "Pending Person", "PENDING", MORNING),
                v(2, "Review Person", "PENDING_MANUAL_REVIEW", MORNING),
                v(3, "Afternoon Arrival", "APPROVED", AFTERNOON),
                v(4, "Morning Arrival", "APPROVED", MORNING),
                v(5, "Rejected Person", "REJECTED", MORNING),
                left,
                v(7, "Denied Person", "DENIED_ENTRY", MORNING))));
        Visitor inside = v(8, "Inside Visitor", "CHECKED-IN", MORNING);
        inside.setCheckInTime(LocalDateTime.now().minusMinutes(30));
        inside.setGroupSize(3);
        when(repo.findByStatus("CHECKED-IN")).thenReturn(new ArrayList<>(List.of(inside)));
    }

    private String page(String tab) throws Exception {
        stubToday();
        return mvc.perform(get("/guard/dashboard").param("tab", tab))
                .andExpect(status().isOk()).andExpect(view().name("guard"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String tbody(String html) {
        return html.substring(html.indexOf("<tbody>"), html.indexOf("</tbody>"));
    }

    // --- Scope: only the three gate statuses ---

    @Test
    void expectedArrivalsAreOnlyTodaysApprovedPassesInTimeWindowOrder() throws Exception {
        String rows = tbody(page("expected"));

        assertThat(rows).containsSubsequence("Morning Arrival", "Afternoon Arrival");
        assertThat(rows).doesNotContain("Pending Person", "Review Person", "Rejected Person", "Denied Person", "Left Visitor", "Inside Visitor");
    }

    @Test
    void theTabsCountEachGroup() throws Exception {
        String html = page("expected");
        assertThat(html).contains("Expected Arrivals <span class=\"count\">(2)</span>", "Inside Now <span class=\"count\">(1)</span>",
                "Left Today <span class=\"count\">(1)</span>");
        assertThat(html).contains("Inside <b class=\"text-dark\">3</b>"); // headcount, the group of 3
    }

    @Test
    void insideNowListsOnlyCheckedInVisitors() throws Exception {
        String rows = tbody(page("inside"));
        assertThat(rows).contains("Inside Visitor", "Group of 3").doesNotContain("Morning Arrival", "Left Visitor");
    }

    @Test
    void leftTodayListsOnlyCheckedOutVisitors() throws Exception {
        String rows = tbody(page("left"));
        assertThat(rows).contains("Left Visitor", "In ", "Out ").doesNotContain("Morning Arrival", "Inside Visitor");
    }

    @Test
    void anUnknownTabFallsBackToExpectedArrivals() throws Exception {
        stubToday();
        mvc.perform(get("/guard/dashboard").param("tab", "all")).andExpect(model().attribute("tab", "expected"));
    }

    // --- Restrictions: no approvals, no KYC, no contact details ---

    @Test
    void noBookingReviewNoKycAndNoContactDetailsOnAnyTab() throws Exception {
        for (String tab : List.of("expected", "inside", "left")) {
            String html = page(tab);
            assertThat(html).as(tab).doesNotContain("/admin/approve", "/admin/reject", ">Approve<", ">Reject<", "Force Approve", "kycModal", "View KYC",
                    "/admin/delete", "@example.com", "0917555", "selfie-", "card-", "/uploads/");
            // Sidebar and bell: the Gate Dashboard instead of the full manifest, and no pending-requests link
            assertThat(html).as(tab).contains("href=\"/guard/dashboard\" class=\"av-nav-link is-active\"")
                    .doesNotContain("Visitor Dashboard", "/admin?filter=pending\"", "/admin/analytics");
        }
    }

    // --- Actions: Deny Entry (arrivals), Check-Out / Evict (inside), Ban (all three) ---

    @Test
    void anExpectedArrivalCanBeDeniedOrBannedButNotCheckedOutHere() throws Exception {
        String rows = tbody(page("expected"));
        assertThat(rows).contains("data-deny-id=\"4\"", "data-deny-name=\"Morning Arrival\"", "data-ban-url=\"/admin/biometric-ban/4\"")
                .doesNotContain("/admin/manual-checkin");
        String html = page("expected");
        assertThat(html).contains("<form id=\"denyForm\" action=\"/admin/deny-entry\" method=\"post\">", "id=\"banModal\"");
    }

    @Test
    void someoneInsideCanBeCheckedOutOrBannedButNotDenied() throws Exception {
        String rows = tbody(page("inside"));
        assertThat(rows).contains("action=\"/admin/manual-checkin\"", "name=\"referenceId\" value=\"PAF-GATE8\"", "Check Out / Evict",
                "data-ban-url=\"/admin/biometric-ban/8\"").doesNotContain("data-deny-id");
    }

    @Test
    void someoneWhoLeftCanOnlyBeBanned() throws Exception {
        String rows = tbody(page("left"));
        assertThat(rows).contains("data-ban-url=\"/admin/biometric-ban/6\"").doesNotContain("data-deny-id", "/admin/manual-checkin");
    }

    @Test
    void withoutFaceDataTheBanButtonIsDisabled() throws Exception {
        Visitor noFace = v(9, "No Face", "APPROVED", MORNING);
        noFace.setFaceEncoding(null);
        when(repo.findByVisitDate(TODAY)).thenReturn(new ArrayList<>(List.of(noFace)));

        String rows = tbody(mvc.perform(get("/guard/dashboard")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(rows).doesNotContain("data-ban-url").contains("disabled", "No face data on file");
    }

    @Test
    void namesAreEscaped() throws Exception {
        Visitor tricky = v(10, "Eve <script>x</script>", "APPROVED", MORNING);
        when(repo.findByVisitDate(TODAY)).thenReturn(new ArrayList<>(List.of(tricky)));

        String rows = tbody(mvc.perform(get("/guard/dashboard")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(rows).doesNotContain("<script>x</script>").contains("Eve &lt;script&gt;x&lt;/script&gt;");
    }

    @Test
    void anEmptyTabSaysSo() throws Exception {
        when(repo.findByVisitDate(TODAY)).thenReturn(new ArrayList<>());
        String html = mvc.perform(get("/guard/dashboard").param("tab", "inside")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("Nobody is inside right now.");
    }

    // --- A Guard on /admin lands here, with the message of the action that sent them there ---

    @Test
    void aGuardOpeningTheFullManifestIsSentHereWithoutLoadingIt() throws Exception {
        mvc.perform(get("/admin").param("filter", "all")).andExpect(redirectedUrl("/guard/dashboard"));
        verify(repo, never()).findAll();
        verify(repo, never()).findByVisitDate(any());
    }

    @Test
    void theMessageOfAGateActionSurvivesTheRedirect() throws Exception {
        mvc.perform(get("/admin").flashAttr("success", "👋 CHECKED-OUT via Manual Override: Juan").flashAttr("error", "x"))
            .andExpect(redirectedUrl("/guard/dashboard"))
            .andExpect(flash().attribute("success", "👋 CHECKED-OUT via Manual Override: Juan"))
            .andExpect(flash().attribute("error", "x"));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void theDirectorStillGetsTheFullManifest() throws Exception {
        when(repo.findByVisitDate(TODAY)).thenReturn(new ArrayList<>());
        mvc.perform(get("/admin")).andExpect(status().isOk()).andExpect(view().name("admin"));
    }

    @Test
    @WithMockUser(username = "director", roles = "DIRECTOR")
    void theDirectorCanOpenTheGateDashboardToo() throws Exception {
        String html = page("expected");
        assertThat(html).contains("Morning Arrival").contains("Visitor Dashboard"); // the Director's own sidebar
    }
}
