# Modular Design Case Study: `EmailService` in AeroVisit

**System:** AeroVisit, the visitor management system of the Philippine Air Force Aerospace Museum
**Layer:** Spring Boot backend, service layer (`com.paf.aerovisit.service`)
**Principles shown:** Single Responsibility Principle (SRP), Dependency Injection (DI), loose coupling, reuse

---

## 1. The Code Snippet

`EmailService` is the only class in AeroVisit that sends email. Every notification goes through it: verification codes, approvals, reminders, check-in guidelines, feedback requests, rejections, cancellations, director reports, and staff duty rosters.

Below is the class structure. The HTML bodies of the email templates are shortened (`/* ... */`) because they repeat the same pattern.

```java
package com.paf.aerovisit.service;

@Service                                        // Registered as a singleton bean in the Spring IoC container
public class EmailService {

    private final JavaMailSender mailSender;    // Mail transport, injected by Spring (an abstraction, not a concrete SMTP client)
    private final String publicBaseUrl;         // Base URL for links in emails, read from configuration

    @Value("${spring.mail.username:" + DEFAULT_FROM + "}")
    String fromAddress = DEFAULT_FROM;          // Sender address, set in exactly one place
    static final String DEFAULT_FROM = "...";

    // Constructor injection: Spring supplies the dependencies, and the class never creates them itself
    EmailService(JavaMailSender mailSender,
                 @Value("${aerovisit.public-base-url:http://localhost:8081}") String publicBaseUrl) {
        this.mailSender = mailSender;
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    // Shared helpers (used by every template)
    private static String escape(String text) {             // Escapes user-typed text before it goes into HTML (XSS protection)
        return text == null ? "" : HtmlUtils.htmlEscape(text);
    }
    String buildFeedbackLink(String feedbackToken) {
        return publicBaseUrl + "/leave-feedback?token=" + feedbackToken;
    }

    // Public API: one method per business notification

    // Identity verification
    public boolean sendOtpEmail(String toEmail, String code, long validMinutes) { /* ... */ }

    // Booking lifecycle
    public void sendPendingNotification(String toEmail, String name, String date, boolean isMasterBooking) { /* ... */ }
    public void sendPendingNotification(String toEmail, String name, String date, boolean isMasterBooking, String referenceId) { /* ... */ }
    public void sendApprovalEmailWithQR(String toEmail, String visitorName, String date, String qrCodeFileName) { /* ... */ }
    public void sendApprovalEmailWithQR(String toEmail, String visitorName, String date, String qrCodeFileName, String referenceId) { /* ... */ }
    public void sendRejectionEmail(String toEmail, String visitorName, String presetReason, String additionalNotes) { /* ... */ }
    public void sendOverrideCancellationEmail(String toEmail, String visitorName, String date, String referenceId) { /* ... */ }
    public void sendEmergencyCancellation(Visitor visitor, String reason) throws MessagingException { /* ... */ }

    // Visit-day operations
    public void sendReminderEmailWithQR(String toEmail, String visitorName, String date, String qrCodeFileName) { /* ... */ }
    public void sendCheckInGuidelines(String to, String name) { /* ... */ }
    public void sendCheckInGuidelines(String to, String name, String tourGuideName) { /* ... */ }
    public void sendCheckOutFeedback(String to, String name, String feedbackToken) { /* ... */ }

    // Management and alerts
    public void sendDirectorDailyReport(String directorEmail, String date, long expected, long checkedIn, long noShows) { /* ... */ }
    public void sendCriticalFeedbackAlert(String directorEmail, String visitorName, String feedbackText, double sentimentScore) { /* ... */ }
    public void sendStaffScheduleEmail(String toEmail, String staffName, List<StaffSchedule> assignedSlots) throws MessagingException { /* ... */ }
}
```

**Representative method, shown in full except for the template body.** Every sender uses the same compose-and-send pattern:

```java
public boolean sendOtpEmail(String toEmail, String code, long validMinutes) {
    try {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromAddress);
        helper.setTo(toEmail);
        helper.setSubject("AeroVisit - Your verification code: " + code);
        helper.setText(/* branded HTML template containing the code and its expiry */, true);

        mailSender.send(message);
        return true;
    } catch (Exception e) {          // SMTP down, bad address, timeout
        return false;                // Reports failure to the caller instead of crashing the request
    }
}
```

---

## 2. Explanation of Reusability

### 2.1 Single Responsibility Principle (SRP)

Robert C. Martin states SRP as *"a class should have only one reason to change."* `EmailService` has a single responsibility: **composing and delivering outbound email notifications.** It holds no business rules. It does not decide whether a visitor is approved, whether a code is valid, or whether a feedback score counts as critical. Those decisions are made in the controllers, in `OtpService`, and in the automation engine. Each of these callers only says *which* notification to send and *to whom*.

So the class changes only for email-related reasons, such as a new SMTP provider, new branding, or a reworded template. A change to booking rules, capacity limits, or KYC logic never requires editing it.

### 2.2 Dependency Injection and Inversion of Control

- **`@Service`** tells Spring to create one shared instance (a singleton bean) in the IoC container. No caller ever writes `new EmailService(...)`.
- **Constructor injection.** `EmailService` receives its own dependency, `JavaMailSender`, through its constructor. Configuration values (`aerovisit.public-base-url`, `spring.mail.username`) come in through `@Value`. AeroVisit uses constructor injection, which Spring recommends for required dependencies, instead of `@Autowired` on fields. When a class has a single constructor, Spring injects through it automatically, so no annotation is needed. The fields can then be `final`, and the class can be built in a unit test with a mock mail sender.
- **`@Autowired`** appears where a dependency is *optional*. For example, `VisitorPortalController` declares `@Autowired(required = false) private OtpService otpService;`, so the web layer can still start in isolated test slices.

