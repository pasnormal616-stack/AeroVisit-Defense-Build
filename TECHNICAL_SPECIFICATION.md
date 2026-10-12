# AeroVisit: Technical Specification (Architecture Chapter Reference)

*Taken from the source code on 2026-10-08. Every name below (tables, columns, endpoints, roles, statuses) is copied from the code. Where the paper's draft wording differs from what the system really does, the gap is flagged in §0.*

**Stack:** Spring Boot 3.5.16 (Java), Thymeleaf, Spring Data JPA / Hibernate, MySQL (`aerovisit_db_v2`), Spring Security, ZXing (`com.google.zxing:core` + `javase`), Apache Commons Math 3.6.1, OpenPDF, Apache POI, Tess4J (OCR). A separate **Python FastAPI service** (`AeroVisit-AI/main.py`, `kyc.py`, port 8000) handles the chatbot (Google Gemini), the Random Forest turnout forecast, feedback sentiment analysis (Hugging Face XLM-RoBERTa, runs locally) and selfie/ID-card detection (OpenCV).

---

## 0. Corrections to the draft's assumptions

| Draft assumed | What the code actually has |
|---|---|
| Tables `visitors`, `bookings`, `users`, `blacklisted_faces` | A single table, **`visitor`**, holds both the visitor and the booking. There is **no users table**: staff accounts are in-memory. The blacklist table is **`blacklist`** and is keyed by **email**, not by face. |
| Roles `ROLE_ADMIN`, `ROLE_GUARD`, `ROLE_VISITOR` | There are only two roles: **`ROLE_GUARD`** and **`ROLE_DIRECTOR`**. Visitors never log in. |
| QR flags `EXPIRED` / `USED` | No such flags exist. Validity comes from the booking's **`status`** column plus a **date and time-window check** (§3). |
| Chatbot answers from MySQL or hardcoded if/else | Sgt. Aero is a **Gemini LLM** (`gemini-3.6-flash`) driven by a **system prompt that contains the knowledge base**. MySQL (`chat_logs`) stores only the conversation memory. |
| One regression model | There are **two** forecasting models: Apache Commons Math `SimpleRegression` (Java, 7-day trend) and a scikit-learn `RandomForestRegressor` (Python, same-day walk-ins). |

---

## 1. Database Schema

Hibernate generates the schema from the JPA entities (`spring.jpa.hibernate.ddl-auto=update`). There is no `schema.sql` or `data.sql`. None of the entities sets an explicit `@Table` name, so Spring Boot's default naming applies: class and field names in camelCase become snake_case tables and columns. The MySQL types listed are what Hibernate 6 generates for each Java type. Every table has a primary key `id BIGINT AUTO_INCREMENT`, except `portal_settings`.

### 1.1 `visitor`: visitor and booking record (`model/Visitor.java`)

| Column | Java type | MySQL type | Notes |
|---|---|---|---|
| id | Long | BIGINT PK | auto-increment |
| name | String | VARCHAR(255) | copied from `full_name` if empty |
| full_name | String | VARCHAR(255) | "Last Name, First Name MI" |
| email | String | VARCHAR(255) | |
| phone | String | VARCHAR(255) | |
| contact_number | String | VARCHAR(255) | |
| visitor_type | String | VARCHAR(255) | |
| sex | String | VARCHAR(255) | |
| age | Integer | INT | |
| address | String | VARCHAR(255) | |
| affiliation | String | VARCHAR(255) | |
| special_consideration | String | VARCHAR(255) | PWD / senior, etc. |
| visit_date | LocalDate | DATE | |
| visit_time | LocalTime | TIME(6) | |
| time_window | String | VARCHAR(255) | e.g. `Morning (08:00 AM - 12:00 PM)` |
| purpose | String | VARCHAR(255) | `Educational Tour (Master Booking Request)` marks a Master Booking |
| status | String | VARCHAR(255) | default `PENDING` (lifecycle in §3.3) |
| reference_number | String | VARCHAR(255) | legacy `PAF-#####` |
| reference_id | String | VARCHAR(20) UNIQUE | Booking Reference ID `PAF-XXXXXX` (SecureRandom) |
| secure_token | String | VARCHAR(255) UNIQUE | random UUID, **the QR payload** |
| rejection_reason | String | VARCHAR(500) | |
| id_image_url | String | VARCHAR(255) | file name of the anonymised ID/selfie photo |
| qr_code_url | String | VARCHAR(255) | `QR_{id}.png` |
| checked_in_by | String | VARCHAR(255) | name of the guard on duty |
| check_in_time | LocalDateTime | DATETIME(6) | |
| check_out_time | LocalDateTime | DATETIME(6) | |
| entry_denied_by | String | VARCHAR(255) | |
| entry_denied_at | LocalDateTime | DATETIME(6) | |
| entry_denial_reason | String | VARCHAR(500) | |
| reminder_sent | boolean | BIT(1) | |
| rating | Integer | INT | 1–5 stars |
| feedback | String | VARCHAR(500) | |
| zone_name | String | VARCHAR(60) | |
| topic_category | String | VARCHAR(30) | from the Python `/analyze` endpoint |
| feedback_sentiment | String | VARCHAR(20) | from the Python `/analyze` endpoint |
| group_size | int | INT | default 1 |
| booked_group_size | Integer | INT | original size when a Master Booking arrives short |
| event_justification | String | VARCHAR(1000) | required for Master Booking |
| companion_names | String | TEXT | one name per line |
| created_at | LocalDateTime | DATETIME(6) | `@CreationTimestamp`, not updatable |

