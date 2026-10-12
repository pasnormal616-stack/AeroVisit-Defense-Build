# Controlled Code Modification Based on Audit and Testing Results: CSRF Protection and Session Termination

**System:** AeroVisit, the visitor management system of the Philippine Air Force Aerospace Museum
**Component:** Spring Security configuration (`SecurityConfig.java`), Thymeleaf views, and the MockMvc test suite
**Change date:** 9 October 2026
**Change control:** A pre-change snapshot of every affected file was stored in `backup-2026-10-09-csrf-nonce-accounts/`, together with a rollback manifest (`README.txt`) listing each changed, added, and removed file.

---

## 1. Initial State and Test Finding (The Vulnerability)

### 1.1 Audit findings

A security review of the authentication layer found two related weaknesses.

**Finding 1: CSRF protection was globally disabled.** The security filter chain contained:

```java
.csrf(csrf -> csrf.disable()) // Disabled for simplicity in the capstone so your forms don't break
```

With this setting, Spring Security did not validate any anti-forgery token. Every state-changing endpoint therefore accepted any request that carried a valid session cookie, no matter which website sent it. This included approving or rejecting bookings, banning a face, denying entry at the gate, executing an emergency closure, and publishing staff rosters. The audit confirmed this: the test suite contained **no** CSRF-aware requests (zero uses of `.with(csrf())`), so the gap had never been exercised by a test.

**Finding 2: "Log out" did not end the session.** The navigation sidebar shared by all staff pages (`fragments/shell.html`) rendered the logout control as a plain hyperlink:

```html
<a href="/login?logout" class="av-nav-link av-logout">...<span>Log out</span></a>
```

This link sent a `GET` request to the *login page* with a `?logout` query parameter. It never reached Spring Security's logout endpoint (`/logout`). The login screen appeared, which looked like a successful sign-out, but the server-side `HttpSession` and the `SecurityContext` were **never invalidated**, and the `JSESSIONID` cookie stayed in the browser. A staff member who "logged out" on a shared gate terminal left an authenticated session behind. Pressing the browser's Back button, or opening `/admin` directly, would restore full Director or Guard access.

The two findings compounded each other. Because CSRF was disabled, Spring Security's logout filter accepted *any* HTTP method, including `GET`. So even the correct `/logout` URL could be triggered by a simple cross-site link or image tag, which allows forced logout from another website.

### 1.2 Risk in a Zero-Trust architecture

Zero-Trust architecture (NIST SP 800-207) requires that **every request be authenticated and authorised on its own merits**, and that the presence of a credential is not enough. Both findings broke this principle:

| Principle | Violation |
|---|---|
| *Verify explicitly* | With CSRF disabled, the session cookie alone was treated as proof of intent. The server could not tell whether the Director deliberately clicked "Approve" or a malicious page caused the browser to send the request (Cross-Site Request Forgery, CWE-352, part of OWASP A01: Broken Access Control). |
| *Assume breach / limit session lifetime* | A session that survives "logout" extends the window of exposure indefinitely. On a shared terminal at a military installation, this is a direct path to privilege misuse by the next person at the keyboard (Insufficient Session Expiration, CWE-613). |
| *Least privilege* | Abandoned Director sessions held the system's highest privileges (approvals, biometric bans, emergency closures) with no owner present. |

Since AeroVisit manages physical access to a military museum and stores biometric data, both issues were rated **high severity** and fixed before deployment.

---

## 2. The Controlled Code Modification (The Fix)

### 2.1 `SecurityConfig.java`: enabling CSRF

**Before**

```java
http
    .csrf(csrf -> csrf.disable()) // Disabled for simplicity in the capstone so your forms don't break
    .authorizeHttpRequests(auth -> auth
        /* ... role-based route rules ... */
    )
```

**After**

```java
http
    // 🛡️ CSRF ON: every POST / PUT / PATCH / DELETE must carry the session's token (hidden "_csrf" field in forms,
    // X-CSRF-TOKEN header in fetch calls, read from the <meta> tags). One exception: the public booking upload
    // /save. Its multipart body is not parsed yet when this filter runs (an over-size photo would turn into a 403
    // instead of the "photo too large" message), and it carries its own session-bound one-time token instead:
    // the KYC nonce, checked and burned by VisitorPortalController before anything else.
    .csrf(csrf -> csrf.ignoringRequestMatchers("/save"))
    .authorizeHttpRequests(auth -> auth
        /* ... role-based route rules (unchanged) ... */
    )
```

