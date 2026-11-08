package com.paf.aerovisit.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.service.AdmissionPricing;

/** 💱 Money as whole centavos: display, parsing the Director's input, defaults and the server-side total. */
class PesoTest {

    @Test
    void formatsCentavosAsPesos() {
        assertThat(Peso.format(0)).isEqualTo("PHP 0.00");
        assertThat(Peso.format(15_000)).isEqualTo("PHP 150.00");
        assertThat(Peso.format(123_456_789)).isEqualTo("PHP 1,234,567.89");
    }

    @Test
    void parsesPricesExactlyWithinTheLimits() {
        assertThat(Peso.parsePrice("150")).isEqualTo(15_000L);
        assertThat(Peso.parsePrice(" 175.5 ")).isEqualTo(17_550L);
        assertThat(Peso.parsePrice("0.10")).isEqualTo(10L);
        assertThat(Peso.parsePrice("12.300")).isEqualTo(1_230L);      // trailing zeros are not extra precision
        assertThat(Peso.parsePrice("100000")).isEqualTo(10_000_000L);
        assertThat(Peso.parsePrice("1,250.00")).isEqualTo(125_000L);
        for (String bad : new String[] {null, "", " ", "-0.01", "100000.01", "12.345", "abc", "1e9"}) {
            assertThat(Peso.parsePrice(bad)).as(String.valueOf(bad)).isNull();
        }
    }

    @Test
    void pricesDefaultTo150And100UntilTheDirectorSavesThem() {
        PortalSettingsRepository repo = mock(PortalSettingsRepository.class);
        AdmissionPricing pricing = new AdmissionPricing();
        assertThat(pricing.current()).isEqualTo(AdmissionPricing.Prices.DEFAULT); // no repository at all

        org.springframework.test.util.ReflectionTestUtils.setField(pricing, "settingsRepo", repo);
        when(repo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.empty());
        assertThat(pricing.current()).isEqualTo(new AdmissionPricing.Prices(10_000L, 15_000L));

        PortalSettings oldRow = new PortalSettings(); // a row saved before prices existed: null columns
        when(repo.findById(PortalSettings.SINGLETON_ID)).thenReturn(Optional.of(oldRow));
        assertThat(pricing.current()).isEqualTo(new AdmissionPricing.Prices(10_000L, 15_000L));

        oldRow.setRegularPriceCentavos(20_000L);
        oldRow.setDiscountedPriceCentavos(0L);
        assertThat(pricing.current()).isEqualTo(new AdmissionPricing.Prices(0L, 20_000L));
    }

    @Test
    void theTotalChargesDiscountedAndRegularButNeverTheFreeCategory() {
        AdmissionPricing.Quote quote = AdmissionPricing.quote(2, 3, 4, new AdmissionPricing.Prices(10_000L, 15_000L));
        assertThat(quote.people()).isEqualTo(9);
        assertThat(quote.totalCentavos()).isEqualTo(65_000L);
        assertThat(AdmissionPricing.countsError(0, 0, 1)).isNull();
        assertThat(AdmissionPricing.countsError(0, 0, 0)).isEqualTo("Please enter at least 1 visitor.");
        assertThat(AdmissionPricing.countsError(0, -1, 2)).isEqualTo("Visitor counts cannot be negative.");
        assertThat(AdmissionPricing.countsError(10_001, 0, 0)).isEqualTo("Please check the number of visitors.");
    }
}