### 1.2 `blacklist` (`model/Blacklist.java`)
- `id` BIGINT PK
- `email` VARCHAR(255): the key used to match blocked visitors
- `reason` VARCHAR(255)
- `block_type` VARCHAR(255): `TEMPORARY` or `PERMANENT`
- `expiry_date` DATE: null for permanent blocks

### 1.3 `booking_slot` (`model/BookingSlot.java`)
- `id` BIGINT PK
- `slot_date` DATE
- `time_window` VARCHAR(255)
- `max_capacity` INT

### 1.4 `time_window_config` (`model/TimeWindowConfig.java`)
- `id` BIGINT PK
- `name` VARCHAR(40) NOT NULL
- `start_time` TIME(6) NOT NULL
- `end_time` TIME(6) NOT NULL

### 1.5 `museum_closure` (`model/MuseumClosure.java`)
- `id` BIGINT PK
- `closure_date` DATE NOT NULL UNIQUE
- `reason` VARCHAR(255) NOT NULL

### 1.6 `staff_schedule`: duty roster (`model/StaffSchedule.java`)
- `id` BIGINT PK
- `staff_name` VARCHAR(255)
- `role` VARCHAR(255): e.g. `Gate Guard`, `Tour Guide`
- `duty_date` DATE
- `shift_time` VARCHAR(255)
- `archived` BIT(1) NOT NULL DEFAULT false
- `roster_notified` BIT(1) NOT NULL DEFAULT false

### 1.7 `museum_employee`: staff directory (`model/MuseumEmployee.java`)
- `id` BIGINT PK
- `name`, `role`, `email`, `working_days`, `default_shift` VARCHAR(255)
- `job_title` VARCHAR(100)

### 1.8 `museum_zone` (`model/MuseumZone.java`)
- `id` BIGINT PK
- `name` VARCHAR(60) NOT NULL UNIQUE
- `is_active` BIT(1) NOT NULL

### 1.9 `admin_notification`: header bell (`model/AdminNotification.java`)
- `id` BIGINT PK
- `message` VARCHAR(255) NOT NULL
- `type` VARCHAR(40) NOT NULL
- `target_url` VARCHAR(255)
- `is_read` BIT(1) NOT NULL
- `created_at` DATETIME(6) NOT NULL

### 1.10 Website content tables (CMS)
- **`announcement`**: `id`, `title` VARCHAR(120), `message` VARCHAR(2000), `announcement_date` DATE, `active` BIT(1)
- **`guideline`**: `id`, `icon` VARCHAR(40), `title` VARCHAR(80), `description` VARCHAR(1000), `display_order` INT
- **`exhibit_photo`**: `id`, `filename` VARCHAR(80) UNIQUE, `upload_date` DATETIME(6)
- **`portal_settings`** (a single row, `id` fixed at 1): `promo_video_url` VARCHAR(200), `facebook_url` VARCHAR(300), `instagram_url` VARCHAR(300), `x_url` VARCHAR(300), `contact_email` VARCHAR(150)

### 1.11 `chat_logs`: created by the Python service, not JPA (`AeroVisit-AI/main.py`)
- `id` BIGINT AUTO_INCREMENT PK
- `session_id` VARCHAR(255) NOT NULL
- `role` VARCHAR(10) NOT NULL: `user` or `model`
- `message` TEXT NOT NULL
- `timestamp` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
- Index `idx_chat_logs_session (session_id, timestamp)`

