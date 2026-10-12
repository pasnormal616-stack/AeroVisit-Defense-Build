package com.paf.aerovisit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Chart.js (dashboard + analytics) and html5-qrcode (gate scanner) are pinned to exact versions with Subresource
 * Integrity, so a new CDN release or a tampered file can't silently change or break them. Template text only.
 * The hashes were computed from the CDN files themselves and checked in Chrome (the scripts load and run).
 */
class PinnedCdnScriptsTest {

    static final String CHART_JS = "<script src=\"https://cdn.jsdelivr.net/npm/chart.js@4.5.1/dist/chart.umd.min.js\" "
            + "integrity=\"sha256-SERKgtTty1vsDxll+qzd4Y2cF9swY9BCq62i9wXJ9Uo=\" crossorigin=\"anonymous\"></script>";
    static final String HTML5_QRCODE = "<script src=\"https://unpkg.com/html5-qrcode@2.3.8/html5-qrcode.min.js\" "
            + "integrity=\"sha256-ZgsSQ3sddH4+aLi+BoXAjLcoFAEQrSE/FnsUtm+LHY4=\" crossorigin=\"anonymous\"></script>";

    private String template(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/templates/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void chartJsIsPinnedWithIntegrityOnBothChartPages() throws IOException {
        for (String page : new String[] {"admin.html", "analytics.html"}) {
            String html = template(page);
            assertThat(html).as(page).contains(CHART_JS);
            assertThat(html).as(page + ": no unpinned copy left").doesNotContain("npm/chart.js\"");
        }
    }

    @Test
    void theGateScannerLibraryIsPinnedWithIntegrity() throws IOException {
        String html = template("scanner.html");
        assertThat(html).contains(HTML5_QRCODE).doesNotContain("unpkg.com/html5-qrcode\"");
    }
}
