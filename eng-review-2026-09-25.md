# Eng Review — aerovisit, branch `appmod/java-upgrade-20260714163731`

Target: current branch diff (committed `main..HEAD` = df5aa5b, plus uncommitted working tree), selected by user at the scope gate.
Reviewer: /plan-eng-review (Claude, degraded mode: gstack helpers not installed on this machine).
Date: 2026-09-25. Report file chosen by user (D2): this file, outside the git repo.

## Setup answers
- D1 Design doc: skipped; review against the branch's evident goals (user answer "Skip, review now").
- D2 Report file: `AeroVisit_Project/eng-review-2026-09-25.md` (user answer "Project folder").

## Scope record
feature answers: none proposed (no feature cuts); structure: B "Smaller arrangement" (D3); accepted scope: all current features kept, delivered as two commits: (1) security + Booking Reference IDs (SecurityConfig, WebConfig, GuardOperationController, IdVerificationController, VisitorPortalController, Visitor, VisitorRepository, EmailService, AeroVisitAutomationEngine, ReferenceIdGenerator, application.properties, .gitignore, .env.example, plus the register.html form/sitekey lines), (2) UI redesign (remaining template changes, templates/fragments/, static/, GlobalModelAdvice); pending remedies: all findings below.

## Scope Challenge findings
1. SC1 [P1] (confidence: 9/10) `src/main/resources/application.properties` in commit df5aa5b — the committed file contains `spring.mail.password=lfzn …` (Gmail app password) and `openweathermap.api.key=5cea…` (OpenWeather key). `git log --all -S` finds both in df5aa5b, and `origin/appmod/java-upgrade-20260714163731` exists, so they are on GitHub. The working-tree change moves them to env vars, but that does not remove them from history. Anyone who can read the repo can send mail as the museum account.
2. Reuse note (not a defect): `VisitorRepository.findBySecureToken` is added by this branch but `GuardOperationController.java:51` still scans `repo.findAll()`; carried into Performance.
3. TODOS.md: none exists in the repo. Distribution: no new build artifacts.

## Decision ledger

### R1: Handle the leaked Gmail app password and OpenWeather key
Finding: SC1, P1, confidence 9/10, application.properties @ df5aa5b, reviewer: Claude
Plan baseline: original proposal (branch): move secrets to env vars; nothing about the already-pushed values.
Runtime evidence: `git log --all --oneline -S "lfzn dcfd"` → df5aa5b; same for the OWM key; remote branch origin/appmod/java-upgrade-20260714163731 exists. Repo visibility unknown (gh CLI not installed).
Comparison grid:
| Choice | Current | A | B | C |
|---|---|---|---|---|
| R1 leaked keys | still valid, in pushed history | revoke Gmail app password + regenerate OWM key; history untouched | revoke/regenerate AND rewrite git history + force-push | leave as is |
Question D4:
D4 — What should happen to the Gmail password and weather key already pushed to GitHub?
Project/branch/task: aerovisit, branch appmod/java-upgrade-20260714163731.
ELI10: An older commit on this branch (df5aa5b) has your real Gmail app password and OpenWeather key typed into application.properties, and that commit is on GitHub. The new code reads them from environment variables instead, but the old commit still shows them to anyone who can see the repo. With the Gmail one, someone could send email as your museum account.
Stakes if we pick wrong: If the keys stay valid, anyone who has seen the repo can keep using them no matter what the new code does.
Recommendation: A because revoking makes the leaked values useless, which is what actually protects you; rewriting history is risky and optional once they're dead.
Completeness: A=9/10, B=10/10, C=2/10
Pros / cons:
A) Revoke and replace the keys (recommended)
  ✅ The leaked values stop working immediately, wherever they've been copied.
  ✅ Nothing about git changes, so no risk to your branches or teammates' clones.
  ❌ The dead values stay visible in history, which looks untidy to a capstone grader.
B) Revoke keys and also scrub git history
  ✅ Keys are dead AND removed from the branch history on GitHub.
  ✅ Cleanest result for a public or graded repo.
  ❌ Needs a force-push that rewrites commits; anyone else's clone breaks (human: ~1h / CC: ~15 min, plus your approval).
C) Leave them as they are
  ✅ No work at all right now.
  ✅ Fine only if the repo is private and nobody else has access.
  ❌ The Gmail password stays usable by anyone who has seen or copied the repo.