> `MuseumPrediction.java` is a plain DTO, not an entity, so it has no table.

---

## 2. API Endpoints and Payloads

Most endpoints are server-rendered Thymeleaf forms: they accept `application/x-www-form-urlencoded` or `multipart/form-data` and answer with a **302 redirect** plus a flash message. The gate scanner endpoints are AJAX and return **JSON**.

### 2.1 Visitor booking submission

| Method | URL | Access | Purpose |
|---|---|---|---|
| POST | `/otp/send` | public | Emails a 6-digit code (valid 5 minutes). Param `email`. Returns JSON `{ok, message, retryAfter}`; 429 during the cooldown. |
| POST | `/otp/verify` | public | Params `email`, `code`. Returns JSON `{ok, message, retryAfter}`. |
| **POST** | **`/save`** | public | **Booking submission** (`VisitorPortalController.saveVisitor`) |

**`POST /save` request (multipart/form-data):** the `Visitor` fields bound by `@ModelAttribute` (`fullName`, `email`, `contactNumber`, `sex`, `age`, `address`, `affiliation`, `visitorType`, `specialConsideration`, `visitDate`, `timeWindow`, `purpose`, `groupSize`, `companionNames`, `eventJustification`), plus:
- `idImage` (file, JPG or PNG): a selfie holding the ID
- `isMasterBooking` (boolean, default false)
- `companionCsv` (file, optional, Master Booking only) and repeated `companionManual` values

**Server-side processing, in order:**
1. Reject the request if the date is an emergency closure.
2. Reject it unless the email was OTP-verified.
3. Reject it if the email is on the blacklist (an expired temporary block is deleted instead).
4. Reject a duplicate booking for the same email and date.
5. Reject the date if a VIP buyout holds it.
6. Check capacity: the seats held by `APPROVED`, `CHECKED-IN` and `CHECKED-OUT` bookings plus this group must fit in `booking_slot.max_capacity`. A date or window with no slot is refused.
7. Validate the companion manifest.
8. Store the photo under an anonymous UUID file name.
9. Run KYC: the AI service checks the selfie for a face and an ID card, then Tess4J OCR matches the name. The result sets `status` to `APPROVED` or `PENDING_MANUAL_REVIEW`.
10. Generate `reference_id`.
11. Save under a row lock (`SeatReservationService`).
12. If the booking was auto-approved, generate the QR code and email it. Otherwise send a "pending" email.

**Response:** 302 to `/success-page` on success. On failure, 302 to `/` with a flash `error` message.

Related public endpoints: `GET /check-status`, `GET /manage-booking`, `POST /manage-booking/login | update | cancel | logout`, `GET /leave-feedback`, `POST /submit-feedback`.

### 2.2 Admin approval and rejection

| Method | URL | Role | Request | Effect |
|---|---|---|---|---|
| POST | `/admin/approve/{id}` | GUARD, DIRECTOR | path `id` | Allowed only from `PENDING`, `PENDING_MANUAL_REVIEW` or `VERIFIED`. Re-checks the blacklist, that the slot exists, and seat capacity under a row lock. Sets `APPROVED`, generates `QR_{id}.png`, emails the QR. A Master Booking also cancels other approved visitors that day (`CANCELLED_OVERRIDE`). |
| POST | `/admin/approve-override/{id}` | **DIRECTOR only** | path `id` | "Force Approve": skips the seat-count check only. The blacklist and slot-existence checks still apply. |
| POST | `/admin/reject-visitor` | GUARD, DIRECTOR | `id`, `presetReason`, `additionalNotes` (optional) | Sets `REJECTED`, stores `rejection_reason`, sends the rejection email. |
| POST | `/admin/reject/{id}` | GUARD, DIRECTOR | path `id` | Quick reject: sets `REJECTED` and clears `qr_code_url`. |
| POST | `/api/booking/verify-id` | GUARD, DIRECTOR | `visitorId`, `idImage` (file) | Re-runs OCR verification. Returns plain text; HTTP 409 if the booking is not pending. |

All of these refuse to act on `DENIED_ENTRY` records, and the reject actions refuse visitors who are `CHECKED-IN`, `CHECKED-OUT` or `NO_SHOW`. **Response:** 302 to `/admin` with a flash `success` or `error` message.

### 2.3 Guard QR scanning at the gate (two-step: verify, then confirm)

