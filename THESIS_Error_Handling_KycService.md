# Component and Error Analysis: Fail-Safe Identity Verification in AeroVisit

**System:** AeroVisit, the visitor management system of the Philippine Air Force Aerospace Museum
**Component under analysis:** `KycService` (identity verification), together with the booking endpoint `POST /save` in `VisitorPortalController`
**Principles shown:** Graceful degradation, fail-safe defaults, Zero-Trust, "Silent Security" (non-disclosure), information hiding in error messages

---

## 1. The Code Snippet

### 1.1 Context

Before a booking is accepted, AeroVisit verifies the visitor's identity in several steps. These steps depend on an **external Python AI microservice** (OpenCV / SFace, reached over HTTP at `/kyc/verify`) and on a **native OCR library** (Tesseract). Both can fail outside the application's control. The microservice may be offline, slow, or return malformed data, and the native library may be missing from the host machine.

`KycService` is the component that absorbs all of these failures. Its design contract, documented in the class header, is that it **never throws**.

### 1.2 Layer 1: The external call (network fault isolation)

```java
// The Python answer, or null when the service is down, slow or not answering 200
private JSONObject verify(byte[] idCardPhoto, byte[] livenessSelfie) {
    try {
        String body = /* JSON with the two Base64-encoded photos */;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBaseUrl + "/kyc/verify"))
                .timeout(timeout)                                   // 20-second response deadline
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.out.println("KYC service answered HTTP " + response.statusCode());
            return null;                                            // Server error -> treated as "no answer"
        }
        return new JSONObject(response.body());
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();                         // Keep the thread's interrupt status
        return null;
    } catch (Exception e) {
        System.out.println("KYC service unreachable: " + e.getMessage());
        return null;                                                // Timeout / connection refused / bad JSON
    }
}

// The HTTP client also has a separate 10-second connection timeout:
private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
```

### 1.3 Layer 2: The decision logic (graceful degradation and Silent Security)

```java
public static final String PENDING       = "PENDING";
public static final String MANUAL_REVIEW = "PENDING_MANUAL_REVIEW";
public static final String REFUSED       = "REFUSED";

public static final String FACE_MISMATCH = "Security Error: Live face does not match ID.";
// Deliberately generic (silent security): it must not tell a banned person they are banned, restricted or recognised
public static final String FACE_BANNED =
    "Booking Failed. We are unable to process your request at this time. "
  + "Please try again later or contact the reception desk for assistance.";

public KycResult screen(byte[] idCardPhoto, byte[] livenessSelfie, String firstName, String lastName) {
    try {
        JSONObject answer = verify(idCardPhoto, livenessSelfie);
        if (answer == null) return review("AI_SERVICE_UNAVAILABLE", null);       // AI offline -> human review

        double[] face = FaceEncodings.fromArray(answer.optJSONArray("live_face_encoding"));
        String encoding = face == null ? null : FaceEncodings.format(face);

        // Biometric blacklist (checked first, whatever name or email is used)
        if (face != null && faceBans != null && FaceEncodings.isBanned(face, faceBans.findAll())) {
            return refuse("FACE_BANNED", FACE_BANNED, null);                    // Generic message only
        }

        // GRACEFUL DEGRADATION: an unclear result goes to staff, never back to the visitor as an error
        if (!answer.optString("face_match_error").isBlank())          return review(reason, encoding);
        if (!answer.has("is_face_match") || answer.isNull("is_face_match")) return review(reason, encoding);
        if (!answer.optBoolean("is_face_match", false))               return refuse("FACE_MISMATCH", FACE_MISMATCH, null);
        if (face == null)                                             return review("NO_FACE_ENCODING", null);

        /* ... decode the ID-card crop -> review("NO_ID_CARD") / review("BAD_CROP") when unusable ... */
        /* ... OCR name match (with one retry on a mirrored image) -> PENDING, or review("NAME_MISMATCH") ... */

    } catch (Exception | LinkageError e) {      // LinkageError: Tesseract's native library missing on this machine
        System.out.println("KYC check failed, sending to manual review: " + e);
        return review("ERROR", null);           // The last safety net: any unexpected fault ends in human review
    }
}

private static KycResult review(String reason, String faceEncoding) {
    System.out.println("KYC -> manual review (" + reason + ")");               // Technical reason: server log only
    return new KycResult(MANUAL_REVIEW, reason, null, faceEncoding);
}

private static KycResult refuse(String reason, String message, String faceEncoding) {
    System.out.println("KYC -> refused (" + reason + ")");                     // Technical reason: server log only
    return new KycResult(REFUSED, reason, message, faceEncoding);              // message: the only text the user sees
}
```

