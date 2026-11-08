package com.paf.aerovisit.util;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

// 💱 Money is kept as whole centavos (long), never as floating point. This turns it into "PHP 1,250.00" for display
// and reads a typed peso amount ("150", "175.50") back into centavos.
public final class Peso {

    // Highest unit price the Director may set: PHP 100,000.00
    public static final long MAX_PRICE_CENTAVOS = 10_000_000L;

    private Peso() {}

    public static String format(long centavos) {
        DecimalFormat pesos = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        return "PHP " + pesos.format(BigDecimal.valueOf(centavos, 2));
    }

    // 💳 The amount as Landbank's amount box takes it: "1250.00" (no "PHP", no comma). The payment page's Copy button uses it.
    public static String plain(long centavos) {
        return BigDecimal.valueOf(centavos, 2).toPlainString();
    }

    // "175.50" -> 17550. Null when the text is not a peso amount from 0 to PHP 100,000.00 with at most two decimals.
    public static Long parsePrice(String text) {
        if (text == null || text.isBlank()) return null;
        BigDecimal pesos;
        try {
            pesos = new BigDecimal(text.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
        if (pesos.scale() > 2 && pesos.stripTrailingZeros().scale() > 2) return null;
        BigDecimal centavos = pesos.movePointRight(2);
        if (centavos.signum() < 0 || centavos.compareTo(BigDecimal.valueOf(MAX_PRICE_CENTAVOS)) > 0) return null;
        return centavos.longValueExact();
    }
}