| Method | URL | Role | Request | Response (JSON) |
|---|---|---|---|---|
| GET | `/admin/scanner` | GUARD, DIRECTOR | none | Scanner page (the browser camera decodes the QR) |
| **POST** | **`/admin/api/scan`** | GUARD, DIRECTOR | `qrData` = decoded QR text | `{status, message, imageUrl, visitorName, leadName, headcount, companions}`; when `status="ready"` it also returns `visitorId` and `masterBooking` |
| POST | `/admin/api/confirm-check-in` | GUARD, DIRECTOR | `visitorId`, `headcount` (Master Booking only) | `{status: "success" or "error", message}`; sets `CHECKED-IN`, `checked_in_by`, `check_in_time` |
| POST | `/admin/api/deny-entry` | GUARD, DIRECTOR | `visitorId`, `reason` | `{status, message}`; sets `DENIED_ENTRY` (final) |
| POST | `/admin/manual-checkin` | GUARD, DIRECTOR | `referenceId` (e.g. `PAF-A7X92K`) | 302 redirect; fallback when no QR is available |

`status` is one of `ready`, `success` or `error`. A scan only *verifies* the pass. Nothing is written to the database until the guard presses Accept (`confirm-check-in`) or Deny. A second scan of a `CHECKED-IN` visitor checks them **out**.

### 2.4 Other endpoints (for completeness)
- **Chatbot:** `GET /api/chatbot/ask?message=…&session_id=…` (public) returns `{reply}`. The limit is 10 requests per IP per minute; over the limit it answers 429 with a `Retry-After` header.
- **Zones:** `GET /api/zones` (public); `GET /api/zones/all`, `POST /api/zones`, `PATCH /api/zones/{id}` (DIRECTOR).
- **Notifications:** `GET /api/notifications`, `POST /api/notifications/mark-read` (GUARD, DIRECTOR).
- **Director:** `/admin/analytics`, `/admin/report` (PDF), `/admin/export-excel`, `/admin/staff/**` (roster, including `ai-preview` and `ai-deploy`), `/admin/slots/**`, `/admin/content/**`, `/admin/employee/**`, `/admin/delete/{id}`, `/admin/unblock/{id}`.
- **Python AI service (internal, `localhost:8000`):** `GET /predict`, `GET /analyze`, `POST /chat`, `POST /retrain`, `POST /kyc/extract-id`.

---

## 3. QR Code Logic (ZXing)

### 3.1 Generation (`util/QRCodeGenerator.generateQRCode(String text, String filePath)`)
- `new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 250, 250)` produces a `BitMatrix`, and `MatrixToImageWriter.writeToPath(..., "PNG", path)` writes it to disk.
- The file is written to `uploads/QR_{visitorId}.png`. Its name is stored in `visitor.qr_code_url` and the image is attached to the approval email.
- It is generated in `GuardOperationController.approveVisitor`, `forceApproveVisitor`, and in `VisitorPortalController.saveVisitor` when KYC auto-approves.

### 3.2 Exact payload
**A bare UUID v4 string**: the visitor's `secure_token` (`java.util.UUID.randomUUID().toString()`, e.g. `3f2b9c1e-…`). It is **not encrypted JSON**, and it does not contain the booking reference or any personal data. The UUID is an opaque, unguessable lookup key (122 random bits). The column is `UNIQUE`. The code notes that an older `ID:x | Name:y` plain-text format was removed because a free QR generator could forge it.

### 3.3 Validation logic (`GuardOperationController.scanVisitorAjax` + `checkInBlocker`)
The system has **no `EXPIRED` or `USED` flag**. A scan is judged by the `status` column and by date/time rules, in this order:

1. `repo.findBySecureToken(qrData)` finds no match → **"SECURITY ALERT: Fake or unassigned QR Code."**
2. `status == "CHECKED-OUT"` → refused (**the "used" case**).
3. `status == "REJECTED"` → refused.
4. `status == "DENIED_ENTRY"` → refused permanently.
5. `status == "CHECKED-IN"` and less than 2 minutes since check-in (`CHECKOUT_COOLDOWN = Duration.ofMinutes(2)`) → refused (debounce).
6. `status == "CHECKED-IN"` → the visitor is **checked out**: `CHECKED-OUT`, `check_out_time` is set, and the feedback email is sent.
7. Otherwise `checkInBlocker(v)` must return null:
   - `status` must be exactly `"APPROVED"`. This covers `PENDING`, `NO_SHOW` and all `CANCELLED*` states.
   - The email must not be blacklisted on the scan date (`Blacklist.blocksOn(LocalDate.now())`).
   - **Date rule:** `LocalDate.now().equals(v.getVisitDate())`. A pass is valid only on its visit date.
   - **Time-window rule** (`timeWindowBlocker`): the start and end times are parsed from the `time_window` label. Check-in opens `EARLY_CHECK_IN_MINUTES = 15` minutes before the start and closes at the end minute (inclusive).
   - If every check passes → `status: "ready"`, and the guard confirms or denies.

