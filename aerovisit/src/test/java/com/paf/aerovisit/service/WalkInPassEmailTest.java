package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.paf.aerovisit.QrCodes;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.service.RecordingMailSender.SentEmail;

/**
 * 🚶 The walk-in gate QR by email: the walk-in payment email (also sent again by "Resend payment link") and the new free
 * walk-in pass email embed the booking's secure token as a QR (cid:entryQr, also attached) under "Show this QR to the
 * guard at the gate". Advance-booking payment emails carry no QR.
 */
class WalkInPassEmailTest {

    private static final String TOKEN = "3f1c2d9e-7b1a-4c55-9f0e-2a6b8d4c1e77";
    private static final String LABEL = "Show this QR to the guard at the gate";

    private RecordingMailSender mail;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mail = new RecordingMailSender();
        emailService = new EmailService(mail, "https://museum.example/");
        emailService.mailPassword = EmailServiceTest.PASSWORD;
    }

    private SentEmail only() {
        assertThat(mail.sent).hasSize(1);
        return mail.sent.get(0);
    }

    private static EmailService.PaymentNotice notice(boolean walkIn, String qrToken) {
        return new EmailService.PaymentNotice("v@example.com", "Juan", "PAF-WALK01", "000777", "PHP 300.00", "2 Regular",
                "Thu, Nov 5, 2026 at 12:30 PM", "pay-tok", null, walkIn, walkIn, null, qrToken);
    }

    private static void assertCarriesTheGateQr(SentEmail email) {
        assertThat(email.html()).contains(LABEL).contains("src=\"cid:entryQr\"").contains("PAF-WALK01");
        assertThat(email.inlineImages()).containsKey("entryQr");
        assertThat(QrCodes.decode(email.inlineImages().get("entryQr"))).isEqualTo(TOKEN);
        assertThat(email.attachments()).containsKey(EmailService.WALK_IN_QR_FILE);
        assertThat(QrCodes.decode(email.attachments().get(EmailService.WALK_IN_QR_FILE))).isEqualTo(TOKEN);
    }

    @Test
    void theWalkInPaymentEmailCarriesTheGateQr() {
        emailService.sendPaymentRequest(notice(true, TOKEN));
        SentEmail email = only();
        assertThat(email.subject()).isEqualTo("PAF Aerospace Museum - Your Walk-in Billing Number: 000777");
        assertCarriesTheGateQr(email);
        // the Landbank steps are still there
        assertThat(email.html()).contains("In the merchant search, type exactly:");
    }

    @Test
    void anAdvanceBookingsPaymentEmailHasNoGateQr() {
        emailService.sendPaymentRequest(notice(false, null));
        SentEmail email = only();
        assertThat(email.html()).doesNotContain(LABEL).doesNotContain("cid:entryQr");
        assertThat(email.inlineImages()).isEmpty();
        assertThat(email.attachments()).isEmpty();
    }

    @Test
    void theFreeWalkInPassEmailCarriesTheGateQrAndTheWindow() {
        emailService.sendWalkInPass("v@example.com", "Juan", "PAF-WALK01", "Walk-in Window (11:00 AM - 01:00 PM)", TOKEN);
        SentEmail email = only();
        assertThat(email.to()).isEqualTo("v@example.com");
        assertThat(email.subject()).isEqualTo("PAF Aerospace Museum - Your Walk-in Pass for Today");
        assertThat(email.html()).contains("Walk-in Window (11:00 AM - 01:00 PM)").contains("there is nothing to pay")
                .contains("Show this QR and your ID to the guard at the gate");
        assertCarriesTheGateQr(email);
    }

    @Test
    void aWalkInsNoticeTakesItsSecureTokenAnAdvanceBookingsDoesNot() {
        Visitor walkIn = new Visitor();
        walkIn.setBookingCategory(Visitor.CATEGORY_WALK_IN);
        walkIn.setSecureToken(TOKEN);
        walkIn.setVisitDate(LocalDate.now());
        assertThat(EmailService.PaymentNotice.of(walkIn).qrToken()).isEqualTo(TOKEN);

        Visitor advance = new Visitor();
        advance.setSecureToken(TOKEN);
        assertThat(EmailService.PaymentNotice.of(advance).qrToken()).isNull();
    }
}