Net: a few minutes in Google/OpenWeather settings vs. leaving a working password on GitHub.
Header: Leaked keys
Options:
A) Revoke and replace keys (recommended)
✅ The leaked values stop working immediately, wherever they've been copied. ✅ Nothing about git changes, so no risk to your branches or clones. ❌ The dead values stay visible in history. You do the revoking in Google Account > App passwords and the OpenWeather dashboard (human: ~10 min); then put the new values in env vars or .env.
B) Revoke keys and scrub history
✅ Keys are dead AND removed from GitHub history. ✅ Cleanest result for a public or graded repo. ❌ Needs a force-push rewriting commits (human: ~1h / CC: ~15 min); a separate confirmation will be asked before any force-push.
C) Leave as is
✅ No work right now. ✅ Acceptable only if the repo is private with no other readers. ❌ The Gmail password stays usable by anyone who has seen the repo.

State: approved
Actual answer: A "Revoke and replace keys" (D4, user answer 2026-09-25)
Accepted scope: User revokes the Gmail app password (Google Account > App passwords) and regenerates the OpenWeather key, then supplies new values via MAIL_PASSWORD / OPENWEATHER_API_KEY env vars or local .env. No git history rewrite, no force-push.
History: none

## Section 1: Architecture findings
1. A1 [P2] (confidence: 8/10) `EmailService.java:301` — `+ "\"" + feedbackText + "\""` and `:303` `+ visitorName +` put visitor-typed text straight into the director's HTML alert email. Any checked-out visitor can inject links/markup into the director's inbox. Same pattern for names at :50, :182, :227, :262, :333 (emails go to the address the visitor typed).
2. A2 [P3] (confidence: 9/10) `EmailService.java:219` — `"https://diamondlike-ferally-albert.ngrok-free.dev/leave-feedback?token=" + feedbackToken`: the feedback link (now carrying the secure token) is hard-coded to one ngrok tunnel. When the tunnel changes, every link breaks, and the token goes to whoever holds that hostname.
3. Note (no remedy): `SecurityConfig.java:26` `.csrf(csrf -> csrf.disable())` is pre-existing; this branch's `server.servlet.session.cookie.same-site=strict` mitigates cross-site requests. `/uploads/**` is now staff-only; verified only `admin.html:261` (staff page) links to it.

### R2: Escape visitor text in HTML emails
Finding: A1, P2, confidence 8/10, EmailService.java:301/303 (+ :50, :182, :227, :262, :333), reviewer: Claude
Plan baseline: original proposal: no escaping.
Runtime evidence: string concatenation of raw `feedbackText` / `visitorName` / `name` into HTML bodies, read in EmailService.java.
Comparison grid:
| Choice | Current | A | B | C |
|---|---|---|---|---|
| R2 HTML escaping | none | escape all visitor-supplied values in every HTML email with Spring's `HtmlUtils.htmlEscape` + unit test | escape only the director alert (:301, :303) + unit test | no change |
| R1 leaked keys | approved A (D4) | unchanged | unchanged | unchanged |
Question D5:
D5 — Should visitor-typed text be made safe before it goes into HTML emails?
Project/branch/task: aerovisit, branch appmod/java-upgrade-20260714163731.
ELI10: When a visitor leaves feedback, the text is pasted straight into an HTML email to the director. A visitor could type HTML, like a fake "click here to log in" button, and it would show up as real formatting in the director's inbox. Visitor names go into the other emails the same way. Spring already has a one-line function (HtmlUtils.htmlEscape) that turns such text into harmless plain text.
Stakes if we pick wrong: A visitor could plant a convincing phishing link in the director's inbox that looks like it came from the system.
Recommendation: A because it's the same one-line fix in seven places, and fixing only one email leaves the pattern in the rest.
Completeness: A=10/10, B=6/10, C=2/10
Net: ~10 minutes of escaping everywhere vs. leaving an HTML-injection path into staff email.
Header: Email escaping
Options:
A) Escape in all emails (recommended)
✅ Every visitor-supplied value in every HTML email is escaped with Spring's built-in HtmlUtils.htmlEscape (EmailService :50, :182, :227, :262, :301, :303, :333). ✅ Adds a unit test proving `<a href>` in feedback arrives as text. ❌ Touches 7 lines across 6 emails (human: ~30 min / CC: ~5 min).
B) Escape director alert only
✅ Fixes the highest-risk email (the one staff read) at :301 and :303, plus a unit test. ✅ Smallest change. ❌ Names in the visitor-facing emails stay unescaped, so the pattern remains (human: ~15 min / CC: ~3 min).
C) Leave as is
✅ No change. ✅ Visitor-facing emails mostly go to the address the visitor typed themselves. ❌ The director alert stays open to injected links.