**How a pass effectively "expires":** the scheduled job `AeroVisitAutomationEngine.markNoShows`, run by `executeNoShowSweep` (cron `0 59 23 * * *`, 11:59 PM), changes every `APPROVED` booking whose window has ended without a check-in to **`NO_SHOW`** and clears `qr_code_url`. From then on the scan rejects the pass because its status is no longer `APPROVED`.

**How a pass is "used":** after check-in (`CHECKED-IN`) and check-out (`CHECKED-OUT`, by a second scan, the 5:30 PM sweep, or the 11:59 PM sweep), any further scan is refused. Ending in `DENIED_ENTRY` also blocks the pass permanently. The 2:00 AM retention job deletes QR and ID images 30 days after the visit date.

**Full `status` lifecycle:** `PENDING`, `PENDING_MANUAL_REVIEW`, `VERIFIED` → `APPROVED` → `CHECKED-IN` → `CHECKED-OUT`. Terminal states: `REJECTED`, `DENIED_ENTRY`, `NO_SHOW`, `CANCELLED`, `CANCELLED_BY_VISITOR`, `CANCELLED_OVERRIDE`. Only `PENDING`, `PENDING_MANUAL_REVIEW` and `VERIFIED` can be approved (`Visitor.APPROVABLE_STATUSES`).

---

## 4. Chatbot Data Source (Sgt. Aero)

**Architecture:** a retrieval-free LLM. The knowledge base is written into the system prompt, and MySQL provides conversation memory. **Answers do not come from a MySQL entity, and they are not hardcoded switch/if-else or HashMap replies.**

Request path:
`chat widget` → `GET /api/chatbot/ask` (`chatbot/AiAdvisorController`, rate limit 10 per minute per IP) → `chatbot/AiAdvisorService.getAiResponse()` → HTTP `POST http://localhost:8000/chat` with JSON body `{message, session_id}` → `AeroVisit-AI/main.py :: chat_with_bot()` → Google Gemini.

On the Python side:
- **Model:** `google-genai` client, `model='gemini-3.6-flash'`, `temperature=0.3`.
- **Knowledge source:** the `persona_instruction` string, passed as `system_instruction`. It defines the Sgt. Aero persona and scope boundaries and holds a static **"Museum Knowledge Base" section**: base rules (free admission, dress code, no food or large bags), the location (Villamor Air Base), emergency exits, ID requirements, PWD/senior access, aircraft specifications (F-5A, UH-1H, Fokker F27), the booking steps and the meaning of each booking status. The code labels this section "(RAG)", but no retrieval happens: the text is static and sent with every chat.
- **Live context injection:** before each message, the service adds the current Manila weather (Open-Meteo API) and the Random Forest walk-in forecast for the day as an "invisible telemetry" prefix.
- **Memory:** `chat_logs` table. When a session starts, the last 6 rows for that `session_id` are loaded as chat history. Each exchange appends a `user` row and a `model` row. Up to 500 sessions are cached in memory and the least recently used one is dropped first.
- **Guardrails (the only hardcoded responses):** a `FORBIDDEN_KEYWORDS` list (`ignore`, `bypass`, `system prompt`, `jailbreak`, `act as`, …) returns a fixed "SECURITY ALERT" reply without calling Gemini. Java returns fixed fallback strings when the service is offline or rate-limited (e.g. *"Sgt. Aero is currently away from the comms terminal…"*).

---

## 5. Analytics and Regression Model

### 5.1 Apache Commons Math `SimpleRegression` (Java, linear trend)
- **Class:** `com.paf.aerovisit.controller.AdminDashboardController`
- **Methods:** `showAnalytics(Model)` (`GET /admin/analytics`, forecast chart) and the PDF report handler for `GET /admin/report` (7-day forecast table). Both build the same model:

```java
SimpleRegression regression = new SimpleRegression();
int dayIndex = 1;
for (String date : stats.keySet()) {          // last 30 days, oldest first
    regression.addData(dayIndex++, stats.get(date));
}
for (int i = 1; i <= 7; i++) {                // next 7 days
    int predicted = (int) Math.max(0, Math.round(regression.predict(dayIndex++)));
}
```

