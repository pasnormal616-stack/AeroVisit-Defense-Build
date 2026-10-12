# AeroVisit: Functional Specification and Technology Mapping

**System:** AeroVisit, the Visitor Management and Base Access Control System of the Philippine Air Force Aerospace Museum, Villamor Air Base, Pasay City
**Document type:** Functional specification with technology mapping (for inclusion in the Capstone manuscript)
**Baseline:** source code as of 9 October 2026 (Spring Boot application `aerovisit`, Python AI microservice `AeroVisit-AI`; 671 automated Java tests and 46 Python tests passing)

---

## How to Read This Document

AeroVisit is divided into three functional domains, each served by a different group of users:

1. **Visitor Portal.** Public, unauthenticated users who book a visit.
2. **Director / Admin Dashboard.** The `DIRECTOR` role, which reviews bookings and runs the museum's back office.
3. **Guard Dashboard & Security.** The `GUARD` role, which operates the physical gate, together with the security mechanisms that protect the whole system.

Each section opens with a **technology mapping table** for quick reference. A **detailed specification** follows, giving every function three parts:

- **Technology Used:** the libraries, frameworks, APIs or algorithms that implement it.
- **How It Works:** the technical mechanism, with its key parameters.
- **Why This Technology:** the engineering rationale for the choice.

The function codes (V-xx, D-xx, G-xx) can be cited directly in the manuscript.

**Platform common to all three domains:** Java 21, Spring Boot 3.5 (Spring MVC, Spring Data JPA / Hibernate, Spring Security 6), MySQL 8, server-side rendering with Thymeleaf, Bootstrap 5.3 with a custom design-token stylesheet, and a local Python FastAPI microservice for computer vision and machine learning. Its two endpoints are reached only from the same machine (`127.0.0.1:8000`).

---

## 1. VISITOR PORTAL

### 1.1 Technology Mapping Summary

| Code | Function | Technology Used |
|---|---|---|
| V-01 | Landing page and three-step booking wizard | Thymeleaf, Bootstrap 5.3, custom CSS design tokens, vanilla JavaScript |
| V-02 | Real-time seat availability calendar | FullCalendar 6.1.10, Spring MVC, Spring Data JPA |
| V-03 | Dynamic museum content (announcements, guidelines, exhibits, video, social links) | Thymeleaf, Spring `HandlerInterceptor` (CMS injection), JPA entities |
| V-04 | Visitor profile capture and input validation | HTML5 constraint validation, Spring `@InitBinder` field whitelist |
| V-05 | Group Manifest and Master Booking (large groups) | Plain Java CSV/TXT parser, Java NIO charset decoding, regex validation |
| V-06 | Email ownership verification (OTP) | `java.security.SecureRandom`, `ConcurrentHashMap`, JavaMail (Gmail SMTP), Fetch API |
| V-07 | Two-step KYC capture: Step 1, ID document close-up | WebRTC `getUserMedia`, OpenCV.js 4.12 (WebAssembly), HTML5 Canvas, `DataTransfer` API |
| V-08 | Two-step KYC capture: Step 2, liveness selfie (challenge-response) | Google MediaPipe Tasks-Vision 1.1.0 FaceLandmarker (WASM/WebGL), Web Crypto `getRandomValues` |
| V-09 | Camera interface (full-screen guide, prompts, fallback) | CSS fixed overlay, SVG alignment guide, ARIA live regions, HTML `capture` attribute |
| V-10 | Server-side ID card extraction | Python 3, FastAPI, OpenCV (contour detection, perspective warp), NumPy |
| V-11 | Server-side 1:1 face verification | OpenCV YuNet face detector + SFace face recognizer (ONNX models), cosine similarity |
| V-12 | ID name verification (OCR) | Tesseract OCR via Tess4J, Java ImageIO / TwelveMonkeys, custom Levenshtein fuzzy matcher |
| V-13 | Biometric ban screening and the "Silent Security" protocol | SFace 128-dimension face embeddings, cosine similarity (Java), generic refusal message |
| V-14 | Cryptographic one-time KYC nonce (anti-replay / anti-automation) | `java.util.UUID` (v4, from `SecureRandom`), `HttpSession`, `MessageDigest.isEqual` |
| V-15 | CSRF protection on public requests | Spring Security `CsrfFilter`, `<meta>` token + `X-CSRF-TOKEN` header (`csrf.js`) |
| V-16 | Race-safe seat reservation | JPA pessimistic write lock (`SELECT … FOR UPDATE`), Spring `@Transactional` |
| V-17 | Booking Reference ID generation | `SecureRandom` over a 32-character unambiguous alphabet |
| V-18 | Secure photo storage | UUID file naming, MIME whitelist, Spring multipart limits, `@ControllerAdvice` |
| V-19 | Booking status lookup | Two-factor lookup (email + Reference ID), Spring Data derived queries |
| V-20 | Manage Booking self-service portal | `HttpSession`, session-ID rotation, per-session form token |
| V-21 | QR code entry ticket | ZXing 3.5.1 (`QRCodeWriter`), UUID secure token |
| V-22 | Email notifications | Spring Boot Mail (JavaMail), Gmail SMTP with STARTTLS, `MimeMessageHelper`, Spring `HtmlUtils` |
| V-23 | Automated visit reminders | Spring `@Scheduled` cron (07:00 Asia/Manila) |
| V-24 | Post-visit feedback ("After-Action Report") with AI sentiment analysis | Hugging Face Transformers, XLM-RoBERTa (`cardiffnlp/twitter-xlm-roberta-base-sentiment`), keyword topic classifier |
| V-25 | AI Chatbot "Sgt. Aero" | Google Gemini API (`gemini-3.6-flash`, `google-genai` SDK), FastAPI, Java `HttpClient`, MySQL chat log |
| V-26 | Chatbot voice input and output | Web Speech API (`SpeechRecognition`, `SpeechSynthesis`) |
| V-27 | Chatbot abuse protection and session isolation | Sliding-window rate limiter (Java), browser `localStorage` session ID, LRU session cache (Python) |
| V-28 | Directions to the museum | Google Maps URL scheme (deep link) |
| V-29 | Privacy notice and informed consent (R.A. 10173) | Bootstrap modals, CMS-configurable contact email |
| V-30 | Accessibility, theming and responsive layout | CSS custom properties, `prefers-color-scheme`, `prefers-reduced-motion`, `pointer: coarse` media queries, ARIA |
| V-31 | Safe error pages | Spring Boot error handling, custom `AuthenticationEntryPoint` |

### 1.2 Detailed Specification