State: approved
Actual answer: A "Escape in all emails" (D5, user answer 2026-09-25)
Accepted scope: Escape every visitor-supplied value in every HTML email with `org.springframework.web.util.HtmlUtils.htmlEscape` (EmailService :50, :182, :227, :262, :301, :303, :333), plus a unit test proving HTML in feedback/name arrives as escaped text.
History: none

### R3: Make the feedback link's base URL configurable
Finding: A2, P3, confidence 9/10, EmailService.java:219, reviewer: Claude
Plan baseline: original proposal: hard-coded ngrok hostname.
Runtime evidence: `String feedbackLink = "https://diamondlike-ferally-albert.ngrok-free.dev/leave-feedback?token=" + feedbackToken;` read at EmailService.java:219.
Comparison grid:
| Choice | Current | A | B |
|---|---|---|---|
| R3 feedback base URL | hard-coded ngrok host | property `aerovisit.public-base-url=${PUBLIC_BASE_URL:http://localhost:8081}` used to build the link; add to .env.example; unit test on the built link | no change |
| R1, R2 | approved (D4, D5) | unchanged | unchanged |
Question D6:
D6 — Should the feedback link's web address come from a setting instead of being typed into the code?
Project/branch/task: aerovisit, branch appmod/java-upgrade-20260714163731.
ELI10: The "leave feedback" email links to one specific ngrok address typed into EmailService. Free ngrok addresses change, so when yours does, every feedback link breaks. The link now also carries each visitor's secret token, which would go to whoever ends up with that old address. Reading the address from a setting (like the other keys) means you change it without touching code.
Stakes if we pick wrong: After the ngrok address changes, visitors click dead links and no feedback comes in; nothing warns you.
Recommendation: A because it's a small change and matches how every other setting in this branch already works.
Completeness: A=9/10, B=3/10
Net: one new setting vs. a link that silently breaks whenever the tunnel changes.
Header: Feedback URL
Options:
A) Read it from a setting (recommended)
✅ Link built from `aerovisit.public-base-url` (env var PUBLIC_BASE_URL, default http://localhost:8081), documented in .env.example. ✅ Unit test checks the built link uses the setting and the token. ❌ You must set PUBLIC_BASE_URL wherever the app runs publicly (human: ~20 min / CC: ~5 min).
B) Leave it hard-coded
✅ No change. ✅ Works as long as that exact ngrok address stays yours. ❌ Breaks silently, and needs a code edit, whenever the address changes.

State: approved
Actual answer: A "Read it from a setting" (D6, user answer 2026-09-25)
Accepted scope: Build the feedback link from property `aerovisit.public-base-url=${PUBLIC_BASE_URL:http://localhost:8081}`; document PUBLIC_BASE_URL in .env.example (commented out, like the others); unit test that the link uses the setting and the token.
History: none

## Section 2: Code quality findings
1. C1 [P3] (confidence: 6/10, medium: verify this is actually an issue) `GuardOperationController.java:127-145` — manual check-in's `else` branch admits any status other than CHECKED-OUT / REJECTED / CHECKED-IN, so PENDING, PENDING_MANUAL_REVIEW and VERIFIED bookings get in. The QR path now requires `"APPROVED".equals(v.getStatus())` (:82). The branch comment at :85 says "Same rule as manual check-in", but the rules now differ. May be intended as a staff override.
2. Note (no remedy proposed): `EmailService.java:24` and `:72`, the old 4-argument overloads, now have no callers (grep of src/main/java). Harmless; not worth a change on its own.