- **x (independent variable):** `dayIndex`, a sequential day number from 1 to 30 covering the last 30 calendar days. It is **not a database column**; the code derives it from the ordering of `visitor.visit_date`.
- **y (dependent variable):** for each date, **`SUM(GREATEST(1, visitor.group_size))`** over rows whose `visitor.visit_date` equals that date and whose `visitor.status` is one of `APPROVED`, `CHECKED-IN`, `CHECKED-OUT` or `NO_SHOW`. Dates with no bookings count as 0.
- **Output:** predictions for x = 31…37 (the next 7 days), rounded and floored at 0. This is ordinary least-squares linear regression of daily booked headcount against time.

### 5.2 Random Forest turnout forecast (Python, scikit-learn)
- **Location:** `AeroVisit-AI/main.py`: `RandomForestRegressor(n_estimators=100, random_state=42)`, trained in `train_ai_engine()` and served by `GET /predict`.
- **Called from Java by** `service/PredictiveAdvisorService.fetchTurnoutPrediction(dayOfWeek, weather, preBooked)` for the dashboard briefing. `scheduler/MLOpsScheduler.auditDailyAiAccuracy()` (11:59 PM) logs the actual turnout against the prediction and flags any error above 20%.
- **Features (X):** `[day_of_week, weather_severity]`
  - `day_of_week` = `WEEKDAY(visitor.visit_date) + 1` (1 = Monday … 7 = Sunday)
  - `weather_severity` = 1–4 (clear, light rain, heavy rain, typhoon). At prediction time it comes from the live Open-Meteo weather code.
- **Target (y):** `COUNT(*)` of `visitor` rows with `status IN ('CHECKED-IN','CHECKED-OUT')`, grouped by `visit_date`.
- **Training data:** it retrains on live data once there are at least 5 dates. Until then it uses a built-in baseline of 14 hand-made rows.
- **Output:** `walk_ins` (prediction), `min_walk_ins` and `max_walk_ins` (±1.5 × the standard deviation across the trees), `confidence_percentage` (clamped between 55 and 98), `total_prediction = walk_ins + pre_booked`, and a rule-based staffing briefing (guides = max(2, total ÷ 40), guards = max(2, total ÷ 100), plus RED/YELLOW conditions).

> ⚠️ **Methodology caveat for the paper:** when the forest retrains on live data, `train_ai_engine()` **assigns each training row a random weather severity** (`random.choices([1,2,3], weights=[0.7,0.2,0.1])`) because no historical weather is stored. The weather feature is therefore not learned from real history. Either describe it that way, or store the weather per visit date before claiming the model learns weather effects.

---

## 6. User Roles (Spring Security)

Source: `config/SecurityConfig.java`. Accounts live in an `InMemoryUserDetailsManager`; there is no user table. Passwords are BCrypt-hashed and read from the `AEROVISIT_GUARD_PASSWORD` and `AEROVISIT_DIRECTOR_PASSWORD` environment variables. If either is unset, a random one-time password is generated at startup.

| Username | `.roles(...)` | Granted authority string | Access |
|---|---|---|---|
| `guard` | `"GUARD"` | **`ROLE_GUARD`** | `/admin/**` (dashboard, approve/reject, scanner, blacklist), `/uploads/**`, `/api/notifications/**` |
| `director` | `"DIRECTOR"` | **`ROLE_DIRECTOR`** | Everything the guard can access, plus `/admin/staff/**`, `/admin/slots/**`, `/admin/report`, `/admin/export-excel`, `/admin/content/**`, `/admin/approve-override/**`, `/admin/employee/**`, `/admin/delete/**`, `/admin/unblock/**`, and writes to `/api/zones` |

- **Visitors are anonymous:** there is no `ROLE_VISITOR`. Public routes are opened with `permitAll()` (`/`, `/save`, `/check-status`, `/manage-booking/**`, `/leave-feedback`, `/submit-feedback`, `/api/chatbot/**`, `POST /otp/send` and `/otp/verify`, `GET /api/zones`). A visitor reaches Manage Booking with **email + Booking Reference ID**, held in the HTTP session and not as a Spring Security principal.
- Login is form-based at `/login` and redirects to `/admin` on success. Logout is `/logout`. Session cookies are `SameSite=Strict` and `HttpOnly`. CSRF protection is disabled.
- Note: `/admin/analytics` checks the **username** (`"director"`) in code rather than the role.