### 1.4 Layer 3: The caller (`POST /save` in `VisitorPortalController`)

```java
KycService.KycResult kyc = (kycService == null || file.isEmpty() || idCardFile.isEmpty())
        ? new KycService.KycResult(KycService.MANUAL_REVIEW, "INCOMPLETE")      // Missing input -> fail safe
        : kycService.screen(idCardFile.getBytes(), file.getBytes(), visitor.getFirstName(), visitor.getLastName());

if (kyc.refused()) {
    // Nothing is saved and the email code stays valid, so the visitor can retake the photos and resubmit
    discardIdPhoto(visitor.getIdImageUrl());                                   // Remove biometric data already on disk
    discardIdPhoto(visitor.getIdCardImageUrl());
    redirectAttributes.addFlashAttribute("error", kyc.message());              // Shows only the safe, generic text
    return "redirect:/";
}
visitor.setStatus(kyc.status());                                               // PENDING or PENDING_MANUAL_REVIEW
/* ... */
} catch (Exception e) {
    System.out.println("File or OCR Processing failed: " + e.getMessage());
    visitor.setStatus("PENDING_MANUAL_REVIEW");                                // A second safety net at the controller level
}
```

### 1.5 Staff-side contrast (`GuardOperationController`)

The same banned face produces **different messages for different audiences**:

```java
// Shown to the authenticated Director / Guard only:
static final String BANNED_APPROVAL = "Approval refused: this visitor is banned. Lift the ban on the Banned Visitors page first if this is a mistake.";
static final String BANNED_ENTRY    = "ACCESS DENIED: This visitor is banned.";

if (v != null && isFaceBanned(v)) {                    // Checked again at approval time (defence in depth)
    redirectAttributes.addFlashAttribute("error", BANNED_APPROVAL);
    return "redirect:/admin";
}
```

---

## 2. Component Failure Analysis

### 2.1 Failure triggers

The identity verification pipeline is a **distributed** component. It spans a Java web application, a Python microservice, a native C++ OCR engine, the local file system, and the database. The table below lists the failure states the component anticipates.

| # | Trigger (fault) | Where it occurs | How it is intercepted | Resulting state |
| --- | --- | --- | --- | --- |
| 1 | AI microservice is offline (connection refused) | `verify()` → `client.send` | `catch (Exception)` → `null` | `PENDING_MANUAL_REVIEW` (`AI_SERVICE_UNAVAILABLE`) |
| 2 | AI microservice hangs or is overloaded | `verify()` | 10 s connection timeout + 20 s request timeout throw `HttpTimeoutException`, which is caught | `PENDING_MANUAL_REVIEW` |
| 3 | AI microservice returns HTTP 4xx/5xx | `verify()` | Explicit status-code check → `null` | `PENDING_MANUAL_REVIEW` |
| 4 | Malformed or incomplete JSON response | `verify()` / `screen()` | `JSONException` caught; `opt*()` accessors return defaults instead of throwing | `PENDING_MANUAL_REVIEW` |
| 5 | No face found, or face found too weakly (glare, bad lighting) | `screen()` | `face_match_error` / missing `is_face_match` check | `PENDING_MANUAL_REVIEW` |
| 6 | ID card not detected, or the crop cannot be decoded | `screen()` | `id_detected` check; `ImageIO.read` returns `null` | `PENDING_MANUAL_REVIEW` (`NO_ID_CARD` / `BAD_CROP`) |
| 7 | Mirrored front-camera photo makes ID text unreadable | `screen()` | One automatic retry with a flipped image | `PENDING` if the retry succeeds |
| 8 | Tesseract native library missing on the server | `ocrService.extractText` | `catch (LinkageError)`, an `Error` and not an `Exception`, which ordinary handlers miss | `PENDING_MANUAL_REVIEW` (`ERROR`) |
| 9 | Thread interrupted during the network call | `verify()` | `InterruptedException` caught and interrupt flag restored | `PENDING_MANUAL_REVIEW` |
| 10 | KYC service bean absent, or a photo missing (forged request) | `/save` controller | Null / empty check before calling the service | `PENDING_MANUAL_REVIEW` (`INCOMPLETE`) |
| 11 | Face clearly does not match the ID | `screen()` | Positive decision, not an exception | `REFUSED` (specific message) |
| 12 | Face matches a biometric ban | `screen()` | Positive decision, not an exception | `REFUSED` (generic Silent Security message) |

