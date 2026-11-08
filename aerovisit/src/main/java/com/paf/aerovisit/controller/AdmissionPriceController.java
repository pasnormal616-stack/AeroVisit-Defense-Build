package com.paf.aerovisit.controller;

import com.paf.aerovisit.service.AdmissionPricing;
import com.paf.aerovisit.util.Peso;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 💱 Admission prices (Settings page, Director only: SecurityConfig). Regular and Discounted are typed in pesos and
 * saved as whole centavos; the Free category is always 0. Bookings already made keep the prices they were made at.
 */
@Controller
public class AdmissionPriceController {

    static final String INVALID_PRICE = "Prices must be peso amounts from 0 to PHP 100,000.00, with at most two decimal places.";

    // Optional, so web-layer test slices without it still start
    @Autowired(required = false)
    private AdmissionPricing admissionPricing;

    @PostMapping("/admin/settings/admission-prices")
    public String savePrices(@RequestParam(name = "regularPrice", required = false) String regularPrice,
                             @RequestParam(name = "discountedPrice", required = false) String discountedPrice,
                             RedirectAttributes redirectAttributes) {
        Long regular = Peso.parsePrice(regularPrice);
        Long discounted = Peso.parsePrice(discountedPrice);
        if (regular == null || discounted == null) {
            redirectAttributes.addFlashAttribute("error", INVALID_PRICE);
            return "redirect:/admin/settings#admissionPrices";
        }
        if (admissionPricing == null) {
            redirectAttributes.addFlashAttribute("error", "Admission prices are unavailable right now. Please try again.");
            return "redirect:/admin/settings#admissionPrices";
        }
        admissionPricing.save(regular, discounted);
        redirectAttributes.addFlashAttribute("success", "Admission prices saved: Regular " + Peso.format(regular)
                + ", Discounted " + Peso.format(discounted) + ". Bookings already made keep their prices.");
        return "redirect:/admin/settings#admissionPrices";
    }
}