### 2.3 Loose Coupling

- **Depends on an abstraction.** The class depends on Spring's `JavaMailSender` interface, not on a specific SMTP library. The mail server (Gmail SMTP in this deployment) is chosen entirely through configuration (`spring.mail.*`), so it can be swapped without changing any code.
- **Simple, domain-level method signatures.** Most methods take plain values (`String toEmail`, `String visitorName`, `String date`). Callers never deal with `MimeMessage`, MIME encoding, attachments, or SMTP exceptions. This is **information hiding** (Parnas, 1972): the complexity of the mail protocol is hidden behind a small public interface.
- **Externalised configuration.** The sender address and the public base URL are each defined once and injected, so moving the system from `localhost` to an ngrok tunnel or a production domain needs no code change.

### 2.4 High Cohesion and Reuse

- **Shared cross-cutting concerns.** HTML escaping (`escape()`), the sender identity, link building (`buildFeedbackLink()`), and the branded template layout all live in one place. Every caller gets XSS-safe output automatically, without having to remember to escape input itself.
- **Method overloading for backward compatibility.** `sendPendingNotification`, `sendApprovalEmailWithQR`, and `sendCheckInGuidelines` each have two versions. A newer version adds an optional parameter (a booking reference ID or a tour guide name), and the older signature still works. Existing callers kept compiling when the new features were added, which shows the class is **open for extension**.
- **Failure handling suited to each caller.** Single-recipient senders catch and log errors, so a failed email never rolls back a check-in or an approval. `sendOtpEmail` returns `boolean` so the booking form can show "try again". The batch senders (`sendEmergencyCancellation`, `sendStaffScheduleEmail`) *throw* their exceptions so the calling loop can count and report each failure. The service keeps one responsibility but still supports different calling contexts.

---

## 3. System-Wide Usage

The same `EmailService` bean is injected into **five different components**, which together make **16 call sites**. These cover the visitor portal, the guard gate, the director's admin panel, the scheduled background jobs, and the security subsystem.

| # | Consuming component | Layer / role | Methods called | Business purpose |
| --- | --- | --- | --- | --- |
| 1 | **`OtpService`** | Security service | `sendOtpEmail` | Sends the 6-digit one-time code that verifies a visitor's email before booking. `OtpService` handles code generation, expiry, and rate limiting; `EmailService` only delivers the code. |
| 2 | **`VisitorPortalController`** | Public visitor portal | `sendPendingNotification` (`POST /save`), `sendCriticalFeedbackAlert` (`POST /submit-feedback`) | Sends an immediate "request received" reply after booking, and alerts the Director when the NLP sentiment engine flags strongly negative visitor feedback. |
| 3 | **`GuardOperationController`** | Gate / approval operations | `sendApprovalEmailWithQR` (`/admin/approve/{id}`, `/admin/approve-override/{id}`), `sendOverrideCancellationEmail` (Master Booking override), `sendCheckInGuidelines` (QR scan, manual check-in, confirm check-in), `sendCheckOutFeedback` (exit scan, manual check-out) | Sends the entry QR pass on approval, notifies visitors displaced by a VIP/Master Booking buyout, sends museum guidelines at check-in, and sends the feedback link at check-out. |
| 4 | **`ManagementController`** | Director's admin panel | `sendRejectionEmail` (`/admin/reject-visitor`), `sendEmergencyCancellation` (`/admin/slots/closure/execute`), `sendStaffScheduleEmail` (`/admin/staff/publish-roster`) | Sends rejection notices with compliance notes, cancels all bookings in bulk during an emergency closure, and sends published duty rosters to museum staff. |
| 5 | **`AeroVisitAutomationEngine`** | Scheduled background jobs (`@Scheduled`) | `sendReminderEmailWithQR` (daily 07:00), `sendDirectorDailyReport` (daily 17:30), `sendCheckOutFeedback` (automatic end-of-day check-out) | Sends next-day visit reminders, the Director's end-of-day attendance summary, and feedback requests to visitors who left without an exit scan. |

### Dependency diagram

```text
  VisitorPortalController ───┐
  GuardOperationController ──┤
  ManagementController ──────┼──► EmailService ──► JavaMailSender ──► SMTP server
  AeroVisitAutomationEngine ─┤     (@Service)       (interface)       (configured)
  OtpService ────────────────┘
      ▲
  OtpController
```

Note that `OtpService` is itself a service that reuses `EmailService`. This shows **service composition**: one reusable service builds on another. `OtpService` owns the security logic (secure random codes, cooldowns, per-email and per-client rate limits), and it hands off delivery to `EmailService`. Each class keeps exactly one responsibility.

### Summary for the paper

> Within AeroVisit, `EmailService` is a clear example of modular, reusable design. It is declared once as a Spring-managed `@Service` bean and supplied through constructor-based Dependency Injection to five independent components: the public visitor portal, the guard's gate operations, the director's management console, the scheduled automation engine, and the OTP security service. Those components call it from sixteen places. Because the class has one responsibility (composing and delivering notifications) and depends only on the `JavaMailSender` abstraction and externalised configuration, it is loosely coupled to both the business logic that uses it and the mail infrastructure under it. Shared concerns such as HTML escaping, sender identity, and link generation are implemented once and reused by every caller, which removes duplicated code and keeps the system's communication behaviour consistent.

---

### Optional: limitations (for a critical-evaluation section)

To be balanced, a thesis can note that `EmailService` builds its HTML templates with inline string concatenation. One possible refinement is to move the templates into a template engine (for example, Thymeleaf email templates that the project already depends on). That would separate *presentation* (template markup) from *delivery* (SMTP transport) and apply SRP at an even finer level. It would also remove the duplicated header and footer markup that is currently repeated across the template methods.
