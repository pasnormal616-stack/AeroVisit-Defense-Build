package com.paf.aerovisit.service;

import static com.paf.aerovisit.service.PaymentStepsAssert.assertFiveSteps;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.paf.aerovisit.util.Peso;

/**
 * 💳 Every payment email carries the same five exact LANDBANK steps as the payment page, from one shared fragment
 * (templates/fragments/payment-steps.html): the "please pay" email at booking, the walk-in payment email, the
 * "Resend payment link" email (all three: EmailService.sendPaymentRequest) and the receipt-not-accepted email.
 */
class PaymentInstructionsTest {

    private static final String BILLING = "004217";
    private static final String AMOUNT = "PHP 1,250.00"; // the comma case: Peso.format of 125000 centavos

    private RecordingMailSender mail;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mail = new RecordingMailSender();
        emailService = new EmailService(mail, "https://museum.example/");
        emailService.mailPassword = EmailServiceTest.PASSWORD;
    }

    private static EmailService.PaymentNotice notice(boolean walkIn, boolean approved, String reason) {
        return new EmailService.PaymentNotice("v@example.com", "Juan", "PAF-A7X92K", BILLING, AMOUNT, "5 Regular",
                "Mon, Nov 2, 2026 at 9:00 AM", "tok", reason, walkIn, approved, "museum@example.com");
    }

    private String onlyHtml() {
        assertThat(mail.sent).hasSize(1);
        return mail.sent.get(0).html();
    }

    private static void assertEmailSteps(String html) {
        assertFiveSteps(html, BILLING, AMOUNT);
        assertThat(html).doesNotContain("<button").doesNotContain("data-copy")
                .contains("font-family:Consolas, Menlo, Courier New, monospace;")
                .doesNotContain("The payment page explains each LANDBANK step.");
        // The warning sits right above step 4
        String text = PaymentStepsAssert.visibleText(html);
        assertThat(text).contains(PaymentStepsAssert.BILLING_WARNING + " Enter your billing number exactly: " + BILLING);
    }

    @Test
    void amountFormatsMatchWhatTheStepsAndCopyButtonUse() {
        assertThat(Peso.format(125000)).isEqualTo(AMOUNT);
        assertThat(Peso.plain(125000)).isEqualTo("1250.00");
        assertThat(Peso.plain(65000)).isEqualTo("650.00");
        assertThat(Peso.plain(0)).isEqualTo("0.00");
        assertThat(Peso.plain(5)).isEqualTo("0.05");
    }

    @Test
    void pleasePayEmailAtBookingHasTheFiveExactSteps() {
        emailService.sendPaymentRequest(notice(false, false, null));
        String html = onlyHtml();
        assertEmailSteps(html);
        assertThat(html).contains("Pay and Upload Receipt"); // the link to the payment page is kept
    }

    @Test
    void walkInPaymentEmailHasTheFiveExactSteps() {
        emailService.sendPaymentRequest(notice(true, false, null));
        String html = onlyHtml();
        assertEmailSteps(html);
        assertThat(html).contains("The guard checks your receipt and your ID at the gate");
    }

    @Test
    void resendPaymentLinkEmailHasTheFiveExactSteps() {
        // "Resend payment link" sends the same request email; an older approve-then-pay booking may be resent too
        emailService.sendPaymentRequest(notice(false, true, null));
        assertEmailSteps(onlyHtml());
    }

    @Test
    void receiptNotAcceptedEmailHasTheFiveExactSteps() {
        emailService.sendPaymentRejected(notice(false, false, "The amount on the receipt does not match."));
        String html = onlyHtml();
        assertEmailSteps(html);
        assertThat(html).contains("Upload a New Receipt");
    }

    @Test
    void theStepsAreWrittenInOneTemplateOnly() throws IOException {
        Path templates = Path.of("src/main/resources/templates");
        List<Path> holders;
        try (Stream<Path> files = Files.walk(templates)) {
            holders = files.filter(p -> p.toString().endsWith(".html"))
                    .filter(p -> {
                        try {
                            String s = Files.readString(p, StandardCharsets.UTF_8);
                            return s.contains("In the merchant search") || s.contains("Open the LANDBANK payment page (button below)")
                                    || s.contains("PHILIPPINE AIR FORCE</code>");
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }).toList();
        }
        assertThat(holders).containsExactly(templates.resolve("fragments").resolve("payment-steps.html"));
    }
}