### 2.2 Interception strategy: layered containment

Failures are contained at **three nested layers**. A fault that gets past one layer is caught by the next:

1. **Transport layer (`verify()`).** Every network-level fault is turned into a single *sentinel value* (`null`). The rest of the component never sees an `IOException`, `HttpTimeoutException`, or HTTP error code. It only has to answer one question: did the AI give an answer or not?
2. **Decision layer (`screen()`).** Each uncertain AI result is mapped to an explicit domain state (`PENDING_MANUAL_REVIEW`) with a machine-readable reason code. An outer `catch (Exception | LinkageError e)` acts as a **catch-all safety net**. Catching `LinkageError` matters here: a missing native library throws an `Error`, which a typical `catch (Exception e)` would not catch.
3. **Controller layer (`/save`).** A second `try/catch` around file handling and KYC sets the booking to `PENDING_MANUAL_REVIEW` if anything still escapes. A refused booking also triggers a **cleanup** (`discardIdPhoto`), so the system is not left in a partial state with orphaned biometric files on disk.

### 2.3 Why this prevents a catastrophic failure

Without this design, a single outage of the Python microservice would surface as an unhandled exception in `POST /save`. That would produce an HTTP 500 error page, and **every visitor booking would fail** for as long as the AI service was down. In other words, a non-critical helper component would become a single point of failure for the core business process.

Instead, the component applies **graceful degradation** (also called *fail-soft* operation). When automated verification is unavailable, the system falls back to a slower but still safe path, **human review**. The booking is still saved, the visitor still receives the "request received" email, and the Director verifies the uploaded photos manually. Three properties follow:

- **Availability is kept.** Booking keeps working during an AI outage.
- **Security is not weakened.** The fallback state is `PENDING_MANUAL_REVIEW`, never `APPROVED`. The system **fails closed**: no failure path can make a booking look verified. The class header states this explicitly: "*Nothing is ever approved here.*"
- **The service contract is total.** `screen()` returns a valid `KycResult` for every input, so callers never need their own error handling for it.

This follows the *fail-safe defaults* principle from Saltzer and Schroeder (1975): when in doubt, deny the privileged outcome (automatic verification) and fall back to the conservative one (human inspection).

---

## 3. Security and UX Impact

### 3.1 Zero-Trust alignment

AeroVisit's identity check follows the Zero-Trust maxim *"never trust, always verify"*:

- **Missing evidence is not treated as passing evidence.** A request without photos (possible only by bypassing the form) or with the KYC service unavailable gets `PENDING_MANUAL_REVIEW`. An absent check is never treated as a passed check.
- **No privileged bypass.** Master Bookings (large VIP or educational groups) go through the same screening as everyone else.
- **Defence in depth.** The biometric ban is checked at booking time (`KycService`) and **again** at approval time (`GuardOperationController.isFaceBanned`). A face banned *after* a booking was submitted still cannot be approved.
- **Automation can refuse but never approve.** Only a human can approve a booking (human-in-the-loop).

### 3.2 Silent Security: non-disclosure of security state

When the biometric blacklist matches a visitor, the system deliberately hides *why* the booking failed. The visitor sees only:

> *"Booking Failed. We are unable to process your request at this time. Please try again later or contact the reception desk for assistance."*

The message does not say that the person is banned, recognised, or under restriction. This has three security benefits:

1. **No oracle for attackers.** A banned person cannot use the error message to test which identities, faces, or emails are flagged, or to keep adjusting a disguise until the message changes.
2. **No escalation trigger.** Telling someone they were recognised by a security system at a military installation can provoke a confrontation. A neutral message lets the situation be handled in person at the reception desk.
3. **Privacy of the blacklist.** Whether a ban exists is itself sensitive information, and it is not disclosed to the person concerned through the public interface.

