package com.paf.aerovisit.service;

import com.paf.aerovisit.model.PortalSettings;
import com.paf.aerovisit.repository.PortalSettingsRepository;
import com.paf.aerovisit.util.Peso;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 💱 Admission categories and their prices. Regular and Discounted are Director settings (PortalSettings, Settings page),
 * so a price change needs no code change; the Free category is always 0. Money is whole centavos (long).
 * The server always computes a booking's total here from the counts; a total sent by the browser is never read.
 */
@Service
public class AdmissionPricing {

    // Unit prices in centavos; FREE costs nothing
    public record Prices(long discounted, long regular) {
        public static final Prices DEFAULT = new Prices(PortalSettings.DEFAULT_DISCOUNTED_PRICE_CENTAVOS,
                PortalSettings.DEFAULT_REGULAR_PRICE_CENTAVOS);

        public String discountedText() { return Peso.format(discounted); }
        public String regularText() { return Peso.format(regular); }
    }

    // A booking's counts priced at the given unit prices
    public record Quote(int discounted, int regular, int free, Prices prices, long totalCentavos) {
        public int people() { return discounted + regular + free; }
    }

    // Largest count accepted in one category (far above any slot; keeps the multiplication small)
    public static final int MAX_COUNT = 10_000;

    // Optional, so test slices without a database still start; without it the default prices apply
    @Autowired(required = false)
    PortalSettingsRepository settingsRepo;

    // The prices in force now (the defaults when nothing is saved or the database can't be read)
    public Prices current() {
        if (settingsRepo == null) return Prices.DEFAULT;
        try {
            return settingsRepo.findById(PortalSettings.SINGLETON_ID)
                    .map(s -> new Prices(s.discountedPrice(), s.regularPrice()))
                    .orElse(Prices.DEFAULT);
        } catch (RuntimeException e) {
            System.err.println("⚠️ Admission prices could not be read; using the defaults. " + e.getMessage());
            return Prices.DEFAULT;
        }
    }

    // Saves new unit prices (already validated: 0 to Peso.MAX_PRICE_CENTAVOS). Existing bookings keep the prices they were made at.
    public void save(long regularCentavos, long discountedCentavos) {
        PortalSettings settings = settingsRepo.findById(PortalSettings.SINGLETON_ID).orElseGet(PortalSettings::new);
        settings.setRegularPriceCentavos(regularCentavos);
        settings.setDiscountedPriceCentavos(discountedCentavos);
        settingsRepo.save(settings);
    }

    // Total = discounted x discounted price + regular x regular price; the free category is not charged
    public static Quote quote(int discounted, int regular, int free, Prices prices) {
        long total = Math.addExact(Math.multiplyExact((long) discounted, prices.discounted()),
                Math.multiplyExact((long) regular, prices.regular()));
        return new Quote(discounted, regular, free, prices, total);
    }

    // Null when the counts are acceptable, otherwise the message to show
    public static String countsError(int discounted, int regular, int free) {
        if (discounted < 0 || regular < 0 || free < 0) return "Visitor counts cannot be negative.";
        if (discounted > MAX_COUNT || regular > MAX_COUNT || free > MAX_COUNT) return "Please check the number of visitors.";
        if (discounted + regular + free < 1) return "Please enter at least 1 visitor.";
        return null;
    }
}