CSRF protection is now **on by default for every route**. The single exemption (`/save`, the public booking upload) is documented in the code and is not left unprotected. It is covered by an **equivalent compensating control**: a one-time, session-bound KYC nonce. The booking page generates the nonce, and the controller validates and immediately consumes it before any other processing.

### 2.2 `SecurityConfig.java`: strict logout configuration

**Before**

```java
.logout(logout -> logout
    .logoutUrl("/logout")
    .logoutSuccessUrl("/login?logout")
    .permitAll()
);
```

**After**

```java
// 🚪 LOGOUT: POST /logout only (CSRF is on, so a link or an image on another site can't sign a guard out, and a
// GET can't sign anyone out by accident). The session is invalidated, the security context cleared and the
// session cookie deleted, so the Back button can't reopen the dashboard.
.logout(logout -> logout
    .logoutUrl("/logout")
    .invalidateHttpSession(true)      // Destroy the server-side HttpSession
    .clearAuthentication(true)        // Remove the Authentication from the SecurityContext
    .deleteCookies("JSESSIONID")      // Expire the session cookie in the browser
    .logoutSuccessUrl("/login?logout")
    .permitAll()
);
```

Two effects combine here. First, because CSRF is now enabled, Spring Security restricts the logout endpoint to **`POST` requests that carry a valid token**. A `GET` link or a cross-site request can no longer trigger it. Second, the termination steps are declared explicitly rather than left to framework defaults. `invalidateHttpSession` and `clearAuthentication` match Spring's default behaviour but are now stated in the security policy itself, and `deleteCookies("JSESSIONID")` is a new step that removes the session identifier from the client.

### 2.3 Thymeleaf view modification

**Before** (`templates/fragments/shell.html`)

```html
<a href="/login?logout" class="av-nav-link av-logout">
    <i class="bi bi-box-arrow-right"></i><span>Log out</span>
</a>
```

**After**

```html
<!-- Logout is a POST with the CSRF token (Spring Security ends the session only on POST /logout) -->
<form th:action="@{/logout}" method="post" class="av-logout-form">
    <button type="submit" class="av-nav-link av-logout">
        <i class="bi bi-box-arrow-right"></i><span>Log out</span>
    </button>
</form>
```

The `th:action` attribute tells Thymeleaf to insert the hidden `_csrf` token field into the form automatically, so the logout request passes CSRF validation. The button keeps the original CSS classes, so the change has no visual impact on users.

### 2.4 Supporting changes (scope of the modification)

Enabling CSRF affects every page that changes data, so the change was applied consistently across the presentation layer:

- **HTML forms:** every `POST` form either uses `th:action` (automatic token) or includes the hidden `_csrf` field explicitly.
- **AJAX calls:** a new helper script, `static/js/csrf.js` (`AeroCsrf.headers()`), reads the token from `<meta name="_csrf">` tags and adds it as an `X-CSRF-TOKEN` header to every state-changing `fetch()` call.
- **Rollback readiness:** the pre-change snapshot and manifest allow the whole change set to be reverted in one step if a defect is found in production.

---

## 3. Testing Verification (The Result)

### 3.1 Updating the existing test suite

Once CSRF was enabled, every existing MockMvc test that sent a state-changing request without a token would correctly receive **HTTP 403 Forbidden**. The suite was updated so these tests behave like a legitimate browser, by adding Spring Security Test's `csrf()` request post-processor:

```java
// Before: passed only because CSRF was disabled
mvc.perform(post("/admin/approve/5"))
   .andExpect(status().isOk());

// After: the request carries a valid CSRF token, as a real form submission does
mvc.perform(post("/admin/approve/5").with(csrf()))
   .andExpect(status().isOk());
```

After the update, `.with(csrf())` appears **314 times across 27 test classes**. Before the change it appeared nowhere.

### 3.2 New regression tests for the vulnerabilities

Adding tokens to the existing tests only shows that legitimate traffic still works. To show that the **vulnerabilities themselves are closed**, new negative tests were added to `SecurityRulesTest`:

```java
// --- CSRF is on: a state-changing request without the session's token is refused, even for the Director ---
@Test
@WithMockUser(roles = "DIRECTOR")
void aPostWithoutTheCsrfTokenIsRefused() throws Exception {
    mvc.perform(post("/admin/approve/5")).andExpect(status().isForbidden());                            // No token
    mvc.perform(post("/admin/approve/5").with(csrf().useInvalidToken())).andExpect(status().isForbidden()); // Forged token
    mvc.perform(post("/admin/approve/5").with(csrf())).andExpect(status().isOk());                      // Valid form token
    mvc.perform(post("/admin/approve/5").with(csrf().asHeader())).andExpect(status().isOk());           // Valid AJAX header
}

@Test
void theAnonymousOtpFormNeedsTheCsrfTokenToo() throws Exception {
    mvc.perform(post("/otp/send").param("email", "a@b.co")).andExpect(status().isForbidden());
}

// --- Logout: POST with the token ends the session; a plain link (GET) or a forged POST can't ---
@Test
void logoutIsAPostThatEndsTheSession() throws Exception {
    MockHttpSession session = new MockHttpSession();
    mvc.perform(post("/logout").session(session).with(user("guard").roles("GUARD")).with(csrf()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login?logout"))
        .andExpect(unauthenticated());                  // The SecurityContext is cleared
    assertThat(session.isInvalid()).isTrue();           // The HttpSession is destroyed
}

@Test
void logoutWithoutTheCsrfTokenIsRefused() throws Exception {
    mvc.perform(post("/logout").with(user("guard").roles("GUARD")))
        .andExpect(status().isForbidden());             // Cross-site forced logout is blocked
}
```

Two more test classes were added to protect the change from **future regressions**:

- **`CsrfTemplatesTest`** is a static-analysis test that scans every Thymeleaf template. It fails the build if any `POST` form lacks a CSRF token, or if any data-changing `fetch()` call omits the `X-CSRF-TOKEN` header. A developer who later adds a form without the token gets a failing test instead of a 403 error in front of a user.
- **`KycNonceTest`** verifies the compensating control on the exempted `/save` endpoint. A submission without the nonce, or a script with no booking-page session, is refused before any other check, and each nonce can be used for **one booking only**.

### 3.3 Final test result

The full automated suite (`./mvnw test`, JUnit 5 + Spring MockMvc) was run after the modification. The Maven Surefire report from the latest run (9 October 2026, 19:35) records:

| Metric | Result |
|---|---|
| Test classes | 69 |
| Tests executed | **681** |
| Failures | **0** |
| Errors | **0** |
| Skipped | **0** |

This result shows two things:

1. **The security gap is closed.** The new negative tests confirm that requests without a token, requests with a forged token, and token-less logout attempts are all rejected with HTTP 403. They also confirm that a legitimate logout invalidates the session and clears the security context.
2. **No regression was introduced.** Every pre-existing functional test still passes once it carries a valid token. These cover booking, approval, check-in and check-out, capacity locking, biometric bans, emergency closures, and staff rostering. The hardening therefore did not change any intended business behaviour.

### 3.4 SDLC summary

This change followed a controlled, verifiable cycle:

| SDLC phase | Activity in this change |
|---|---|
| **Identify** | Security audit found `csrf().disable()` and a logout control that never reached the logout endpoint |
| **Assess** | Mapped to CWE-352 (CSRF) and CWE-613 (Insufficient Session Expiration); rated high severity against Zero-Trust requirements |
| **Prepare** | Snapshot of all affected files plus a rollback manifest made before any edit |
| **Modify** | CSRF enabled system-wide with one documented, compensated exemption; logout restricted to `POST` with explicit session invalidation; views and AJAX calls updated |
| **Verify** | Existing tests updated with `.with(csrf())`; negative regression tests and template-scanning tests added; full suite of 681 tests passed with 0 failures |

> **Summary for the paper.** A security audit of AeroVisit found that Cross-Site Request Forgery protection had been globally disabled and that the staff "Log out" control only redirected to the login page without terminating the server-side session. These weaknesses correspond to CWE-352 and CWE-613 and violate the Zero-Trust requirement to verify every request explicitly. The fix was applied as a controlled modification. A full pre-change snapshot and rollback manifest were made first. CSRF validation was then enabled for all routes, with a single documented exemption protected by a one-time nonce. Logout was restricted to token-bearing `POST` requests that invalidate the `HttpSession`, clear the `SecurityContext`, and delete the session cookie. The views were updated to match. Verification combined updated functional tests, new negative security tests, and a static template-scanning test that prevents future regressions. The complete automated suite of 681 tests passed with zero failures, showing that the vulnerability was closed without any regression in system behaviour.
