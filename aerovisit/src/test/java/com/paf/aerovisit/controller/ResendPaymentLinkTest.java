package com.paf.aerovisit.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.paf.aerovisit.TestImages;
import com.paf.aerovisit.config.SecurityConfig;
import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.BillingNumberService;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.NotificationService;
import com.paf.aerovisit.service.PaymentWorkflow;
import com.paf.aerovisit.service.ReceiptStorage;

/**
 * ✉️ "Resend payment link" (Director, ⋮ menu of a row waiting for its receipt): the same email as at booking, at most
 * once every 10 minutes per booking; and a waiting booking moves to the ready group once its receipt is uploaded.
 */
@WebMvcTest({PaymentAdminController.class, PaymentController.class})
@Import({SecurityConfig.class, PaymentWorkflow.class, ReceiptStorage.class})
class ResendPaymentLinkTest {

    private static final RequestPostProcessor DIRECTOR = user("director").roles("DIRECTOR");
    private static final RequestPostProcessor GUARD = user("guard").roles("GUARD");
    private static final long ID = 990_801L;

    @Autowired private MockMvc mvc;

    @MockitoBean private VisitorRepository repo;
    @MockitoBean private EmailService emailService;
    @MockitoBean private BillingNumberService billingNumbers;
    @MockitoBean private NotificationService notificationService;
    @MockitoBean private PortalSettingsRepository settingsRepo;

    private Visitor booking;

    @BeforeEach
    void waitingBooking() {
        booking = new Visitor();
        booking.setId(ID);
        booking.setName("Juan Dela Cruz");
        booking.setEmail("juan@example.com");
        booking.setStatus(Visitor.STATUS_AWAITING_RECEIPT);
        booking.setVisitDate(LocalDate.now().plusDays(5));
        booking.setTimeWindow("Morning (08:00 AM - 12:00 PM)");
        booking.setGroupSize(2);
        booking.setRegularCount(2);
        booking.setDiscountedCount(0);
        booking.setFreeCount(0);
        booking.setRegularPriceCentavos(15_000L);
        booking.setAdmissionTotalCentavos(30_000L);
        booking.setBillingNumber("000321");
        booking.setPaymentToken("pay-token-321");
        booking.setPaymentStatus(Visitor.PAY_AWAITING);
        booking.setPaymentDueAt(LocalDateTime.now().plusDays(1));
        when(repo.findById(ID)).thenReturn(Optional.of(booking));
        when(repo.findByPaymentToken("pay-token-321")).thenReturn(Optional.of(booking));
        when(repo.save(any(Visitor.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.claimPaymentLinkResend(eq(ID), any(), any())).thenReturn(1);
        when(settingsRepo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
    }

    @AfterEach
    void removeReceipt() throws Exception {
        if (booking.getReceiptImageUrl() != null) Files.deleteIfExists(Paths.get("uploads", booking.getReceiptImageUrl()));
    }

    private org.springframework.test.web.servlet.ResultActions resend(RequestPostProcessor who) throws Exception {
        return mvc.perform(post("/admin/payments/" + ID + "/resend-link").with(csrf()).with(who));
    }

    @Test
    void resendEmailsTheSameBillingNumberAmountDeadlineAndPaymentLink() throws Exception {
        resend(DIRECTOR).andExpect(redirectedUrl(PaymentAdminController.RECEIPT_RETURN))
                .andExpect(flash().attribute("success", startsWith("Payment link sent again to juan@example.com (billing 000321")));

        ArgumentCaptor<EmailService.PaymentNotice> notice = ArgumentCaptor.forClass(EmailService.PaymentNotice.class);
        verify(emailService).sendPaymentRequest(notice.capture());
        assertThat(notice.getValue().to()).isEqualTo("juan@example.com");
        assertThat(notice.getValue().billingNumber()).isEqualTo("000321");
        assertThat(notice.getValue().amount()).isEqualTo(booking.amountDueText());
        assertThat(notice.getValue().deadline()).isEqualTo(PaymentWorkflow.deadlineText(booking.getPaymentDueAt()));
        assertThat(notice.getValue().paymentToken()).isEqualTo("pay-token-321");
        assertThat(notice.getValue().approved()).isFalse(); // the pay-first wording, as at booking
        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repo).claimPaymentLinkResend(eq(ID), now.capture(), cutoff.capture());
        assertThat(java.time.Duration.between(cutoff.getValue(), now.getValue())).hasMinutes(10);
    }

    @Test
    void aReceiptRejectedBookingCanGetTheLinkAgainToo() throws Exception {
        booking.setStatus("PENDING");
        booking.setPaymentStatus(Visitor.PAY_REJECTED);
        resend(DIRECTOR).andExpect(flash().attribute("success", startsWith("Payment link sent again")));
        verify(emailService).sendPaymentRequest(any());
    }

    @Test
    void aSecondResendWithinTenMinutesIsRefusedAndSendsNothing() throws Exception {
        when(repo.claimPaymentLinkResend(eq(ID), any(), any())).thenReturn(1, 0);
        resend(DIRECTOR).andExpect(flash().attribute("success", startsWith("Payment link sent again")));
        resend(DIRECTOR).andExpect(flash().attribute("error", PaymentAdminController.RESEND_TOO_SOON));
        verify(emailService, org.mockito.Mockito.times(1)).sendPaymentRequest(any());
    }

    @Test
    void aPassedDeadlineOrABookingNotWaitingIsRefused() throws Exception {
        booking.setPaymentDueAt(LocalDateTime.now().minusMinutes(1));
        resend(DIRECTOR).andExpect(flash().attribute("error", startsWith("The payment deadline has already passed")));

        booking.setPaymentDueAt(LocalDateTime.now().plusDays(1));
        booking.setStatus("PENDING");
        booking.setPaymentStatus(Visitor.PAY_PROOF_SUBMITTED);
        resend(DIRECTOR).andExpect(flash().attribute("error", "This booking is not waiting for a payment receipt."));

        verify(repo, never()).claimPaymentLinkResend(anyLong(), any(), any());
        verify(emailService, never()).sendPaymentRequest(any());
    }

    @Test
    void aGuardGets403() throws Exception {
        resend(GUARD).andExpect(status().isForbidden());
        verify(repo, never()).claimPaymentLinkResend(anyLong(), any(), any());
        verify(emailService, never()).sendPaymentRequest(any());
    }

    // --- After the receipt upload the booking is in "Ready for approval" ---

    @Test
    void afterTheReceiptUploadTheBookingMovesToTheReadyGroup() throws Exception {
        assertThat(booking.waitingForReceipt()).isTrue();
        assertThat(booking.inPendingQueue()).isFalse();

        mvc.perform(multipart("/payment/proof").file(new MockMultipartFile("receipt", "r.png", "image/png", TestImages.png(30)))
                .param("token", "pay-token-321").with(csrf()));

        assertThat(booking.getStatus()).isEqualTo("PENDING");
        assertThat(booking.getPaymentStatus()).isEqualTo(Visitor.PAY_PROOF_SUBMITTED);
        assertThat(booking.waitingForReceipt()).isFalse();
        assertThat(booking.inPendingQueue()).isTrue(); // the Pending tab's ready group (findPendingQueue / inPendingQueue)
        assertThat(booking.mainAction(LocalDate.now())).isEqualTo("approve");
    }
}
