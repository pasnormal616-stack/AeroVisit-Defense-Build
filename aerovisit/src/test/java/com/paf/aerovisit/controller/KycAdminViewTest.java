package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

/** Admin table: one "View KYC" button feeding a shared side-by-side viewer, and the OCR name-match badge. No database. */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(username = "director", roles = "DIRECTOR") // KYC review is Director-only; guards never reach /admin
class KycAdminViewTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor v(long id, String name, String selfie, String card, boolean nameMatch) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName(name);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("Leisure");
        v.setStatus("PENDING_MANUAL_REVIEW");
        v.setVisitDate(LocalDate.now());
        v.setIdImageUrl(selfie);
        v.setIdCardImageUrl(card);
        v.setOcrNameMatch(nameMatch);
        return v;
    }

    private String page(Visitor... rows) throws Exception {
        List<Visitor> list = List.of(rows);
        when(repo.findAll()).thenAnswer(inv -> new ArrayList<>(list));
        when(repo.findByVisitDate(LocalDate.now())).thenAnswer(inv -> new ArrayList<>(list));
        return mvc.perform(get("/admin").param("filter", "all")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String row(String html, String name) {
        int start = html.lastIndexOf("<tr", html.indexOf(">" + name + "<"));
        return html.substring(start, html.indexOf("</tr>", start));
    }

    private static String button(String rowHtml) {
        Matcher m = Pattern.compile("<button[^>]*data-bs-target=\"#kycModal\"[^>]*>").matcher(rowHtml);
        return m.find() ? m.group() : null;
    }

    @Test
    void oneViewKycButtonCarriesBothPhotosForTheSharedViewer() throws Exception {
        String html = page(v(1, "Juan Dela Cruz", "selfie-1.jpg", "card-1.jpg", true));

        String b = button(row(html, "Juan Dela Cruz"));
        assertThat(b).isNotNull().contains("data-kyc-card=\"card-1.jpg\"", "data-kyc-selfie=\"selfie-1.jpg\"",
                "data-kyc-name=\"Juan Dela Cruz\"", "type=\"button\"");
        assertThat(row(html, "Juan Dela Cruz")).contains("View KYC").doesNotContain("ID Card</a>", "View Document", "target=\"_blank\"");
        // One shared viewer with both slots, not one per row
        assertThat(html.split("id=\"kycModal\"", -1)).hasSize(2);
        assertThat(html).contains("id=\"kycCardImg\"", "id=\"kycSelfieImg\"", "class=\"av-kyc-grid\"");
    }

    @Test
    void anOlderBookingWithOnlyASelfieStillOpensTheViewer() throws Exception {
        String b = button(row(page(v(2, "Maria Santos", "selfie-2.jpg", null, false)), "Maria Santos"));
        assertThat(b).contains("data-kyc-selfie=\"selfie-2.jpg\"").doesNotContain("data-kyc-card");
    }

    @Test
    void noPhotosAtAllShowsPendingIdInsteadOfAButton() throws Exception {
        String r = row(page(v(3, "Jose Reyes", null, null, false)), "Jose Reyes");
        assertThat(button(r)).isNull();
        assertThat(r).contains("Pending ID");
    }

    @Test
    void theNameBadgeIsGreenOnlyWhenOcrReadTheName() throws Exception {
        String html = page(v(1, "Juan Dela Cruz", "s1.jpg", "c1.jpg", true), v(2, "Maria Santos", "s2.jpg", "c2.jpg", false));

        assertThat(row(html, "Juan Dela Cruz")).contains("av-badge--ok", "ID Name Verified").doesNotContain("Name Unverified");
        assertThat(row(html, "Maria Santos")).contains("av-badge--warn", "Name Unverified").doesNotContain("ID Name Verified");
    }

    @Test
    void masterBookingsShowTheNameBadgeLikeEveryoneElse() throws Exception {
        // Zero-trust KYC: Master Bookings now go through OCR too, so their result is shown
        Visitor verified = v(5, "Colegio de San Juan", "s5.jpg", "c5.jpg", true);
        verified.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        Visitor unread = v(6, "Ateneo Field Trip", "s6.jpg", "c6.jpg", false);
        unread.setPurpose(Visitor.MASTER_BOOKING_PURPOSE);
        String html = page(verified, unread);

        assertThat(row(html, "Colegio de San Juan")).contains("av-badge--ok", "ID Name Verified");
        assertThat(row(html, "Ateneo Field Trip")).contains("av-badge--warn", "Name Unverified");
    }

    @Test
    void theOnlyBanInTheMenuIsBanVisitorByFace() throws Exception {
        Visitor withFace = v(7, "Juan Dela Cruz", "s7.jpg", "c7.jpg", true);
        withFace.setFaceEncoding("[0.1]");
        String html = page(withFace, v(8, "Maria Santos", "s8.jpg", null, false));

        assertThat(html).doesNotContain("Block User", "/admin/block/", "/admin/execute-block", "Biometric Ban");
        assertThat(row(html, "Juan Dela Cruz")).contains("data-bs-target=\"#banModal\"", "data-ban-url=\"/admin/biometric-ban/7\"",
                "data-ban-name=\"Juan Dela Cruz\"", "Ban Visitor</button>");
        // No face data: the item is shown disabled with the reason, so staff know why they can't ban
        assertThat(row(html, "Maria Santos")).contains("Ban Visitor (no face data)").doesNotContain("/admin/biometric-ban/8");
        // One shared dialog: reason + 30 days (default) / 6 months / permanent; no form action until a row is chosen
        assertThat(html.split("id=\"banModal\"", -1)).hasSize(2);
        assertThat(html).containsPattern("<form id=\"banForm\" method=\"post\">")
            .containsSubsequence("name=\"duration\"", "value=\"30_DAYS\" selected>30 days", "value=\"6_MONTHS\">6 months", "value=\"PERMANENT\">Permanent");
    }

    @Test
    void aVisitorNameWithMarkupIsEscapedInTheButton() throws Exception {
        String b = button(row(page(v(4, "<b>X</b>", "s.jpg", "c.jpg", false)), "&lt;b&gt;X&lt;/b&gt;"));
        assertThat(b).contains("data-kyc-name=\"&lt;b&gt;X&lt;/b&gt;\"");
    }
}
