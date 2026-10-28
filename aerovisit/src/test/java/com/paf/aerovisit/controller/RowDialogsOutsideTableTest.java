package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * 📱 Mobile: the Reject and Deny Entry dialogs must sit outside the visitor table and its panel. Inside the
 * sideways-scrolling table, phones (iOS Safari) draw the dialog under the dark backdrop, so nothing can be tapped.
 * No database.
 */
@WebMvcTest(AdminDashboardController.class)
@Import(SecurityConfig.class)
@WithMockUser(roles = "DIRECTOR")
class RowDialogsOutsideTableTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private PredictiveAdvisorService aiService;

    private static Visitor visitor(long id, String status) {
        Visitor v = new Visitor();
        v.setId(id);
        v.setName("Visitor " + id);
        v.setEmail("v" + id + "@example.com");
        v.setPurpose("Leisure");
        v.setStatus(status);
        v.setVisitDate(LocalDate.now());
        return v;
    }

    @Test
    void rejectAndDenyEntryDialogsAreRenderedAfterTheVisitorTable() throws Exception {
        when(repo.findAll()).thenReturn(List.of(visitor(1, "PENDING"), visitor(2, "CHECKED-IN")));

        String html = mvc.perform(get("/admin").param("filter", "all")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        int tableStart = html.indexOf("id=\"visitorTable\"");
        int tableEnd = html.indexOf("</table>", tableStart);
        assertThat(tableStart).isPositive();
        String table = html.substring(tableStart, tableEnd);
        String afterTable = html.substring(tableEnd);

        // The row buttons stay in the table and point at the dialogs by id
        assertThat(table).contains("data-bs-target=\"#rejectModal1\"", "data-bs-target=\"#denyModal2\"");
        // ...but no dialog markup is inside the table any more
        assertThat(table).doesNotContain("class=\"modal fade\"");
        assertThat(afterTable).contains("id=\"rejectModal1\"", "id=\"rejectModal2\"", "id=\"denyModal2\"");
        assertThat(afterTable).doesNotContain("id=\"denyModal1\""); // Deny Entry only exists for a checked-in visitor
        // Outside the panel too: the panel closes before the first dialog
        assertThat(afterTable.indexOf("id=\"rejectModal1\"")).isGreaterThan(afterTable.indexOf("</div>"));
        // The forms inside still post the right visitor
        assertThat(afterTable).contains("action=\"/admin/reject-visitor\"", "name=\"id\" value=\"1\"",
                "action=\"/admin/deny-entry\"", "name=\"visitorId\" value=\"2\"");
    }
}