### R4: Should manual check-in require an approved booking?
Finding: C1, P3, confidence 6/10, GuardOperationController.java:127-145 vs :82, reviewer: Claude
Plan baseline: original proposal: QR requires APPROVED + today's date; manual requires today's date + time window only.
Runtime evidence: code read at GuardOperationController.java:113-157 and :82.
Comparison grid:
| Choice | Current | A | B |
|---|---|---|---|
| R4 manual check-in status rule | any non-final status admitted | keep as a deliberate override; fix the misleading comment at :85 only | also deny non-APPROVED in manual check-in, same message as QR; unit test both paths |
| R1-R3 | approved | unchanged | unchanged |
Question D7:
D7 — Should guards be able to manually check in a booking that isn't approved yet?
Project/branch/task: aerovisit, branch appmod/java-upgrade-20260714163731.
ELI10: This branch made QR scanning refuse bookings that aren't APPROVED. But the "manual check-in" button on the guard dashboard still lets in bookings that are PENDING or waiting for ID review. That might be on purpose (a guard override when the system is slow) or an oversight. A code comment claims the two follow the same rule, and they don't.
Stakes if we pick wrong: Either an unvetted visitor can be let in with one click, or guards lose a way to handle a stuck booking at the gate.
Recommendation: A because a manual override by logged-in staff is a normal thing to want; the real bug is the comment that says otherwise. This is a taste call about museum policy.
Note: options differ in kind, not coverage — no completeness score.
Net: guard flexibility vs. one consistent rule for entry.
Header: Manual check-in
Options:
A) Keep override, fix comment (recommended)
✅ Guards can still admit a pending visitor manually (e.g. system slow, walk-in confirmed by phone). ✅ Only the misleading comment at :85 changes (human: ~5 min / CC: ~1 min). ❌ An unapproved booking can still enter if a guard clicks the button.
B) Require APPROVED for manual too
✅ One entry rule for both paths; pending bookings are refused with the same message as the QR scan. ✅ Unit tests cover the refusal on both paths. ❌ Guards must first approve the booking in the dashboard before admitting (human: ~30 min / CC: ~5 min).

State: approved
Actual answer: A "Keep override, fix comment" (D7, user answer 2026-09-25)
Accepted scope: Manual check-in stays a staff override (status rule unchanged). Rewrite the comment at GuardOperationController.java:85 so it no longer claims the QR and manual rules match (e.g. "QR is only valid on the scheduled date; manual check-in is a staff override").
History: none

## Section 3: Test review
Framework: Maven + JUnit 5 via `spring-boot-starter-test` (pom.xml:84). No `spring-security-test`, no in-memory DB. The only test is `AerovisitApplicationTests.contextLoads()`, which needs a running MySQL.

```
CODE PATHS                                                  USER FLOWS
[+] SecurityConfig                                          [+] Public booking
  ├── [GAP] [→E2E] /uploads/** anonymous → login redirect     ├── [GAP] [→E2E] register → reCAPTCHA → save → Reference ID email
  ├── [GAP] GUARD → /admin/employee/add, /admin/delete/** 403 ├── [GAP] reCAPTCHA secret blank → rejected
  ├── [GAP] DIRECTOR → same routes allowed                    └── [GAP] crafted POST with status=APPROVED ignored (@InitBinder)
  └── [GAP] resolvePassword: blank → random, set → used      [+] Manage Booking
[+] VisitorPortalController                                   ├── [GAP] email + correct Ref ID → status page
  ├── /save: [GAP] referenceId always server-generated        ├── [GAP] email only / wrong Ref ID → "not found"
  │          [GAP] safeName strips ../ and odd chars          └── [GAP] legacy booking (null Ref ID) → legacy message
  ├── /check-status: [GAP] blank ref / match / legacy / miss [+] Gate
  ├── /leave-feedback: [GAP] bad token / not CHECKED-OUT      ├── [GAP] [→E2E] QR valid+APPROVED+today → CHECKED-IN
  ├── /submit-feedback: [GAP] bad token, already rated,       ├── [GAP] QR PENDING → denied; QR other date → denied
  │          rating <1 or >5, >500 chars truncated            ├── [GAP] old "ID:x | Name:y" QR → rejected
  └── verifyRecaptcha: [GAP] blank secret, success=false,     └── [GAP] checkout → feedback email carries token link
             timeout → false                                 [+] Feedback
[+] GuardOperationController QR scan (4 branches)  [GAP]        ├── [GAP] token link → form → submit once
[+] Visitor @PrePersist assigns Ref ID             [GAP]        └── [GAP] second submit → "already submitted"
[+] ReferenceIdGenerator format PAF-XXXXXX, alphabet [GAP]
[+] EmailService Ref ID lines, token link           [GAP]
[+] IdVerificationController log line (no PII)      [GAP]

COVERAGE: 0/30 paths tested (0%)  |  Code paths: 0/18  |  User flows: 0/12
QUALITY: ★★★:0 ★★:0 ★:1 (contextLoads)  |  GAPS: 30 (3 E2E)
```
Legend: ★★★ behavior + edge + error | ★★ happy path | ★ smoke check | [→E2E] = needs integration test

REGRESSION RULE: this branch changes existing behavior (login passwords, QR acceptance, feedback links, /check-status, /uploads access) with zero regression coverage. That is CRITICAL. R5 settles how to cover it.