#### V-01 Landing Page and Three-Step Booking Wizard
- **Technology Used:** Thymeleaf server-side templates (`register.html`, shared fragments in `fragments/visitor.html`), Bootstrap 5.3, a custom design system (`theme.css`, `visitor.css`), vanilla JavaScript (`visitor.js`).
- **How It Works:** The page is rendered on the server with the calendar data, CMS content and a fresh security nonce already in place. A client-side stepper splits the form into **01 Arrival Date → 02 Visitor Profile → 03 Clearance**. A step unlocks only when the previous one is valid. A radar-style pre-loader and a one-time welcome modal (remembered in `sessionStorage`) introduce the portal. Fonts are *Bricolage Grotesque* and *Plus Jakarta Sans*; colours follow the Philippine Air Force palette (#002B80 navy, #D2AF39 gold, #EC2B2A red).
- **Why This Technology:** Server-side rendering needs no separate front-end build pipeline. It keeps all business data on the server, and it lets Spring Security add tokens to forms automatically (see V-15). Splitting a long form into steps lowers the cognitive load on first-time visitors, and a token-based stylesheet keeps light and dark themes consistent.

#### V-02 Real-Time Seat Availability Calendar
- **Technology Used:** FullCalendar 6.1.10, Spring MVC (`VisitorPortalController.showForm`), Spring Data JPA.
- **How It Works:**
  - For every future `BookingSlot` (date + Director-defined time window), the server works out *remaining = capacity − seats held*. Only **seat-holding** statuses count: `APPROVED`, `CHECKED-IN` and `CHECKED-OUT`. A `PENDING` request is a waitlist entry and holds no seat.
  - Dates reserved by an approved Master Booking (VIP buyout) or closed by an emergency are forced to zero.
  - FullCalendar draws the results with a colour legend: **green** = open, **orange** = 15 seats or fewer, **red** = full or closed.
- **Why This Technology:** FullCalendar gives an accessible, touch-friendly month view without building a calendar from scratch. Calculating capacity on the server means the browser can never make a closed or full date look available.

#### V-03 Dynamic Museum Content
- **Technology Used:** Thymeleaf; a Spring `HandlerInterceptor` (`CmsWebConfig`) loaded through `ObjectProvider`; JPA entities `Announcement`, `Guideline`, `ExhibitPhoto` and `PortalSettings`.
- **How It Works:** After a public page's controller runs, the interceptor adds the active announcements, visitor guidelines, exhibit slideshow photos, the featured video, social-media links and the contact email to the model. All of these are edited by the Director (D-19). Exhibit photos are served publicly from `/exhibit-photos/**`, separately from the protected visitor uploads.
- **Why This Technology:** An interceptor adds the content to every public page *without changing the booking controller*. Museum staff can update public information without a developer or a redeployment.

#### V-04 Visitor Profile Capture and Input Validation
- **Technology Used:** HTML5 constraint validation (`required`, `min`, `pattern`, `maxlength`); Spring `@InitBinder` with `setAllowedFields`.
- **How It Works:**
  - The browser checks fields (name, sex, age, address, affiliation, contact, group size, purpose) before submission.
  - On the server, an **allow-list binder** accepts only the 15 public form fields. Server-controlled fields (`status`, `secureToken`, `referenceId`, check-in data, `faceEncoding`, `ocrNameMatch`) are silently ignored, so a hand-crafted request cannot approve itself or forge a ticket (*mass-assignment protection*).
- **Why This Technology:** Browser validation gives instant feedback, while the server allow-list provides the actual security. Together they follow the rule that client-side checks are for convenience and server-side checks are for security.

#### V-05 Group Manifest and Master Booking
- **Technology Used:** Plain Java CSV/TXT parsing (quote-aware first-field extraction); Java NIO `CharsetDecoder` (UTF-8, falling back to Windows-1252); regular-expression validation.
- **How It Works:**
  - Every companion beyond the lead booker must be named (count = group size − 1), up to 100 characters each, using only letters, digits and `. , ' ( ) / -`.
  - Schools with 50+ guests can **unlock Master Booking**. It needs an official event justification, and the guest list can be entered three ways: row by row, pasted in bulk, or uploaded as a CSV/TXT file (up to 256 KB, header row detected, Excel encodings handled).
  - Master Bookings skip the public seat limit because, once approved, they reserve the whole day (VIP buyout, D-09).
- **Why This Technology:** A named manifest lets guards check every person's ID at the gate. A small built-in parser handles Excel's real-world output (quoted "Dela Cruz, Juan" names, BOMs, Windows encodings) with no third-party dependency and a smaller attack surface.

#### V-06 Email Ownership Verification (One-Time Password)
- **Technology Used:** `SecureRandom`, thread-safe `ConcurrentHashMap` state (`OtpService`), JavaMail over Gmail SMTP, the browser Fetch API (`OtpController`, `POST /otp/send`, `/otp/verify`).
- **How It Works:**
  - A 6-digit code is generated with `SecureRandom` and emailed. It is valid for **5 minutes**, can be resent after **60 seconds**, and is destroyed after **5 wrong attempts**.
  - Sending is rate-limited to **5 codes per email** and **10 codes per client IP** per rolling hour. Behind the ngrok tunnel, the IP is taken from the proxy-added `X-Forwarded-For` hop.
  - Codes are compared in **constant time** (`MessageDigest.isEqual`), so response timing reveals nothing about the code.
  - A verified email may submit **one** booking within 30 minutes; the verification is consumed on save.
- **Why This Technology:** The OTP replaced Google reCAPTCHA. It proves the visitor controls the email address that will receive the QR ticket, works as an anti-bot barrier, and needs no third-party widget. In-memory storage fits short-lived codes and requires no database table.

#### V-07 Two-Step KYC Capture, Step 1: ID Document Close-Up
- **Technology Used:** WebRTC `navigator.mediaDevices.getUserMedia`, OpenCV.js 4.12 (WebAssembly build), HTML5 Canvas `toBlob`, the `DataTransfer` API.
- **How It Works:**
  - The phone's **rear camera** is requested (`facingMode: ideal environment`) and the visitor is asked to place the ID flat on a table. On webcams the prompt asks them to hold the ID up instead.
  - Four times a second, OpenCV.js looks for a card-shaped rectangle (ID-1 aspect ratio 1.25–2.0) inside a large CR80 guide frame.
  - When the card fills at least **35%** of the frame and stays **steady for 1 second**, the frame is captured automatically as a JPEG. The `DataTransfer` API places it into the form's ordinary file input (`highResIdImage`).
- **Why This Technology:** Auto-capture produces a sharp, framed, glare-checked image without asking the visitor to judge photo quality. Running OpenCV in WebAssembly means the video never leaves the device; only the final still image is uploaded.

#### V-08 Two-Step KYC Capture, Step 2: Liveness Selfie (Challenge-Response)
- **Technology Used:** Google MediaPipe Tasks-Vision 1.1.0 **FaceLandmarker** (478-point face mesh; GPU delegate with CPU fallback), the Web Crypto API (`crypto.getRandomValues`), OpenCV.js (ID presence check).
- **How It Works:**
  - The visitor holds the same ID while facing the front camera. Two **different, randomly chosen** challenges are drawn from four: *blink*, *open mouth*, *turn head left*, *turn head right* (12 possible ordered pairs).
  - Each frame is measured with:
    - **Eye Aspect Ratio (EAR)** = (|p2−p6| + |p3−p5|) / 2|p1−p4| on six eye landmarks;
    - **Mouth Aspect Ratio (MAR)** from the inner lips;
    - **head yaw** from the nose tip's position between the cheek edges.
  - Thresholds **adapt** to the visitor's own resting face. A blink counts below min(0.20, 0.75 × baseline EAR).
  - A **blink is mandatory**, natural or requested. Testing showed that a printed photo can fake head turns and mouth movement in the face mesh but cannot fake a blink.
  - A wrong move or the **12-second** limit restarts the round with a new pair; after **3 rounds** the camera stops.
  - The challenge clock **pauses** whenever the face or the ID card leaves its guide box (card at least 10% of the smaller guide box).
- **Why This Technology:** MediaPipe gives dense, real-time facial landmarks in the browser with no server round trip and no video upload, which matters for both privacy and bandwidth. A *randomised* challenge defeats a pre-recorded video that cannot know which moves will be asked, and cryptographic randomness makes the sequence unpredictable. This is a presentation-attack **deterrent**; the authoritative checks run on the server (V-10 to V-13) and in human review (D-05).

#### V-09 Camera Interface: Guides, Prompts and Fallback
- **Technology Used:** A CSS fixed full-screen overlay (`#livenessModal`, moved to `<body>` to escape stacking contexts), an SVG alignment guide (`viewBox` equal to the camera frame, `preserveAspectRatio="xMidYMid meet"`), ARIA live regions, and the HTML `capture` attribute.
- **How It Works:**
  - The camera opens full-screen with a face oval and an ID-card rectangle drawn in SVG. Because the SVG uses the same coordinate system and scaling rule as the video (`object-fit: contain`), the guides stay aligned on **any screen shape, portrait or landscape**.
  - The front camera is mirrored and the rear camera is not; the card-detection region is mirrored to match.
  - Prompts ("Challenge 1/2: Please Turn Your Head Left") are announced to screen readers.
  - A **Pro Tip** advises holding the device slightly above eye level in good light.
  - If the camera is unavailable, permission is denied, the page is on plain HTTP, the browser is too old, or the models take more than 20 s to load, the page falls back to two ordinary file inputs (`capture="environment"` for the ID, `capture="user"` for the selfie).
- **Why This Technology:** Pure CSS/SVG overlays scale without distortion and need no extra library. The graceful fallback means no visitor is locked out of booking because of their device, and the server-side checks still apply to uploaded photos.

#### V-10 Server-Side ID Card Extraction
- **Technology Used:** Python 3, FastAPI (`POST /kyc/verify`), OpenCV (Gaussian blur, Canny and Otsu edge maps, contour approximation, `getPerspectiveTransform` / `warpPerspective`), NumPy.
- **How It Works:**
  - Java sends both photos as base64 JSON. Several edge maps are tried so that a single lighting condition cannot hide the card's outline.
  - A candidate must be ID-1 shaped (aspect ratio 1.25–2.0), cover **3–85%** of the frame, and be at least **75% rectangular**. Fingers over a corner are tolerated through a rotated bounding box.
  - The best candidate is **flattened** with a perspective warp to a 1712-pixel-wide crop. A card photographed **upright (portrait)** is rotated so that its text lies horizontally (landscape).
  - The service never returns HTTP 500; every problem comes back as a normal answer with a reason code.
- **Why This Technology:** OpenCV is the standard open-source computer-vision library, runs fully offline, and offers the geometric operations needed to correct perspective and orientation. A separate Python service uses Python's mature vision ecosystem while keeping the Java core clean.

#### V-11 Server-Side 1:1 Face Verification
- **Technology Used:** The OpenCV **YuNet** face detector and **SFace** face recognizer (ONNX models from the OpenCV Model Zoo), cosine similarity.
- **How It Works:**
  - The **live face** is found first (YuNet confidence ≥ 0.8, at least 12% of the frame). Its **128-dimension SFace embedding** is computed and returned even if the ID side later fails, so ban screening (V-13) always runs.
  - The **ID portrait** is searched only *inside* the flattened card, at full resolution and a lower confidence of 0.6, which allows for glare and holograms. Detection tries 0°, 90°, 180° and 270° rotations.
  - The two embeddings are compared. **Cosine similarity ≥ 0.363** (OpenCV's published SFace threshold) means the same person.
  - A mismatch counts only when the ID portrait was found with full confidence. Otherwise the booking is routed to human review instead of being refused.
- **Why This Technology:** YuNet and SFace are lightweight, accurate, CPU-friendly models bundled with OpenCV. They avoid dlib, which cannot be installed on the target Windows/Python 3.14 environment, and they need no external biometric API, so biometric data never leaves museum servers.

#### V-12 ID Name Verification (OCR)
- **Technology Used:** **Tesseract OCR** through **Tess4J 5.11**, Java ImageIO with TwelveMonkeys plug-ins (JPEG/WebP), and a custom bounded **Levenshtein** fuzzy matcher (`FuzzyNameMatcher`).
- **How It Works:**
  - The flattened card crop is redrawn on a clean RGB canvas and read by Tesseract.
  - The visitor's first and last names must each match a **different** OCR word within an edit tolerance that grows with word length: 1–3 letters exact, 4–7 letters one edit, 8+ letters two edits.
  - Accents are normalised ("PEÑA" = "PENA"), and multi-word surnames are compared without spaces ("DE LA CRUZ" = "DELACRUZ").
  - If the first pass fails, OCR is retried once on a **horizontally mirrored** crop, because front cameras often save mirrored images.
  - The result only sets the Director's **"ID Name Verified"** or **"Name Unverified"** badge (D-03). It never approves anything by itself.
- **Why This Technology:** Tesseract is a mature, free, offline OCR engine; Tess4J lets the existing Java service call it directly. Fuzzy matching absorbs typical OCR errors ("N1CHOLAS") without allowing short names to match by accident.

#### V-13 Biometric Ban Screening and the "Silent Security" Protocol
- **Technology Used:** SFace embeddings stored as JSON in MySQL (`blacklisted_faces`), cosine similarity in Java (`FaceEncodings.isBanned`), a generic refusal message.
- **How It Works:**
  - The live-face embedding is compared with every **active** ban before any other KYC decision. Temporary bans past their end date are ignored.
  - On a match, the booking is refused with a deliberately neutral message: *"Booking Failed. We are unable to process your request at this time…"*. Both photos are deleted and no record is created.
  - The person is never told they were recognised, so changing their name or email gives them no feedback to work with.
  - The same check runs again at approval, Force Approve, QR scan and manual check-in (defence in depth).
- **Why This Technology:** A face cannot be changed the way an email address can, so the ban follows the person. The silent response denies attackers the feedback they would need to probe the system.

#### V-14 Cryptographic One-Time KYC Nonce (Anti-Replay / Anti-Automation)
- **Technology Used:** `java.util.UUID.randomUUID()` (version-4 UUID, 122 random bits from `SecureRandom`), the server-side `HttpSession`, constant-time comparison (`MessageDigest.isEqual`).
- **How It Works:**
  1. Each time the booking page is rendered, the server generates a fresh nonce, stores it in the visitor's session, and embeds it in the form as a hidden field.
  2. `POST /save` checks this **before anything else**. The submitted value must equal the session's value.
  3. The session's nonce is **removed on every attempt, successful or not**, so each page load allows exactly one submission and guessing is impossible.
  4. A request with no nonce, a made-up nonce, or a replayed nonce is refused before the OTP, KYC or database is touched.

  The nonce also serves as the booking upload's synchronizer token, standing in for Spring's CSRF token on this one multipart endpoint (see V-15).
- **Why This Technology:** Binding each submission to a server-issued, single-use secret defeats **scripted submissions** that never load the booking page and **replays** of a captured request. Scope, stated plainly: an attacker who loads the page in a real browser session still receives a valid nonce, so the nonce raises the cost of automation but is **not proof of liveness**. That assurance comes from V-11, V-13 and mandatory human review (D-05).

#### V-15 CSRF Protection on Public Requests
- **Technology Used:** The Spring Security `CsrfFilter` (session-stored synchronizer token), `<meta name="_csrf">` tags, and a small helper script (`/js/csrf.js`, `AeroCsrf.headers()`) that adds an `X-CSRF-TOKEN` header to Fetch API calls.
- **How It Works:**
  - Every state-changing request (POST, PUT, PATCH, DELETE) must carry the session's token. Forms carry it as a hidden `_csrf` field; scripted calls such as the OTP buttons send it as a header.
  - The booking upload `/save` is the single exemption. Its multipart body cannot be read before the filter runs, and it is protected by the one-time nonce (V-14) instead. This exemption also lets an oversized photo receive the friendly "larger than 15 MB" message rather than an error page.
- **Why This Technology:** CSRF tokens stop another website from submitting forms on a user's behalf. An automated test scans every template so that a future form or Fetch call without the token fails the build.

#### V-16 Race-Safe Seat Reservation
- **Technology Used:** A JPA `@Lock(PESSIMISTIC_WRITE)` query on the booking slot (translated to `SELECT … FOR UPDATE` in MySQL), Spring `@Transactional` (`SeatReservationService`).
- **How It Works:** The service locks the slot row, then **re-counts** the seats held inside the same transaction, and saves only if the group still fits. Two simultaneous requests for the last seats therefore queue behind the lock, and the second sees the first one's seats. The same mechanism protects Director approvals (D-06).
- **Why This Technology:** A check-then-save sequence without a lock is a classic race condition that leads to overbooking. Pessimistic locking at the database level guarantees correctness even when several application threads or servers run at once. The behaviour is proven by an H2 test with a negative control.

#### V-17 Booking Reference ID Generation
- **Technology Used:** `SecureRandom` over a 32-character alphabet without look-alike characters (no 0/O or 1/I), plus a database uniqueness check.
- **How It Works:** The server generates IDs such as `PAF-A7X92K` (6 characters, about 1.07 billion combinations) and regenerates in the unlikely event of a collision. The ID is never accepted from the form.
- **Why This Technology:** The ID must be easy to read over the phone or type at the gate, yet impossible to guess, because it works as a second factor alongside the email (V-19, V-20).

#### V-18 Secure Photo Storage
- **Technology Used:** UUID-based file names, a MIME whitelist (`image/jpeg`, `image/png`), Spring multipart limits (15 MB), Tomcat `max-swallow-size`, `CmsUploadLimitAdvice` (`@ControllerAdvice`).
- **How It Works:**
  - Uploaded photos are saved as `<random-UUID>.jpg|png`, so the visitor's name never appears in file paths or URLs.
  - Refused bookings have their photos deleted immediately.
  - The upload folder is served only to authenticated staff (G-01).
  - Oversized uploads are redirected with a friendly message instead of a dropped connection.
- **Why This Technology:** Anonymous names and staff-only access support the Data Privacy Act's data-minimisation and security principles. Explicit size handling prevents a confusing "connection reset" on phones that take very large photos.

#### V-19 Booking Status Lookup
- **Technology Used:** Spring Data JPA derived queries (`findByEmailIgnoreCase`), plain-language status mapping (`visitorStatusLabel`).
- **How It Works:** The visitor must enter **both** the email and the Booking Reference ID. Statuses are shown in friendly terms ("Pending Review", "Approved", "Not Approved", "Missed Visit"). Internal reasons, such as a ban, are never shown.
- **Why This Technology:** An email alone is guessable. Requiring a second, unguessable value prevents strangers from viewing other people's bookings, and the friendly labels support the Silent Security protocol (V-13).

#### V-20 Manage Booking Self-Service Portal
- **Technology Used:** `HttpSession`, `HttpServletRequest.changeSessionId()`, a per-session form token, and Spring Security CSRF protection.
- **How It Works:**
  - After signing in with email and Reference ID, the session ID is **rotated** to prevent session fixation.
  - The visitor can change the date, time window, group size, contact number or guest list, or cancel. Capacity, closure, buyout and duplicate checks run again on every change.
  - Changing an **approved** booking sends it back to *Pending* and cancels the old QR code until the Director approves the change.
  - Cancelling releases the seats immediately.
- **Why This Technology:** Self-service reduces administrative workload. Session rotation and the form token are standard defences for an unauthenticated portal that changes data.

#### V-21 QR Code Entry Ticket
- **Technology Used:** **ZXing 3.5.1** (`QRCodeWriter`, `MatrixToImageWriter`), a version-4 UUID secure token.
- **How It Works:**
  - At approval, a 250×250 PNG QR code is generated that encodes **only** the booking's random secure token. It contains no name, ID number or other personal data.
  - The image is attached to the approval email.
  - The token works at the gate only while the booking is *Approved* and only on its date and time window (G-06).
  - Rejection, cancellation, gate denial, a no-show, or a schedule change clears the QR code.
- **Why This Technology:** ZXing is the reference open-source barcode library for Java. An opaque random token cannot be forged with a free QR generator, unlike QR codes that encode readable data.

#### V-22 Email Notifications
- **Technology Used:** Spring Boot Mail (JavaMail), Gmail SMTP on port 587 with STARTTLS, `MimeMessageHelper` (HTML body + attachments), Spring `HtmlUtils` escaping, 10-second SMTP timeouts.
- **How It Works:** HTML emails are sent for:
  - the OTP code;
  - booking received (pending review);
  - approval with the QR attachment and Reference ID;
  - the visit reminder;
  - check-in guidelines naming the Tour Guide on duty;
  - check-out with a feedback link;
  - rejection, with reason-specific wording;
  - VIP-buyout cancellation;
  - emergency closure cancellation;
  - staff duty schedules;
  - the Director's daily report and critical-feedback alerts.

  All visitor-typed text is HTML-escaped before it is placed in an email. Emails triggered at the gate are sent on a background thread (G-15).
- **Why This Technology:** Email is the one channel every visitor has, and JavaMail integrates natively with Spring Boot. Escaping prevents HTML injection through names, and timeouts stop a slow mail server from freezing a booking.

#### V-23 Automated Visit Reminders
- **Technology Used:** Spring `@Scheduled` cron (`0 0 7 * * ?`), with the JVM time zone pinned to `Asia/Manila`.
- **How It Works:** Every morning at 07:00, tomorrow's approved visitors who have a QR code receive a reminder with the ticket attached. A `reminderSent` flag guarantees that each visitor receives only one.
- **Why This Technology:** Built-in scheduling needs no external job server. Pinning the time zone keeps every job on Philippine time even if the server runs in UTC.

#### V-24 Post-Visit Feedback with AI Sentiment Analysis
- **Technology Used:** A token-protected feedback form; Hugging Face Transformers running **XLM-RoBERTa** (`cardiffnlp/twitter-xlm-roberta-base-sentiment`, loaded from the local cache); a keyword topic classifier; the FastAPI `GET /analyze` endpoint.
- **How It Works:**
  - After check-out the visitor receives a unique link that is valid once and only for completed visits. They rate the visit 1–5 stars, choose a museum zone and comment (up to 500 characters).
  - The comment is classified as positive, neutral or negative and tagged with a topic: Facilities, Staff, Exhibits, Ticketing, Entry or General.
  - A **negative result above 0.75 confidence** triggers an email alert and a bell notification for the Director.
- **Why This Technology:** XLM-RoBERTa is multilingual and reads Tagalog and Taglish, which matches the museum's real visitors. Running it locally keeps feedback private and avoids API quotas.

#### V-25 AI Chatbot "Sgt. Aero"
- **Technology Used:** The **Google Gemini API** (`gemini-3.6-flash`, through the `google-genai` SDK) inside the FastAPI service (`POST /chat`); a Java bridge (`AiAdvisorService`, `java.net.http.HttpClient`, 10-second timeouts); a MySQL `chat_logs` table.
- **How It Works:**
  - The visitor's message travels browser → Java (`/api/chatbot/ask`) → Python, as a JSON POST body so the text is never written into URLs or logs → Gemini.
  - A system **persona and scope prompt** limits the assistant to the museum, aviation heritage, booking help and visit planning. It must refuse off-topic tasks, never invent facts, and never reveal its instructions.
  - Each request is invisibly enriched with **live telemetry**: current Manila weather (Open-Meteo) and the expected crowd from the Random Forest forecast (D-14). The bot can therefore answer "Is it busy today?".
  - Conversations are stored per session in `chat_logs`.
- **Why This Technology:** A large language model handles open-ended natural-language questions that a scripted FAQ cannot. Gemini's free tier suits a capstone budget. Placing the call in the Python service keeps the API key off the Java application and the browser.

#### V-26 Chatbot Voice Input and Output
- **Technology Used:** Web Speech API: `SpeechRecognition` / `webkitSpeechRecognition` (speech-to-text) and `SpeechSynthesis` (text-to-speech, with a preferred local-accent voice, rate 0.95, pitch 0.9).
- **How It Works:** A microphone button dictates the question; a speaker toggle reads replies aloud. Errors such as a blocked microphone or a non-HTTPS page produce a clear message, and the button always resets, even on mobile browsers that skip the `onend` event.
- **Why This Technology:** Voice interaction helps visitors with low literacy, visual impairments or busy hands. The browser API needs no paid speech service.

#### V-27 Chatbot Abuse Protection and Session Isolation
- **Technology Used:** A sliding-window rate limiter (`ChatRateLimiter`), a per-browser session ID kept in `localStorage`, and an LRU-bounded chat-session cache in Python (`OrderedDict`, 500 entries, thread lock).
- **How It Works:**
  - Each client may send **10 messages per rolling minute**; extra requests receive HTTP 429 with `Retry-After`.
  - Each browser keeps its own conversation ID, validated against a strict pattern. Malformed or legacy shared IDs are replaced with an anonymous one, so visitors never see each other's history.
- **Why This Technology:** Rate limiting protects the limited Gemini quota and the server from abuse. Session isolation is a privacy requirement for any multi-user conversational system.

#### V-28 Directions to the Museum
- **Technology Used:** A Google Maps URL-scheme deep link (`/maps/dir/?api=1&destination=…`).
- **How It Works:** A "Get directions" link above the chat input and in the footer opens turn-by-turn navigation to Villamor Air Base in the visitor's maps application.
- **Why This Technology:** The chatbot cannot see the visitor's location. Handing navigation to the native maps app gives accurate routing with no API key and no location data collected by the museum.

#### V-29 Privacy Notice and Informed Consent (R.A. 10173)
- **Technology Used:** Bootstrap modals (Privacy Policy, Terms of Entry), a single required consent checkbox, a contact email the Director can configure through the CMS.
- **How It Works:** Before submitting, the visitor must agree to the guidelines, the Terms and the processing described in the Privacy Policy. The policy discloses:
  - the collection of a **face template**, 1:1 matching and comparison with barred faces;
  - that all processing happens on museum servers;
  - automatic deletion 30 days after the visit (D-23);
  - the data subject's rights and the National Privacy Commission complaint route.
- **Why This Technology:** The Data Privacy Act of 2012 requires informed, specific consent, especially for sensitive biometric data. Modals keep the full text one tap away without leaving the form.

#### V-30 Accessibility, Theming and Responsive Layout
- **Technology Used:** CSS custom properties (design tokens), `prefers-color-scheme` with a persistent manual toggle (`theme.js`), `prefers-reduced-motion`, `(pointer: coarse)` media queries for 44-pixel touch targets, ARIA roles and live regions.
- **How It Works:** One set of colour tokens is redefined for dark mode. Layouts collapse at the 576/768/992 px breakpoints. Animations are disabled for users who request reduced motion, and status messages are announced to assistive technology.
- **Why This Technology:** Most visitors book from phones. Token-based theming and progressive enhancement give a consistent, inclusive experience with no extra framework.

#### V-31 Safe Error Pages
- **Technology Used:** Spring Boot error handling (`server.error.include-*=never`), custom `error.html` / `error/404.html`, and a custom `AuthenticationEntryPoint`.
- **How It Works:** Errors show branded, friendly pages (400/403/404/405/413) with **no stack traces or internal messages**. An unknown public URL returns *404* instead of revealing the staff login page.
- **Why This Technology:** Suppressing internal details is a basic defence against reconnaissance, and hiding the staff portal from the public reduces the attack surface.

---

## 2. DIRECTOR / ADMIN DASHBOARD

### 2.1 Technology Mapping Summary

| Code | Function | Technology Used |
|---|---|---|
| D-01 | Staff authentication and command shell | Spring Security form login, BCrypt, Thymeleaf shell fragments, role flags |
| D-02 | Command Dashboard (Today / Pending / Entire Database) | Spring MVC, Spring Data JPA, Java `Comparator` sorting |
| D-03 | Pending Review queue with triage badges | Thymeleaf conditional rendering, `java.time` |
| D-04 | KYC document viewer (portrait and landscape presentation) | Bootstrap modal, CSS Grid `auto-fit`, `object-fit: contain` |
| D-05 | Human-in-the-Loop (HITL) review: unclear KYC, minors, school IDs, ID orientation | KYC routing state machine (Java), OpenCV orientation correction (Python) |
| D-06 | Booking approval with QR issuance | Status state machine, pessimistic locking, ZXing, JavaMail |
| D-07 | Director capacity override (Force Approve) | Role-restricted endpoint, audit logging |
| D-08 | Rejection with reasons | Preset reason catalogue, templated email |
| D-09 | VIP buyout (Master Booking approval) | Bulk JPA update, email notifications |
| D-10 | Proactive biometric banning | SFace embeddings, JPA (`BlacklistedFace`), Java NIO file copy |
| D-11 | Ban list administration (lift ban) | Role-restricted endpoint, file deletion |
| D-12 | Daily capacity tracker and live occupancy | JPA aggregation, server-side calculation |
| D-13 | Notification bell | Fetch API polling (30 s), REST JSON (`NotificationApiController`) |
| D-14 | Strategic analytics, AI forecast and Explainable AI | Chart.js 4.5.1 (SRI-pinned), scikit-learn Random Forest, Open-Meteo API |
| D-15 | Feedback insights and Root Cause Matrix | XLM-RoBERTa outputs, zone tagging, Chart.js |
| D-16 | Reporting: PDF log and Excel export | OpenPDF 1.3.30, Apache POI 5.5.1 (`.xlsx`) |
| D-17 | Booking slots and time-window configuration | FullCalendar, JPA entities (`BookingSlot`, `TimeWindowConfig`) |
| D-18 | Emergency closure | JPA (`MuseumClosure`), bulk cancellation, email |
| D-19 | Website content management (mini-CMS) | Spring MVC, multipart upload with validation, JPA |
| D-20 | Staff directory and duty roster | JPA, FullCalendar, client-side search and pagination |
| D-21 | AI Roster Generator (preview → deploy) | Random Forest turnout forecast, rule-based staffing, review modal |
| D-22 | Museum zones administration | REST endpoints (`GET/POST/PATCH /api/zones`), Fetch API with CSRF header |
| D-23 | Automated data-retention purge | Spring `@Scheduled` (02:00), Java NIO, path-traversal guard |
| D-24 | Automation engine (sweeps, Director report, AI accuracy audit, roster archiving) | Spring `@Scheduled` cron jobs |
| D-25 | Account settings | Spring MVC, honest "not enabled" notice |
| D-26 | Demonstration data injection | Synthetic data generator, AI retraining trigger |

### 2.2 Detailed Specification

#### D-01 Staff Authentication and Command Shell
- **Technology Used:** Spring Security form login with an in-memory `UserDetailsService` and **BCrypt** password hashing; Thymeleaf shell fragments (`fragments/shell.html`: sidebar, header, scripts); role flags `isDirector` / `isGuard` provided by `GlobalModelAdvice`.
- **How It Works:**
  - Two accounts exist: `director` (role `DIRECTOR`) and `guard` (role `GUARD`). Passwords are read from environment configuration (`.env`), never from source code.
  - Every staff page uses the same shell, and navigation links are rendered according to the **role** (never the username).
  - Logging out is a POST request carrying the CSRF token. It invalidates the session and deletes the session cookie.
- **Why This Technology:** Spring Security is the de-facto standard for Spring applications and provides hashing, session management and authorisation in one framework. BCrypt is deliberately slow and salted, which resists brute-force attacks. Role-based rendering means renaming or adding accounts never breaks the interface.

#### D-02 Command Dashboard
- **Technology Used:** Spring MVC (`AdminDashboardController`), Spring Data JPA, Java `Comparator` chains.
- **How It Works:**
  - **Tabs:** *Today's Manifest*, *Pending Approval* and *Entire Database* (mock baseline data excluded).
  - **Ordering:** first-in-first-out by submission time by default, newest-first optional.
  - **Filters:** a visit-date filter for the Pending and Database tabs.
  - **Row details:** group size, companion manifest, Master Booking justification and status badges.
- **Why This Technology:** Server-side filtering keeps the page fast and ensures guards can never load this data (G-01). FIFO ordering gives fair, predictable processing of requests.

#### D-03 Pending Review Queue with Triage Badges
- **Technology Used:** Thymeleaf conditional rendering, `java.time` comparisons, the `Visitor.APPROVABLE_STATUSES` state set.
- **How It Works:** The queue lists `PENDING` (checks passed), `PENDING_MANUAL_REVIEW` (something needs a closer look) and legacy `VERIFIED` requests. Each row carries:
  - a **NEW** badge for requests under 24 hours old;
  - an **"ID Name Verified"** (green) or **"Name Unverified"** (amber) OCR badge (V-12);
  - a **Pending Review** status badge.
- **Why This Technology:** Visual cues let the Director put attention where it is needed, typically amber-badged manual-review cases, without opening every record.

#### D-04 KYC Document Viewer (Portrait and Landscape Presentation)
- **Technology Used:** One shared Bootstrap modal (`#kycModal`), filled from data attributes on the clicked **View KYC** button; CSS Grid with `repeat(auto-fit, minmax(min(100%, 320px), 1fr))`; images styled with `width: 100%`, `height: auto`, `max-height: 65vh` and `object-fit: contain`.
- **How It Works:**
  - The **ID close-up** and the **liveness selfie** are displayed side by side on desktops and stacked on phones; the grid decides automatically from the available width.
  - Because every image keeps its natural aspect ratio (`object-fit: contain`) within a height cap tied to the viewport (`65vh`), a **portrait** photo (vertical school ID, upright phone selfie) and a **landscape** photo (horizontal government ID) are both shown **complete and undistorted**, with nothing cropped or stretched.
  - Images are removed from the page when the modal closes.
- **Why This Technology:** The Director must see the *entire* document, including edges, photo and text, to judge authenticity. Fixed-size thumbnails would crop or distort IDs of different shapes. A pure-CSS solution handles every orientation and screen size without JavaScript image processing. (The *geometric* correction of a sideways card for OCR happens on the server; see V-10 and D-05.)

#### D-05 Human-in-the-Loop (HITL) Review
- **Technology Used:** The KYC routing logic in Java (`KycService`, which never returns an approval), OpenCV orientation correction in Python (`warp_card`, four-rotation face search), and the Director's manual decision through the KYC viewer (D-04).
- **How It Works:**
  - **Principle:** automation **sorts** bookings and **refuses only two high-confidence threats**, a clear face mismatch and an active ban. **Every admission is decided by a person.**
  - **Routing:** every uncertain case becomes `PENDING_MANUAL_REVIEW` instead of an error for the visitor. Uncertain cases include glare, holograms, a card that cannot be found, a weak ID portrait, an unreadable name, the AI service offline, or missing models.
  - **Minors and visitors without a government ID:** there is no automated age rule. A minor's school ID or missing ID simply produces an unclear or unverified result, which routes to manual review. The Director then applies museum policy, for example admission with a guardian or as a companion on a parent's or school's booking.
  - **School IDs, company IDs and other ID-1 cards:** the detector accepts any ISO/IEC 7810 ID-1-shaped card, so PhilSys, driver's licences, UMID and school IDs are processed the same way. Small or glossy school-ID portraits are searched at full resolution with a lower threshold, and a mismatch against such a weak portrait goes to review rather than refusal.
  - **Portrait vs landscape IDs:** an ID held upright is rotated to landscape by the perspective warp before OCR. Faces are searched at 0°, 90°, 180° and 270°, and a mirrored card is OCR'd a second time after flipping. Anything still unreadable goes to review, and the Director sees the original photos at their true orientation (D-04).
- **Why This Technology:** Automated identity checks produce false rejections for exactly the groups a museum serves most: students, minors and visitors with worn or unusual IDs. Keeping a human as the final authority meets the principle that consequential automated decisions must be reviewable, while automation removes most of the manual effort.

#### D-06 Booking Approval with QR Issuance
- **Technology Used:** A status state machine (`Visitor.awaitsApproval`), the biometric ban re-check, a slot-existence check, pessimistic locking (V-16), ZXing (V-21) and JavaMail (V-22).
- **How It Works:** Approval succeeds only when:
  - the booking is still pending (state machine);
  - the face is not banned;
  - the date and time window match a real slot;
  - seats remain (re-counted under a database lock).

  On success the QR ticket is generated and emailed with the Reference ID. Gate denials are final and can never be re-approved.
- **Why This Technology:** Re-validating at the moment of approval closes the gap between booking and review, during which capacity or a ban may have changed. The explicit state machine prevents illegal transitions such as reviving a checked-out ticket.

#### D-07 Director Capacity Override (Force Approve)
- **Technology Used:** A Director-only endpoint (`/admin/approve-override/{id}`), enforced by Spring Security, with server-side audit logging.
- **How It Works:** Approves a pending request **even when its slot is full**. It still refuses banned faces, nonexistent slots, non-pending bookings and Master Bookings, and every use is logged with the Director's username.
- **Why This Technology:** Real operations need a controlled escape hatch for VIP or emergency cases. Limiting it to one role, keeping every other safeguard, and logging it preserves accountability.

#### D-08 Rejection with Reasons
- **Technology Used:** A preset reason catalogue with optional notes, `Visitor.rejectionReason`, and templated JavaMail messages.
- **How It Works:** The Director selects a reason and may add notes. The visitor receives a matching email; for example, "Capacity Reached" becomes an invitation to rebook. Visits already underway or completed, and gate-denial records, cannot be rejected.
- **Why This Technology:** Standardised reasons give consistent, professional communication and clean data for reporting, while the guards against invalid transitions protect the audit trail.

#### D-09 VIP Buyout (Master Booking Approval)
- **Technology Used:** A bulk JPA update (`findApprovedVisitorsToOverride`, `saveAll`) and per-visitor JavaMail notifications.
- **How It Works:** Approving a Master Booking reserves its date. Public visitors already approved for that date are moved to `CANCELLED_OVERRIDE`, their QR codes are voided, and each receives an apology email. The public calendar then shows the date as unavailable (V-02).
- **Why This Technology:** Large educational tours need exclusive use of the facility. Automating the displacement and its notifications removes error-prone manual follow-up.

#### D-10 Proactive Biometric Banning
- **Technology Used:** SFace face embeddings (V-11), the JPA entity `BlacklistedFace` (`blacklisted_faces`: vector, reason, name, mugshot, `bannedAt`, `expiresAt`), Java NIO `Files.copy`, and a shared Bootstrap dialog (`fragments/ban.html`).
- **How It Works:**
  - From any booking with face data, the Director chooses a **reason** (Fake ID Uploaded, Repeated No-Shows, Misconduct on Premises, Security Threat, Other) and a **duration** (30 days, 6 months, Permanent).
  - The face vector is copied into the ban list, together with the name and the ban's **own copy** of the selfie as a mugshot. The original selfie is purged after 30 days, so a separate copy is needed.
  - **Every open booking with the same face is revoked under any email.**
  - Banning the same person again keeps the longer ban. Bookings without face data show a disabled ban option with an explanation.
- **Why This Technology:** Biometric banning prevents a banned person from simply re-registering with a new email or name. Enforcement uses only the numerical vector; the name and photo exist solely to help staff recognise the person.

#### D-11 Ban List Administration
- **Technology Used:** A Director-only endpoint (`/admin/bans/{id}/lift`, enforced by Spring Security) and secure file deletion limited to plain file names inside the uploads folder.
- **How It Works:** Lifting a ban removes the vector and deletes its mugshot. Bookings revoked earlier stay rejected. Expired temporary bans stay listed as records but are ignored by every check.
- **Why This Technology:** Separating the power to *impose* a ban (Director and Guard) from the power to *lift* one (Director only) is a separation-of-duties control. Deleting the biometric image follows the storage-limitation principle.

#### D-12 Daily Capacity Tracker and Live Occupancy
- **Technology Used:** JPA queries with server-side aggregation (`buildCapacityForDate`).
- **How It Works:** For any chosen date, each time window shows its capacity and remaining seats (zero on buyout or closure days). A live panel shows how many people are inside now (the sum of checked-in group sizes) against today's capacity, as a utilisation percentage.
- **Why This Technology:** Approval decisions depend on context. Showing capacity next to the approval queue supports informed, just-in-time decisions.

#### D-13 Notification Bell
- **Technology Used:** Fetch API polling every 30 seconds, a REST JSON endpoint (`GET /api/notifications`, `POST /api/notifications/mark-read` with the CSRF header), and the JPA entity `AdminNotification` with per-role read status.
- **How It Works:** The bell shows unread alerts plus the pending count. Alerts include large-group bookings (more than 15 people) and negative feedback. Messages are inserted as text, never as HTML, so visitor-typed content cannot inject markup. Guards receive a filtered view with no pending count.
- **Why This Technology:** Short-interval polling gives near-real-time awareness without the complexity of WebSockets, which suits a single-server deployment with a small number of staff.

#### D-14 Strategic Analytics, AI Forecast and Explainable AI
- **Technology Used:** **Chart.js 4.5.1** (pinned with a Subresource Integrity hash); a scikit-learn **RandomForestRegressor** with 100 trees (FastAPI `GET /predict`); the **Open-Meteo** weather API (Manila coordinates, no key required).
- **How It Works:**
  - The forecast combines the day of the week and live weather severity to predict **walk-ins**, then adds known pre-bookings to give **total expected turnout** and a staffing recommendation.
  - An **Explainable AI (XAI)** modal breaks the forecast into three stages:
    1. *Live Data Ingestion:* day weight, weather, pre-booked count.
    2. *Ensemble Processing:* algorithm, number of trees, variance.
    3. *Final Computation:* walk-in median + pre-booked = total.
  - Charts show traffic history, peak arrival hours and the attendance funnel. The page is Director-only.
- **Why This Technology:** A Random Forest captures non-linear effects (weekends, bad weather) from small datasets and resists overfitting. The XAI breakdown makes the prediction transparent and defensible instead of a "black box". Chart.js is lightweight and needs no build step, and SRI pinning blocks a tampered CDN copy.

#### D-15 Feedback Insights and Root Cause Matrix
- **Technology Used:** Stored sentiment labels and topics from XLM-RoBERTa (V-24), museum-zone tagging, Chart.js.
- **How It Works:** A complaint is counted when the sentiment is negative **or** the rating is 2 stars or lower. Complaints are cross-tabulated by **topic × zone** (the Root Cause Matrix), and a feed lists recent feedback with its AI labels.
- **Why This Technology:** Aggregating free-text feedback into categories turns comments into actionable maintenance and staffing priorities.

#### D-16 Reporting: PDF Log and Excel Export
- **Technology Used:** **OpenPDF 1.3.30** (PDF generation) and **Apache POI 5.5.1** (`.xlsx`).
- **How It Works:**
  - **Export Log (PDF):** today's roster with a chart, plus an all-time Root Cause Matrix section.
  - **Export Excel:** the last 30 days of real visitors (mock data excluded) in 16 columns, including feedback and AI analysis.
  - Both are Director-only and ask for confirmation before downloading.
- **Why This Technology:** PDF suits printing and filing official records; Excel suits further analysis by management. Both libraries are open-source and generate files on the server with no external service.

#### D-17 Booking Slots and Time-Window Configuration
- **Technology Used:** JPA entities `BookingSlot` and `TimeWindowConfig`, a bulk slot generator, FullCalendar visualisation, Director-only endpoints (`/admin/slots/**`).
- **How It Works:** The Director defines named time windows (for example "Morning (08:00 AM – 12:00 PM)"; windows cannot cross midnight) and generates bookable slots with a capacity across a date range. Deleting a slot is a POST action with confirmation.
- **Why This Technology:** Configurable windows let the museum adapt to seasons and events without code changes. The window label is also parsed by the gate (G-06) and by the automatic sweeps.

#### D-18 Emergency Closure
- **Technology Used:** The JPA entity `MuseumClosure`, bulk status updates, and JavaMail emergency-cancellation emails.
- **How It Works:** Declaring a closure cancels every affected booking (`CANCELLED`), voids QR codes, notifies each visitor by email, and blocks the date on the public calendar (V-02). Removing the closure re-opens the date.
- **Why This Technology:** Typhoons and security events need fast, mass communication. Automating the process ensures nobody arrives at a closed facility.

#### D-19 Website Content Management (Mini-CMS)
- **Technology Used:** Spring MVC endpoints under `/admin/content/**` (Director-only), multipart upload with per-photo size checks (`ExhibitPhotoStorage`), and JPA entities.
- **How It Works:** The Director manages announcements (add, edit, toggle, delete), visitor guidelines (add, edit, reorder, delete), exhibit slideshow photos (upload, delete), the featured video, social-media links and the public contact email. Inputs are validated; for example, social links are rebuilt as HTTPS URLs on the correct domain, and only a single valid email address is accepted.
- **Why This Technology:** Content ownership moves from developers to museum staff. Server-side validation prevents injection through links and email fields.

#### D-20 Staff Directory and Duty Roster
- **Technology Used:** JPA entities `MuseumEmployee` and `StaffSchedule`, FullCalendar roster view, client-side search with 5-row pagination, POST-only delete actions.
- **How It Works:** The Director keeps a directory of officers (role: Gate Guard, Tour Guide, Admin or custom) with default shifts and working days, and assigns duty shifts. Publishing the roster emails every officer their upcoming shifts. The roster determines which guard is credited with check-ins (G-17) and which tour guide is named in visitor emails.
- **Why This Technology:** Linking the roster to gate records creates an accountability trail per shift, and email distribution removes manual schedule messaging.

#### D-21 AI Roster Generator (Preview → Deploy)
- **Technology Used:** The Random Forest turnout forecast (D-14) together with rule-based matching of staff shift templates, and a Bootstrap review modal.
- **How It Works:** For a chosen period, the system forecasts turnout per day, works out the personnel required, and proposes named officers based on their working days and default shifts. The Director **reviews and edits every suggested name** in a preview before pressing *Deploy*, which saves exactly what was reviewed.
- **Why This Technology:** Forecast-driven staffing balances cost and service, and the mandatory preview keeps a human in control of personnel decisions. This applies the same HITL principle as D-05.

#### D-22 Museum Zones Administration
- **Technology Used:** REST endpoints (`GET /api/zones` public; `POST` and `PATCH` Director-only) and the Fetch API with the CSRF header.
- **How It Works:** The Director adds or deactivates museum zones in a modal on the analytics page. The feedback form (V-24) lists only active zones, and the server accepts only active zone names.
- **Why This Technology:** A small REST interface lets the zone list update without reloading the page, while server-side validation keeps feedback data clean.

#### D-23 Automated Data-Retention Purge
- **Technology Used:** Spring `@Scheduled` (`0 0 2 * * ?`), Java NIO deletion with a path-traversal guard (only plain file names directly inside the uploads folder are touched).
- **How It Works:** Every night at 02:00, visits that ended more than **30 days** ago lose their selfie, ID close-up, QR file **and face vector**. This covers checked-out, no-show, rejected and all cancelled statuses. The record itself remains for statistics. A field is cleared only after its file is confirmed gone, so locked files are retried the next night. Gate-denial records are kept deliberately as security records.
- **Why This Technology:** It implements the Data Privacy Act's **storage-limitation** principle automatically and verifiably, without relying on manual clean-up.

#### D-24 Automation Engine
- **Technology Used:** Spring `@Scheduled` cron jobs (Asia/Manila time).
- **How It Works:**

  | Time | Job |
  |---|---|
  | 07:00 | Visitor reminders (V-23) |
  | 17:30 | Check out visitors whose time window has ended, and send feedback emails |
  | 23:59 | Check out anyone still inside, then mark unattended approved bookings as `NO_SHOW` (QR voided) |
  | 23:59 | AI accuracy audit: actual turnout vs forecast, with variance and error margin logged |
  | 00:00 | Director's daily security report by email; archiving of past duty shifts |
  | 02:00 | Data-retention purge (D-23) |

- **Why This Technology:** Built-in scheduling keeps records consistent without staff intervention. For example, a ticket can never stay valid forever, and nobody stays "inside" overnight in the records.

#### D-25 Account Settings
- **Technology Used:** Spring MVC (`/admin/settings`).
- **How It Works:** The page shows the account profile, role and system information. The change-password form validates its input but **honestly reports that the password was not changed**, because staff accounts are managed in server configuration.
- **Why This Technology:** It avoids a misleading success message, which would be a usability and security anti-pattern.

#### D-26 Demonstration Data Injection
- **Technology Used:** A synthetic data generator (`MockVisitorGenerator`) and the AI retraining endpoint (`POST /retrain`).
- **How It Works:** A Director-only action generates realistic historical visits with feedback and retrains the forecast model on them. Mock records are tagged and excluded from exports and the Entire Database view.
- **Why This Technology:** It provides a populated environment for demonstrations and model training without contaminating real operational data.

---

## 3. GUARD DASHBOARD & SECURITY

### 3.1 Technology Mapping Summary

| Code | Function | Technology Used |
|---|---|---|
| G-01 | Role-Based Access Control (RBAC) | Spring Security `SecurityFilterChain`, `hasRole` / `hasAnyRole`, handler-level role checks |
| G-02 | Session and request security | CSRF tokens, POST logout, `HttpOnly` + `SameSite=Strict` cookie, Tomcat `RemoteIpValve` |
| G-03 | Three-tab Gate Dashboard | Spring MVC (`GuardDashboardController`), responsive CSS card layout |
| G-04 | Header quick check-in | Thymeleaf form with automatic CSRF token |
| G-05 | HTML5 QR code scanner | html5-qrcode 2.3.8 (SRI-pinned), WebRTC camera, Fetch API |
| G-06 | Verify-then-confirm check-in protocol | Shared server-side validator (`checkInBlocker`), regex time-window parsing |
| G-07 | Visual identity verification at the gate | Liveness selfie and group manifest returned with the scan |
| G-08 | Master Booking headcount | Server-validated arrival count |
| G-09 | Check-out by second scan, with cooldown | `java.time.Duration` debounce |
| G-10 | Manual check-in override | Reference ID lookup, role-dependent rules (`SecurityConfig.isDirector`) |
| G-11 | Deny Entry (final gate refusal) | Audited status transition (`DENIED_ENTRY`) |
| G-12 | Check-out and eviction | Immediate status change, feedback email |
| G-13 | Reactive (on-the-ground) biometric banning | Shared ban dialog and endpoint (D-10) |
| G-14 | Security Gallery (banned-visitor UI) | CSS Grid gallery, colour-coded badges, client-side live search |
| G-15 | Non-blocking gate notifications | `CompletableFuture` background email dispatch |
| G-16 | Guard-on-duty attribution | Duty roster lookup by date and shift |
| G-17 | Automatic end-of-day sweeps and no-show marking | Spring `@Scheduled` (17:30, 23:59) |

### 3.2 Detailed Specification

#### G-01 Role-Based Access Control (RBAC)
- **Technology Used:** A Spring Security `SecurityFilterChain` with ordered `requestMatchers` (`hasRole("DIRECTOR")`, `hasAnyRole("GUARD", "DIRECTOR")`), handler-level checks (`SecurityConfig.isDirector()`), and a custom `AuthenticationEntryPoint`.
- **How It Works:**
  - Authorisation is enforced **on the server for every request**, before any controller runs.
  - Director-only rules come first: approvals, rejections, pending counts, KYC review, Force Approve, deletions, lifting bans, staff, slots, CMS, exports and zones. They are followed by the general staff rule for gate operations.
  - **Responses:**

    | Situation | Response |
    |---|---|
    | Guard requests a Director-only URL | **HTTP 403** with a branded "Access Restricted" page |
    | Guard opens the full manifest (`/admin`) | **Redirect** to the Gate Dashboard; pending success/error messages carried along |
    | Anonymous request for a staff area | Redirect to the login page |
    | Anonymous request for an unknown public URL | **404** |

  - The sidebar shows only the links the role can use. This is a usability layer; the server rules are the control.
- **Why This Technology:** **Least privilege** must be enforced where it cannot be bypassed, which is on the server, not by hiding buttons. Spring Security's declarative rules are centralised, readable and covered by parameterised tests that assert a 403 for every Director-only route.

#### G-02 Session and Request Security
- **Technology Used:**
  - CSRF synchronizer tokens: hidden `_csrf` fields, and the `X-CSRF-TOKEN` header for Fetch.
  - POST-only logout with session invalidation and cookie deletion.
  - A session cookie flagged `HttpOnly` and `SameSite=Strict`.
  - `server.forward-headers-strategy=native` (Tomcat `RemoteIpValve`) behind the ngrok HTTPS tunnel.
- **How It Works:**
  - Every state-changing gate action (check-in, deny, check-out, ban) must carry the session's CSRF token.
  - The cookie cannot be read by scripts or sent from other websites.
  - Logout destroys the session, so the browser's Back button cannot reopen the dashboard on a shared gate device.
  - Forwarded headers are trusted only from local or private proxies, which keeps redirects on HTTPS and records the real client IP.
- **Why This Technology:** Gate devices are often shared and left unattended, so session-level protections are as important as access rules. These are industry-standard defences against cross-site request forgery, session hijacking and IP spoofing.

#### G-03 Three-Tab Gate Dashboard
- **Technology Used:** Spring MVC (`GuardDashboardController`, `guard.html`) and responsive CSS (the table becomes stacked cards below 768 px; 44-pixel touch targets on `pointer: coarse` devices).
- **How It Works:**

  | Tab | Contents | Order | Row actions |
  |---|---|---|---|
  | **Expected Arrivals** | Today's approved passes | By time-window start | Deny Entry, Ban |
  | **Inside Now** | All checked-in visitors | Newest check-in first | Check Out / Evict, Ban |
  | **Left Today** | Today's checked-out visitors | Newest check-out first | Ban |

  Live counters and the total headcount inside are shown at the top. The controller **loads only these three groups** and shows no contact details, ID photos or approval controls.
- **Why This Technology:** Least privilege is applied to the *data itself*, not only the screens. The card layout lets guards operate one-handed on a phone at the gate.

#### G-04 Header Quick Check-In
- **Technology Used:** A Thymeleaf `th:action` form (Spring Security adds the CSRF token automatically).
- **How It Works:** A Reference ID box in the guard's header bar on every page allows immediate manual processing (G-10) without navigating away. On phones it moves to its own header row.
- **Why This Technology:** It is the fastest path for the most common fallback, a visitor whose QR code cannot be scanned.

#### G-05 HTML5 QR Code Scanner
- **Technology Used:** **html5-qrcode 2.3.8**, loaded from a CDN with a **Subresource Integrity (SHA-256)** hash; the WebRTC camera; the Fetch API with the CSRF header.
- **How It Works:**
  - The device camera is decoded at **20 frames per second** with an adaptive scan box sized to the viewfinder. On touch devices the scanner re-renders when the screen rotates between portrait and landscape.
  - The decoded token is posted to `/admin/api/scan`, and the result card shows the outcome with colour coding: blue = verify, green = success, red = refused.
  - Duplicate submissions are blocked while a request is in progress.
- **Why This Technology:** A browser-based scanner turns any phone or laptop into a gate terminal with **no dedicated hardware or app installation**. The SRI hash ensures that a tampered library from the CDN is refused by the browser.

#### G-06 Verify-Then-Confirm Check-In Protocol
- **Technology Used:** A shared server-side validator (`GuardOperationController.checkInBlocker`), a regex parser for time-window labels, and JSON responses.
- **How It Works:**
  1. **Verify:** the scan looks the booking up by its secure token only. Unknown tokens produce "SECURITY ALERT: Fake or unassigned QR Code". *Nothing is saved at this stage.*
  2. **Validator:** the booking must be **Approved**, its **face must not be banned**, the **date must be today**, and the time must fall within the **booked time window**. Check-in opens **15 minutes early** and closes at the window's last minute.
  3. **Confirm:** the guard compares the person with the photo (G-07), then presses **Accept & Check-In**. The *same validator* runs again on confirm, so a stale screen can never admit someone the scan would now refuse.
- **Why This Technology:** Separating verification from confirmation puts a human identity check between "valid ticket" and "admitted". Re-validating on the server at each step makes the state change atomic and tamper-proof.

#### G-07 Visual Identity Verification at the Gate
- **Technology Used:** The liveness selfie and group manifest returned in the scan response; the staff-only `/uploads/**` route.
- **How It Works:** The scanner card displays the booker's **liveness selfie**, the lead name, the headcount and the **numbered list of companions**, so the guard can match each person's ID against the manifest.
- **Why This Technology:** It closes the loop between online identity verification and physical presence, so the person at the gate must be the person who passed KYC. This is the *physical chokepoint* that complements online screening, especially for group companions, whose faces are not captured online.

#### G-08 Master Booking Headcount
- **Technology Used:** Server-validated input (`confirm-check-in`, `headcount` parameter).
- **How It Works:** For Master Bookings, the guard enters how many guests **actually arrived**, between 1 and the approved group size. The original size is kept in `bookedGroupSize`, and occupancy figures use the real arrival count.
- **Why This Technology:** Large tours rarely arrive complete. Recording actual arrivals keeps live occupancy and analytics accurate.

#### G-09 Check-Out by Second Scan, with Cooldown
- **Technology Used:** `java.time.Duration` comparison against `checkInTime`.
- **How It Works:** Scanning a checked-in visitor's QR code again checks them out. A re-scan within **2 minutes** of check-in is treated as an accidental double scan ("⏳ Cooldown") and does not check the visitor out.
- **Why This Technology:** It allows fast exit processing with the same ticket while preventing a common operator error from corrupting visit records.

#### G-10 Manual Check-In Override
- **Technology Used:** Reference ID lookup (`/admin/manual-checkin`) and role-dependent rules (`SecurityConfig.isDirector()`).
- **How It Works:**
  - When a QR code cannot be scanned, the guard types the Reference ID.
  - A **guard** can admit only an **approved** pass. Admitting a booking that was never approved is a **Director-only** override.
  - Every path still refuses: banned faces, the wrong date, outside the time window, and denied, rejected, cancelled, no-show or checked-out bookings.
  - Typing the ID of a visitor who is already inside checks them out.
  - Manual check-ins are labelled "(Manual)" in the audit trail.
- **Why This Technology:** It gives operational resilience (dead phones, cracked screens) without weakening security or exceeding the guard's authority.

#### G-11 Deny Entry (Final Gate Refusal)
- **Technology Used:** An audited state transition to `DENIED_ENTRY`, with fields for who, when and why.
- **How It Works:**
  - From the scanner card or the Expected Arrivals tab, the guard selects an official reason: ID does not match lead booker; expired or invalid ID; group does not match manifest; unregistered person in group; security concern. Optional notes can be added (up to 500 characters in total).
  - The QR code stops working immediately.
  - A check-in made that same day can also be reversed.
  - The record is **final**: it can never be re-approved, rejected or purged.
- **Why This Technology:** Gate refusals are security events that may need later investigation, so they are stored as permanent, tamper-resistant audit records.

#### G-12 Check-Out and Eviction
- **Technology Used:** An immediate status change (`CHECKED-IN → CHECKED-OUT`) and the feedback email (V-22, V-24).
- **How It Works:** On the **Inside Now** tab, *Check Out / Evict* (after a confirmation dialog) ends the visit at once and records the exit time. The visitor moves to **Left Today**, live occupancy drops, and the feedback email is sent.
- **Why This Technology:** One action covers both a normal departure and an escorted removal, while keeping occupancy figures accurate. These are the safety-critical numbers in an emergency.

#### G-13 Reactive (On-the-Ground) Biometric Banning
- **Technology Used:** The same shared ban dialog and endpoint as the Director (D-10), available on all three gate tabs.
- **How It Works:** A guard can ban a person immediately, for example for misconduct inside the museum, a fake ID found at the gate, or after an eviction. The ban takes effect across the whole system at once: future bookings, approvals and gate attempts by that face are refused silently, and the person's other open bookings are revoked. Guards **cannot lift** bans (D-11).
- **Why This Technology:** Front-line staff witness incidents first and need to act immediately. Restricting the reversal to the Director keeps that power accountable.

#### G-14 Security Gallery (Banned-Visitor UI)
- **Technology Used:** A CSS Grid card gallery (`repeat(auto-fill, minmax(min(100%, 230px), 1fr))`, two columns on phones), colour-coded status badges, client-side live search in JavaScript, and lazy-loaded images.
- **How It Works:**
  - One card per ban, newest first and **mugshot first**, so a guard can match a walk-in at a glance. Each card shows name, ban number, date, reason and expiry.
  - **Badges:** 🔴 **Permanent** (white on red), 🟡 **Temporary** (dark text on PAF gold), ⚪ **Expired** (grey, and the whole card is dimmed).
  - The **live search** filters cards by name as the guard types, with a result counter, and sends nothing to the server.
  - Face vectors are **never displayed**, and mugshots are served only to authenticated staff.
- **Why This Technology:** Recognition at the gate is visual and time-critical. A photo-first gallery with instant search is faster than reading a table. Colour coding communicates ban severity at a glance, and keeping biometric vectors hidden follows data-minimisation principles.

#### G-15 Non-Blocking Gate Notifications
- **Technology Used:** `java.util.concurrent.CompletableFuture.runAsync`, with failure logging.
- **How It Works:** Check-in and check-out emails triggered by the scanner are sent on a background thread. The guard receives the result instantly, while the database change is already committed.
- **Why This Technology:** The gate queue must not wait for the mail server. Asynchronous dispatch keeps throughput high while still recording failures.

#### G-16 Guard-on-Duty Attribution
- **Technology Used:** A duty roster lookup (`StaffSchedule`) by date and shift window.
- **How It Works:** Each check-in and gate denial records the **Gate Guard(s) on shift** according to the roster. If nobody is scheduled, the logged-in account name is recorded with "(Unscheduled)".
- **Why This Technology:** It creates an accountability trail per shift, so every admission can be traced to responsible personnel.

#### G-17 Automatic End-of-Day Sweeps and No-Show Marking
- **Technology Used:** Spring `@Scheduled` cron jobs (17:30, 23:59), using the same time-window parser as the gate.
- **How It Works:**
  - **17:30:** visitors whose window has ended are checked out automatically (labelled "Auto-Sweep") and receive the feedback email.
  - **23:59:** everyone still inside is checked out, then approved bookings that never arrived become `NO_SHOW` and their QR codes are voided.
- **Why This Technology:** It keeps the Inside Now count accurate, so nobody is recorded as being on the base overnight, and it ensures an unused ticket can never be used on a later date.

---

## Appendix: Booking Status Lifecycle (Reference)

| Status | Meaning | Holds a seat? | Set by |
|---|---|:---:|---|
| `PENDING` | KYC checks passed; waiting for the Director | No | `/save` (V-11 to V-13) |
| `PENDING_MANUAL_REVIEW` | Something needs a human look (HITL) | No | `/save` |
| `APPROVED` | Ticket issued | Yes | Director (D-06, D-07) |
| `CHECKED-IN` | Inside the facility | Yes | Guard (G-06, G-10) |
| `CHECKED-OUT` | Visit completed | Yes | Guard (G-09, G-12) or sweep (G-17) |
| `REJECTED` | Not approved, or revoked by a ban | No | Director (D-08) or ban (D-10) |
| `DENIED_ENTRY` | Refused at the gate (final, kept permanently) | No | Guard (G-11) |
| `NO_SHOW` | Approved but never arrived | No | 23:59 sweep (G-17) |
| `CANCELLED_BY_VISITOR` | Cancelled by the visitor | No | Manage Booking (V-20) |
| `CANCELLED_OVERRIDE` | Displaced by a VIP buyout | No | Director (D-09) |
| `CANCELLED` | Emergency closure | No | Director (D-18) |
