package com.paf.aerovisit.controller; 

import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.OcrService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Optional;
import java.util.regex.Pattern; // 🔥 NEW: Required for strict word matching

@RestController
@RequestMapping("/api/booking")
public class IdVerificationController {

    @Autowired
    private OcrService ocrService;

    @Autowired
    private VisitorRepository visitorRepository;

    @PostMapping("/verify-id")
    public ResponseEntity<String> verifyVisitorId(
            @RequestParam("visitorId") Long visitorId,
            @RequestParam("idImage") MultipartFile idImage) {
        
        try {
            // 1. Fetch the visitor
            Optional<Visitor> optionalVisitor = visitorRepository.findById(visitorId);
            if (optionalVisitor.isEmpty()) {
                return ResponseEntity.badRequest().body("Visitor not found in database.");
            }
            Visitor visitor = optionalVisitor.get();

            // 🔒 STATE MACHINE: VERIFIED leads straight to the Approve button, so only a pending request may be
            // re-verified. Otherwise a checked-out (or rejected, cancelled...) booking could be verified, then approved again.
            if (!visitor.awaitsApproval()) {
                return ResponseEntity.status(org.springframework.http.HttpStatus.CONFLICT)
                        .body("Invalid state transition: a booking with status " + visitor.getStatus() + " cannot be re-verified.");
            }

            // 2. Get the names and ensure they are not blank
            String checkFirst = visitor.getFirstName().toUpperCase();
            String checkLast = visitor.getLastName().toUpperCase();

            if (checkFirst.isEmpty() || checkLast.isEmpty()) {
                return ResponseEntity.badRequest().body("❌ FAILED: Visitor name is missing in the database.");
            }

            // 3. Extract the text
            String rawIdText = ocrService.extractTextFromId(idImage);
            String cleanedIdText = rawIdText.toUpperCase();
            
            // 4. 🔥 THE FIX: Strict Word Boundary Matching (\b) 🔥
            // This ensures "ERICK" only matches "ERICK", not "FREDERICK"
            boolean hasFirstName = Pattern.compile("\\b" + Pattern.quote(checkFirst) + "\\b").matcher(cleanedIdText).find();
            boolean hasLastName = Pattern.compile("\\b" + Pattern.quote(checkLast) + "\\b").matcher(cleanedIdText).find();

            // 5. 🔒 Log only the outcome — never visitor names or the raw ID text (personal data)
            System.out.println("OCR check for visitor #" + visitorId + ": firstNameMatch=" + hasFirstName + ", lastNameMatch=" + hasLastName);

            if (hasFirstName && hasLastName) {
                // MATCH FOUND: Update the database
                visitor.setStatus("VERIFIED");
                visitor.setOcrNameMatch(true); // admin table: "ID Name Verified"
                visitorRepository.save(visitor);
                return ResponseEntity.ok("✅ SUCCESS: ID Verified. Database updated.");
            } else {
                // NO MATCH: Flag for admin review
                visitor.setStatus("PENDING_MANUAL_REVIEW");
                visitor.setOcrNameMatch(false); // the newest ID photo didn't show the name
                visitorRepository.save(visitor);
                return ResponseEntity.badRequest().body("❌ FAILED: Name mismatch.");
            }

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("An error occurred: " + e.getMessage());
        }
    }
}