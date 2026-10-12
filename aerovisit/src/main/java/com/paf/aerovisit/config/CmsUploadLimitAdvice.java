package com.paf.aerovisit.config;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

// Shows a friendly message when an upload is over the size limit: on the Website Content page, and on the public
// booking form (/save), where the error page can't be shown (Spring's /error forward re-reads the same oversized
// upload and fails again, leaving the visitor a blank 413). Requests to any other page rethrow the exception.
@ControllerAdvice
public class CmsUploadLimitAdvice {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request,
                                 RedirectAttributes redirectAttributes) throws MaxUploadSizeExceededException {
        if ("/save".equals(request.getRequestURI())) {
            redirectAttributes.addFlashAttribute("error", "Your ID photo is larger than 15 MB. Please upload a smaller JPG or PNG (a screenshot or a lower camera resolution works).");
            return "redirect:/";
        }
        if (!request.getRequestURI().startsWith("/admin/content/")) {
            throw ex;
        }
        redirectAttributes.addFlashAttribute("error", "Upload too large. Each photo must be 5 MB or smaller (15 MB per upload).");
        return "redirect:/admin/content#photos";
    }
}