### R5: Regression test contract for the security changes
Finding: T1, P1 (CRITICAL regression gap), confidence 9/10, src/test/java has only AerovisitApplicationTests.java, reviewer: Claude
Plan baseline: original proposal: no new tests.
Runtime evidence: `ls src/test -R` → one file; pom.xml has spring-boot-starter-test only.
Comparison grid:
| Choice | Current | A | B |
|---|---|---|---|
| R5 test coverage | contextLoads only (needs MySQL) | add `spring-security-test` (test scope); `@WebMvcTest` slice tests with mocked repositories/EmailService for SecurityConfig role rules, /check-status (4 cases), /leave-feedback + /submit-feedback (all guard branches), QR scan (4 branches + old format rejected), @InitBinder ignoring status/secureToken/referenceId; plain unit tests for ReferenceIdGenerator, safeName, resolvePassword, verifyRecaptcha blank-secret, EmailService escaping (R2) and link building (R3). No DB needed. | unit tests only: ReferenceIdGenerator, EmailService escaping + link, check-status matching logic; skip controller/security slice tests |
| R1-R4 | approved | unchanged | unchanged |
Question D8:
D8 — How thoroughly should the security changes be covered by tests?
Project/branch/task: aerovisit, branch appmod/java-upgrade-20260714163731.
ELI10: This branch changes who can log in, which QR codes open the gate, who can see uploaded ID photos, and how visitors look up bookings. Right now there's one test, and it only checks the app starts (and needs MySQL running). If someone later breaks one of these rules, nothing would catch it. Spring can test web pages and login rules without a database by faking the database layer.
Stakes if we pick wrong: A future edit could quietly let guards delete staff or let a forged QR through, and you'd only find out at the gate.
Recommendation: A because the security rules are exactly what needs tests, and with AI help the full set is about 30 minutes.
Completeness: A=10/10, B=5/10
Net: ~30 min (CC) for tests on every rule this branch adds vs. testing only the easy helper classes.
Header: Test coverage
Options:
A) Full regression suite (recommended)
✅ Adds spring-security-test (test scope) and web-layer tests with a faked database for every changed rule: role access, /check-status (4 cases), feedback token checks, QR scan branches, mass-assignment guard. ✅ Plus unit tests for ReferenceIdGenerator, file-name cleaning, passwords, reCAPTCHA, email escaping and link. Runs without MySQL. ❌ One new test dependency and ~25 tests to maintain (human: ~2 days / CC: ~30 min).
B) Helper unit tests only
✅ Tests ReferenceIdGenerator, email escaping/link and the booking-lookup matching logic. ✅ No new dependency, fast to write (human: ~3h / CC: ~10 min). ❌ Login/role rules, QR gate and feedback token checks stay untested.