The **audience-dependent messaging** in §1.5 completes this design. The same event produces a generic message for the anonymous public user and an explicit, actionable one ("*this visitor is banned... Lift the ban on the Banned Visitors page*") for the **authenticated** Director or Guard. How much a message reveals depends on the trust level of the person reading it.

### 3.3 Separation of user-facing and technical error information

The `KycResult` record keeps two separate fields:

| Field | Example | Audience | Destination |
| --- | --- | --- | --- |
| `reason` (technical code) | `AI_SERVICE_UNAVAILABLE`, `FACE_BANNED`, `BAD_CROP`, `ERROR` | Developers / administrators | Server log (`KYC -> manual review (AI_SERVICE_UNAVAILABLE)`) |
| `message` (user-facing text) | The generic Silent Security text | Visitor | Flash message on the booking page |

Exception details, stack traces, the AI service's address, and HTTP status codes **never reach the browser**. They are written only to the server console. This follows the OWASP recommendation against *Improper Error Handling* and CWE-209 (*Generation of Error Message Containing Sensitive Information*): internal system details are not exposed to anonymous users.

### 3.4 Data privacy

When a booking is refused, the controller **deletes the uploaded selfie and ID photo from disk** (`discardIdPhoto`). The live face vector is also not kept: `refuse(...)` passes `null` as the face encoding. A refused applicant's biometric data is not retained, which follows the *data minimisation* principle of the Philippine Data Privacy Act of 2012 (RA 10173) and the GDPR (Art. 5(1)(c)).

### 3.5 User-experience impact

- **The visitor is never blamed for a system fault.** If the AI is offline or the photo has glare, the visitor does not see an error. They see the normal "request received / pending" confirmation, and staff review the photos.
- **Recoverable errors are recoverable.** When a booking is refused (e.g. a face mismatch from a poor selfie), the email verification code **stays valid**, so the visitor can retake the photos and resubmit without starting over.
- **Friendly messages at the framework edge.** A related `@ControllerAdvice` (`CmsUploadLimitAdvice`) catches `MaxUploadSizeExceededException` on the booking form. Instead of a blank HTTP 413 page, the visitor is redirected back with: "*Your ID photo is larger than 15 MB. Please upload a smaller JPG or PNG.*"

---

### Summary for the paper

> AeroVisit's identity verification component shows deliberate failure-state engineering. `KycService` relies on an external AI microservice and a native OCR library, both outside the application's control. It contains their failures in three nested layers: transport-level exception conversion, decision-level graceful degradation, and a controller-level safety net. Every anticipated fault, including network timeouts, HTTP errors, malformed responses, unclear biometric results, and even a missing native library (`LinkageError`), resolves to a single safe state, `PENDING_MANUAL_REVIEW`, which hands the decision to a human administrator. The system therefore *fails closed* without *failing hard*: booking stays available during an AI outage, and no failure path can grant automated verification. On the security side, the component separates technical diagnostics (written to the server log) from user-facing messages. It applies a "Silent Security" policy so that a biometrically banned person receives only a neutral, generic error, while authenticated staff receive an explicit one. It also deletes the biometric data of refused applicants. This is consistent with Zero-Trust architecture, fail-safe defaults, CWE-209 guidance, and data-minimisation principles.

---

### Optional: limitations (for a critical-evaluation section)

For balance, a thesis can note two refinements:

1. **Logging.** Diagnostics are written with `System.out.println` rather than a structured logging framework (SLF4J/Logback, which Spring Boot already includes). Structured logging would add timestamps, severity levels, and log rotation, and would make the audit trail easier to search.
2. **Persisting the reason code.** The technical `reason` (e.g. `AI_SERVICE_UNAVAILABLE`) is logged but not saved on the booking record. The Director sees the `PENDING_MANUAL_REVIEW` status and the photos, but not *why* the booking was routed there. Likewise, a refused banned-face attempt is recorded only in the server log and does not raise an admin notification. Saving the reason and alerting staff on banned-face attempts would make the system easier to audit.
