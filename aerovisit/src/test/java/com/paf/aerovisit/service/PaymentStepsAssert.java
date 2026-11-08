package com.paf.aerovisit.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 💳 The five exact LANDBANK steps (templates/fragments/payment-steps.html). The payment page and every payment email
 * must show the same five sentences, the exact merchant and transaction text in the monospace box, the booking's
 * billing number and its amount. Shared by PaymentInstructionsTest (emails) and PaymentFlowTest (page).
 */
public final class PaymentStepsAssert {

    public static final String MERCHANT = "PHILIPPINE AIR FORCE";
    public static final String TRANSACTION = "PAF Aerospace Museum";
    public static final String BILLING_WARNING = "Type your billing number exactly as shown. A wrong number can delay your booking.";

    private PaymentStepsAssert() {}

    public static List<String> steps(String billingNumber, String amount) {
        return List.of(
                "Open the LANDBANK payment page (button below) and click \"Pay Now\".",
                "In the merchant search, type exactly: PHILIPPINE AIR FORCE (all capital letters, with one space between each word), select it and click \"Continue\".",
                "Choose the transaction: PAF Aerospace Museum, then click \"Continue\".",
                "Enter your billing number exactly: " + billingNumber + ", and the exact amount: " + amount + ".",
                "Complete the payment (GCash, card or bank), then take a screenshot of the Landbank confirmation page that shows your billing number and amount, and upload it below.");
    }

    /** The five steps in order, and the four exact values each in its own monospace box. */
    public static void assertFiveSteps(String html, String billingNumber, String amount) {
        String text = visibleText(html);
        List<String> steps = steps(billingNumber, amount);
        int from = 0;
        for (String step : steps) {
            int at = text.indexOf(step, from);
            assertThat(at).as("step in order: " + step + "\n--- text ---\n" + text).isGreaterThanOrEqualTo(0);
            from = at + step.length();
        }
        assertThat(text).as("each step once").satisfies(t -> {
            for (String step : steps) assertThat(t.split(Pattern.quote(step), -1)).hasSize(2);
        });
        // The layout header already says "PHILIPPINE AIR FORCE AEROSPACE MUSEUM": the check is on the exact box itself
        for (String exact : List.of(MERCHANT, TRANSACTION, billingNumber, amount)) {
            assertThat(html).as("exact box for " + exact)
                    .containsPattern("<code[^>]*>" + Pattern.quote(exact) + "</code>");
        }
        assertThat(text).contains(BILLING_WARNING);
    }

    // Tags removed (Copy buttons too, they are not part of the sentences), entities read, whitespace collapsed
    static String visibleText(String html) {
        String noButtons = html.replaceAll("(?s)<button\\b.*?</button>", "");
        String noTags = noButtons.replaceAll("(?s)<!--.*?-->", "").replaceAll("(?s)<[^>]+>", "");
        return noTags.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
                .replaceAll("\\s+", " ").trim();
    }
}