State: approved
Actual answer: A "Full regression suite" (D8, user answer 2026-09-25)
Accepted scope: Add `spring-security-test` (test scope). `@WebMvcTest` slice tests with mocked repositories/EmailService for: SecurityConfig role rules (anonymous /uploads/** blocked; GUARD 403 on director-only routes incl. /admin/employee/add and /admin/employee/delete/**; DIRECTOR allowed), /check-status (blank ref, match, legacy null ref, miss), /leave-feedback and /submit-feedback (bad token, not CHECKED-OUT, already rated, rating out of 1-5, >500 chars truncated), QR scan (CHECKED-IN→out, non-APPROVED denied, other date denied, success, old "ID:x | Name:y" format rejected), @InitBinder ignoring status/secureToken/referenceId. Unit tests for ReferenceIdGenerator (format, alphabet), safeName cleaning, resolvePassword, verifyRecaptcha blank secret, EmailService escaping (R2) and link building (R3). No MySQL needed.
History: none

### Test Plan Artifact (not persisted to ~/.gstack: gstack-slug unavailable; kept here instead)
- Affected routes: `/`, `/save`, `/check-status`, `/leave-feedback`, `/submit-feedback`, `/uploads/**`, `/admin/**` director-only routes, guard QR scan endpoint, `/admin/manual-checkin`.
- Key interactions: register with reCAPTCHA and receive a Reference ID email; Manage Booking lookup with email + Ref ID; QR scan at the gate; checkout → feedback email → submit once.
- Edge cases: legacy booking with null Ref ID; wrong Ref ID; blank reCAPTCHA secret; QR for a PENDING booking or another date; forged old-format QR; second feedback submit; feedback containing HTML (R2).
- Critical paths: public registration end to end; gate check-in by QR; director-only actions refused to GUARD.
- Pending decisions: none.

## Section 4: Performance findings
1. P1 [P3] (confidence: 8/10) `GuardOperationController.java:51` `List<Visitor> allVisitors = repo.findAll();` then a loop comparing `qrData` to each `getSecureToken()`, and `VisitorPortalController.java:320` `List<Visitor> visitors = repo.findAll();` for /check-status. Every gate scan and every public lookup loads the whole visitor table into memory. `VisitorRepository.findBySecureToken` already exists (added by this branch) but the scanner doesn't use it. /check-status is public, so anyone can make the server load the full table repeatedly.
2. Note (out of scope): many other `findAll()` calls in AdminDashboardController / ManagementController are pre-existing staff-only pages; not part of this branch.

### R6: Use indexed lookups for QR scan and /check-status
Finding: P1, P3, confidence 8/10, GuardOperationController.java:51, VisitorPortalController.java:320, reviewer: Claude
Plan baseline: original proposal: full-table scan in both.
Runtime evidence: code read above; `findBySecureToken` present in VisitorRepository.java.
Comparison grid:
| Choice | Current | A | B |
|---|---|---|---|
| R6 lookup method | findAll() + loop in both | scanner uses existing `findBySecureToken`; /check-status uses new `findByEmailIgnoreCase(email)` and applies the same Ref ID / legacy rules to that short list; behavior identical, covered by R5 tests | no change |
| R1-R5 | approved | unchanged | unchanged |
Question D9:
D9 — Should the gate scanner and booking lookup stop loading every visitor from the database?
Project/branch/task: aerovisit, branch appmod/java-upgrade-20260714163731.
ELI10: Each time a guard scans a QR code, or anyone checks a booking status, the app loads every visitor ever registered and searches them one by one. That's fine with 50 visitors, slow with 50,000. The database can find the one matching row directly, and this branch already added the method the scanner needs.
Stakes if we pick wrong: As bookings pile up, the gate scanner slows down; and since /check-status is public, anyone can make the server do that heavy work over and over.
Recommendation: A because it reuses the lookup this branch already added and keeps behavior exactly the same.
Completeness: A=9/10, B=3/10
Net: two small query swaps vs. a scanner that gets slower every month.
Header: Lookups
Options:
A) Use direct lookups (recommended)
✅ Scanner calls the existing `findBySecureToken`; /check-status calls a new `findByEmailIgnoreCase` and keeps the same Ref ID and legacy-booking rules. ✅ Same results, covered by the R5 tests (human: ~1h / CC: ~5 min). ❌ One new repository method.
B) Leave the full-table scans
✅ No change now. ✅ Fine while the museum has a few hundred bookings. ❌ Slows linearly with every booking, and the public page can be used to load the server.

State: approved
Actual answer: A "Use direct lookups" (D9, user answer 2026-09-25)
Accepted scope: QR scan (GuardOperationController.java:51) uses existing `repo.findBySecureToken(qrData)`; /check-status (VisitorPortalController.java:320) uses new `VisitorRepository.findByEmailIgnoreCase(String)` and applies the same Ref ID / legacy-booking rules to that list. Behavior unchanged; covered by R5 tests.
History: none

## Outside Voice
Codex not installed (`CODEX_MODE: not_installed`). Native fallback needs TaskOutput and TaskStop, and TaskOutput is not available in this session, so: Outside voice unavailable. Missing outside coverage; no clean-review credit. Review log not persisted (gstack-review-log missing).

## Final planning decisions

### R7: TODO for legacy bookings without a Reference ID
Finding: carried from the earlier /code-review fix report: bookings created before Reference IDs have `referenceId = null` and can only get the "contact the museum" message at /check-status. Reviewer: Claude.
Plan baseline: original proposal: no backfill (the earlier agent held back because it needs a new email).
Runtime evidence: VisitorPortalController.java /check-status legacy branch; `@PrePersist` in Visitor.java only fills on insert.
TODO detail: What: one-time job that assigns a Ref ID to every visitor with `referenceId IS NULL` and emails it with a short new template. Why: those visitors can't use Manage Booking at all. Pros: closes the gap for every old booking. Cons: one new email template and a one-off batch send; emails every past visitor, including ones whose visit is over (limit to future visit dates). Context: start at VisitorPortalController /check-status legacy branch and EmailService. Depends on: R1 (new Gmail app password must be set before sending).
Comparison grid:
| Choice | Current | A | B | C |
|---|---|---|---|---|
| R7 legacy backfill | not tracked | add to a new aerovisit/TODOS.md | skip | build it now on this branch |
| R1-R6 | approved | unchanged | unchanged | unchanged |
Question D10:
D10 — What should happen with the old bookings that have no Reference ID?
Project/branch/task: aerovisit, branch appmod/java-upgrade-20260714163731.
ELI10: Bookings made before this branch have no Reference ID, so those visitors can't use Manage Booking; they just see "contact the museum". Fixing it means giving each one an ID and emailing it to them, which needs a new email. It isn't urgent if most old visits are already over.
Stakes if we pick wrong: Visitors with upcoming older bookings can't check their status online and may call or email the museum instead.
Recommendation: A because it's real but not blocking, and the email should wait until the new Gmail password (R1) is in place.
Note: options differ in kind, not coverage — no completeness score.
Net: write it down for later vs. drop it vs. build it now.
Header: Old bookings
Options:
A) Add to TODOS.md (recommended)
✅ Creates aerovisit/TODOS.md with this item (what, why, where to start, depends on R1). ✅ Nothing built now; the branch stays focused. ❌ Old upcoming bookings stay without online lookup until someone picks it up.
B) Skip it
✅ No extra work or file. ✅ Fine if almost all old bookings are for past dates. ❌ The gap is forgotten.
C) Build it now on this branch
✅ Every upcoming old booking gets an ID and an email. ✅ Closes the gap before you ship. ❌ New email template + one-off send, which grows this branch (human: ~4h / CC: ~20 min), and it needs the new Gmail password first.

State: approved
Actual answer: A "Add to TODOS.md" (D10, user answer 2026-09-25)
Accepted scope: Created aerovisit/TODOS.md with the legacy Reference ID backfill item (limited to future visit dates, depends on R1). Nothing built.
History: none

Approval readiness: PASS — R1 (D4), R2 (D5), R3 (D6), R4 (D7), R5 (D8), R6 (D9), R7 (D10); scope record (D3). No pending choices.

## NOT in scope
- Git history rewrite / force-push to remove the leaked keys: user chose revoke-only (D4).
- Enabling CSRF protection: pre-existing design choice; SameSite=strict added by this branch mitigates it.
- Other `findAll()` calls in AdminDashboardController / ManagementController: pre-existing, staff-only pages, not touched by this branch.
- Legacy Reference ID backfill: deferred to TODOS.md (D10).
- Removing the unused 4-argument EmailService overloads: harmless, no change proposed.

## What already exists
- `VisitorRepository.findBySecureToken` (added by this branch) is reused for the QR scan (R6) instead of a new method.
- Spring's `HtmlUtils.htmlEscape` (already on the classpath) is used for email escaping (R2); no new library.
- `spring-boot-starter-test` already provides JUnit 5, Mockito and MockMvc; only `spring-security-test` is added (R5).
- The `${ENV:fallback}` + `.env.example` pattern from this branch is reused for PUBLIC_BASE_URL (R3).

## Diagrams
```
Registration → Reference ID → lookup
  POST /save ──@InitBinder allowlist──▶ verifyRecaptcha (fail closed)
      │                                   └─ false → back to form with error
      ▼
  ReferenceIdGenerator (loop until !existsByReferenceId) → repo.save → @PrePersist safety net
      ▼
  email (pending or approved+QR) with Ref ID
  GET /check-status?email&referenceId
      ├─ ref blank → "enter both"
      ├─ email match + ref match → success page
      ├─ email match + ref null (legacy) → "contact the museum"
      └─ otherwise → "not found"

Gate
  QR token ─findBySecureToken (R6)─▶ CHECKED-IN? → check out + feedback email (token link, base URL from R3)
                                    not APPROVED → denied
                                    not today    → denied
                                    else         → CHECKED-IN
  Manual check-in (staff override, R4) → date + time window only
```

## Failure modes
| Path | Realistic failure | Handling / test | User sees |
|---|---|---|---|
| reCAPTCHA verify | Google unreachable / secret blank | fail closed, logged; R5 test | clear error on form |
| Reference ID lookup | legacy booking with null ID | explicit message; R5 test | clear message |
| Feedback link | ngrok host changes | R3 makes it configurable; R5 test on link | today: silent dead link (**critical gap, fixed by R3**) |
| Director alert email | HTML injected in feedback | R2 escaping + test | escaped text |
| QR scan | forged old-format QR | rejected; R5 test | "invalid" at gate |
| Mail send | leaked password revoked (R1) before new one set | exceptions logged, booking still saved | no email received (silent to visitor) |

## Worktree parallelization strategy
Sequential implementation, no parallelization opportunity. All fixes touch the same controllers/EmailService, and tests (T5) depend on the code changes.

## Implementation Tasks
Synthesized from this review's findings. Each task derives from a specific finding above. Run with Claude Code or Codex; checkbox as you ship.

- [ ] **T1 (P1, human: ~10 min / CC: n/a, done by you)** — secrets — Revoke the Gmail app password and regenerate the OpenWeather key; set MAIL_PASSWORD / OPENWEATHER_API_KEY locally
  - Surfaced by: Scope Challenge — SC1, R1
  - Files: none (Google Account, OpenWeather dashboard, local env/.env)
  - Verify: old password rejected by Gmail; app sends a test email with the new one
- [ ] **T2 (P2, human: ~30 min / CC: ~5 min)** — EmailService — Escape visitor text in all HTML emails
  - Surfaced by: Architecture — A1, R2
  - Files: src/main/java/com/paf/aerovisit/service/EmailService.java
  - Verify: unit test: feedback `<a href=x>` arrives as `&lt;a href=x&gt;`
- [ ] **T3 (P3, human: ~20 min / CC: ~5 min)** — EmailService — Build feedback link from `aerovisit.public-base-url`
  - Surfaced by: Architecture — A2, R3
  - Files: EmailService.java, application.properties, .env.example
  - Verify: unit test on built link
- [ ] **T4 (P3, human: ~5 min / CC: ~1 min)** — GuardOperationController — Fix the misleading comment at :85
  - Surfaced by: Code Quality — C1, R4
  - Files: src/main/java/com/paf/aerovisit/controller/GuardOperationController.java
  - Verify: read the comment
- [ ] **T5 (P1, human: ~2 days / CC: ~30 min)** — tests — Full regression suite per R5
  - Surfaced by: Test review — T1 (0/30 paths covered), R5
  - Files: pom.xml (spring-security-test, test scope), src/test/java/com/paf/aerovisit/**
  - Verify: `mvnw test` passes without MySQL for the new tests
- [ ] **T6 (P3, human: ~1h / CC: ~5 min)** — repository — Direct lookups for QR scan and /check-status
  - Surfaced by: Performance — P1, R6
  - Files: GuardOperationController.java, VisitorPortalController.java, VisitorRepository.java
  - Verify: T5 tests still pass
- [ ] **T7 (P2, human: ~1h / CC: ~10 min)** — git — Commit as two commits per D3 (security + Ref IDs, then redesign)
  - Surfaced by: Scope record — D3
  - Files: all changed files, register.html split by hunk
  - Verify: `git log` shows two commits; each builds

## Unresolved decisions
None.

## Completion summary
- Step 0: Scope Challenge — scope accepted as-is, delivered as two commits (D3)
- Architecture Review: 2 issues found
- Code Quality Review: 1 issue found
- Test Review: diagram produced, 30 gaps identified
- Performance Review: 1 issue found
- NOT in scope: written
- What already exists: written
- TODOS.md updates: 1 item proposed to user (added)
- Failure modes: 1 critical gap flagged (feedback link, fixed by R3)
- Unresolved decisions: 0 in this review
- Outside voice: codex, unavailable (not installed; native fallback unavailable: no TaskOutput tool)
- Parallelization: 1 lane, 0 parallel / 1 sequential
- Lake Score: 2/5 = 10/10 chosen on R2 and R5; R3 and R6 had 9/10 as their highest option; R1 chose 9/10 over the 10/10 history rewrite

## GSTACK REVIEW REPORT

| Review | Trigger | Why | Runs | Status | Findings |
|--------|---------|-----|------|--------|----------|
| CEO Review | `/plan-ceo-review` | Scope & strategy | 0 | — | — |
| Outside Review | codex (auto after eng review) | Independent 2nd opinion | 1 | unavailable | none (no reviewer ran) |
| Eng Review | `/plan-eng-review` | Architecture & tests (required) | 1 | ISSUES OPEN (not persisted to review log) | 35 issues (1 scope, 2 arch, 1 quality, 30 test gaps, 1 perf), 1 critical gap |
| Design Review | `/plan-design-review` | UI/UX gaps | 0 | — | — |
| DX Review | `/plan-devex-review` | Developer experience gaps | 0 | — | — |

- **OUTSIDE COVERAGE:** codex, plan-review phase, unavailable (CLI not installed; native fallback unavailable). No outside findings.
- **VERDICT:** No reviews CLEAR. All findings have approved remedies (T1–T7); eng review required again after implementation.

NO UNRESOLVED DECISIONS
