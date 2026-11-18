package com.paf.aerovisit.controller;

import com.paf.aerovisit.FixedClock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.repository.BlacklistedFaceRepository;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.StaffScheduleRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.BillingNumberService;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.ReceiptStorage;
import com.paf.aerovisit.service.WalkInService;

/**
 * 🔑 Security fix M2, staff side: the Guard Dashboard shows today's walk-in code to post at the gate, and only the
 * Director can replace it (when it leaks), which makes the old code stop working at once. No database.
 */
@WebMvcTest({GuardOperationController.class, PaymentController.class, PaymentAdminController.class, GuardDashboardController.class})
@Import({FixedClock.class, SecurityConfig.class, PaymentWorkflow.class, ReceiptStorage.class, WalkInService.class})
@TestPropertySource(properties = "aerovisit.walk-in.code-required=true")
class WalkInCodeStaffTest {

    private static final RequestPostProcessor DIRECTOR = user("director").roles("DIRECTOR");
    private static final RequestPostProcessor GUARD = user("guard").roles("GUARD");

    @Autowired private MockMvc mvc;
    @Autowired private WalkInService walkInService;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private StaffScheduleRepository staffRepo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BookingSlotRepository bookingRepo;
    @MockitoBean private BillingNumberService billingNumbers;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private PortalSettingsRepository settingsRepo;
    @MockitoBean private BlacklistedFaceRepository faceBanRepo;

    @Test
    void theGuardDashboardShowsTodaysCode() throws Exception {
        String code = walkInService.todaysCode(FixedClock.TODAY);
        mvc.perform(get("/guard/dashboard").with(GUARD))
           .andExpect(status().isOk())
           .andExpect(content().string(Matchers.allOf(
                   Matchers.containsString("id=\"walkInCodeChip\""), Matchers.containsString(code))));
    }

    @Test
    void theDirectorReplacesTheCodeAndTheOldOneStopsWorking() throws Exception {
        String old = walkInService.todaysCode(FixedClock.TODAY);
        mvc.perform(post("/admin/settings/walk-in/new-code").with(csrf()).with(DIRECTOR))
           .andExpect(redirectedUrl("/admin/settings#walkInSettings"))
           .andExpect(flash().attribute("success", Matchers.startsWith("New walk-in code for today: ")));
        String fresh = walkInService.todaysCode(FixedClock.TODAY);
        assertThat(fresh).isNotEqualTo(old);
        assertThat(walkInService.codeError(old, FixedClock.TODAY)).isEqualTo(WalkInService.CODE_WRONG_MESSAGE);
        assertThat(walkInService.codeError(fresh, FixedClock.TODAY)).isNull();
        verify(settingsRepo, org.mockito.Mockito.atLeastOnce()).save(any(PortalSettings.class));
    }

    @Test
    void aGuardCannotReplaceTheCode() throws Exception {
        String old = walkInService.todaysCode(FixedClock.TODAY);
        org.mockito.Mockito.clearInvocations(settingsRepo);
        mvc.perform(post("/admin/settings/walk-in/new-code").with(csrf()).with(GUARD)).andExpect(status().isForbidden());
        assertThat(walkInService.todaysCode(FixedClock.TODAY)).isEqualTo(old);
        verify(settingsRepo, never()).save(any());
    }
}
