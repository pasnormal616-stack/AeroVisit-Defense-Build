package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.OcrService;
import com.paf.aerovisit.service.PredictiveAdvisorService;

/**
 * "Get directions": a Google Maps link to the museum in the chat window and the footer. Google Maps uses the visitor's
 * own location on their device; the link carries only the museum as the destination, nothing about the visitor. No database.
 */
@WebMvcTest(VisitorPortalController.class)
@Import(SecurityConfig.class)
class DirectionsLinkTest {

    static final String DIRECTIONS = "https://www.google.com/maps/dir/?api=1&amp;destination=Philippine+Air+Force+Aerospace+Museum,+Villamor+Air+Base,+Pasay+City";

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private OcrService ocrService;
    @MockitoBean private PredictiveAdvisorService aiService;

    private String page(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void theChatWindowHasAGetDirectionsLinkAboveTheMessageBox() throws Exception {
        assertThat(page("/")).containsSubsequence("<div id=\"chat-body\">",
            "<a class=\"chat-directions\" id=\"chat-directions\" href=\"" + DIRECTIONS + "\" target=\"_blank\" rel=\"noopener noreferrer\">",
            "Get directions to the museum</a>", "<div class=\"chat-input-area\">");
    }

    @Test
    void theFooterAddressHasTheSameLinkOnEveryVisitorPage() throws Exception {
        for (String path : new String[] {"/", "/manage-booking"}) {
            String html = page(path);
            String footer = html.substring(html.indexOf("<footer"), html.indexOf("</footer>"));
            assertThat(footer).as(path).containsSubsequence("Villamor Air Base, Pasay City, Metro Manila.",
                "<a class=\"vp-footer-directions\" href=\"" + DIRECTIONS + "\" target=\"_blank\" rel=\"noopener noreferrer\">", "Get directions</a>");
        }
    }

    @Test
    void theLinkCarriesOnlyTheMuseumAsDestinationNoVisitorLocation() {
        assertThat(DIRECTIONS).contains("destination=").doesNotContain("origin=").doesNotContain("geolocation");
    }
}
