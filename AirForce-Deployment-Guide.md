# AeroVisit — Deployment Guide: Official Mail Server (SMTP)

**For:** Air Force IT Department (system administrator)
**Applies to:** AeroVisit (Spring Boot 3.5), folder `aerovisit/`
**Purpose:** Set the password for the museum's official Gmail account (`PAFMuseumOfficial@gmail.com`), or move AeroVisit to the Air Force's own SMTP server, before go-live.

---

## 1. Overview

AeroVisit sends all of its email through **one SMTP account**:

- booking confirmations and QR passes
- email verification codes (OTP) on the booking form
- rejections, cancellations and reminders
- staff duty rosters
- the Director's daily report

The account is **`PAFMuseumOfficial@gmail.com`** on Gmail SMTP. Its settings live in **two files**, both inside the `aerovisit/` folder:

| File | What goes there | Committed to Git? |
|---|---|---|
| `src/main/resources/application.properties` | Server settings: **host, port, encryption, sender address** | Yes (no secrets) |
| `.env` | Secret: **the account's password** (`AEROVISIT_MAIL_PASSWORD`) | **No** (already git-ignored) |

The sender ("From") address of every email is the SMTP username (`spring.mail.username`). No Java code needs to change.

To keep using the museum's Gmail account, only Section 3 (the password) is needed. Section 2 is for moving to a different mail server or account.

> ⚠️ **Important: no comments at the end of a line.** Both files are read as Java `.properties` files. In that format, `#` starts a comment **only at the beginning of a line**. A line such as `spring.mail.port=587  # change me` would set the port to the text `587  # change me` and break email. In the blocks below, every `# <--- AIR FORCE IT` marker is on **its own line, directly above** the line it refers to. Keep them that way, or delete them after editing.

---

## 2. Server settings: `aerovisit/src/main/resources/application.properties`

Only needed when moving to a different mail server or account. Open the file and find **Section 3, "Email Settings"** (around line 44). Below is that section as it is today, with the `AIR FORCE IT` markers added above the lines to change. Update the host, port and username lines; check the encryption/login lines; leave the password line and `aerovisit.mail.from` alone.

```properties
# --- 3. Email Settings ---
# <--- AIR FORCE IT: UPDATE THIS (HOST): replace smtp.gmail.com with your official SMTP server's host name
spring.mail.host=smtp.gmail.com
# <--- AIR FORCE IT: UPDATE THIS (PORT): 587 = STARTTLS (default), 465 = SSL/TLS, 25 = internal relay; see the table below
spring.mail.port=587
# <--- AIR FORCE IT: UPDATE THIS (ACCOUNT): the SMTP login; it is also the "From" address of every email
spring.mail.username=PAFMuseumOfficial@gmail.com
# <--- AIR FORCE IT: DO NOT TYPE THE PASSWORD HERE. Leave this line as it is; the password goes in .env (Section 3 of this guide)
spring.mail.password=${AEROVISIT_MAIL_PASSWORD:}
# <--- AIR FORCE IT: set to false ONLY if your relay accepts mail without a login (see the table below)
spring.mail.properties.mail.smtp.auth=true
# <--- AIR FORCE IT: CHECK THIS (ENCRYPTION): keep both true for port 587; see the table below for 465
spring.mail.properties.mail.smtp.starttls.enable=true
spring.mail.properties.mail.smtp.starttls.required=true
# ⏱️ SMTP gives up after 10 s (connect, read, write; JavaMail's defaults wait forever), so email can never hang a request
spring.mail.properties.mail.smtp.connectiontimeout=10000
spring.mail.properties.mail.smtp.timeout=10000
spring.mail.properties.mail.smtp.writetimeout=10000
# "From" of every email: always the account above (Gmail rewrites or rejects a different sender)
aerovisit.mail.from=${spring.mail.username}
```

### Choosing port and encryption

Ask your mail team which of these your server uses, then set the lines in the block above to match:

| Your server uses | `spring.mail.port` | `...smtp.starttls.enable` and `...starttls.required` | Add this line | `...smtp.auth` |
|---|---|---|---|---|
| **Submission with STARTTLS** (most common) | `587` | `true` | — | `true` |
| **Implicit SSL/TLS** ("SMTPS") | `465` | `false` | `spring.mail.properties.mail.smtp.ssl.enable=true` | `true` |
| **Internal relay, no login** | `25` (or as given) | as your mail team advises | — | `false` |

**Notes:**
- **Tested setup.** AeroVisit was tested with an authenticated server on port 587 with STARTTLS. For the other two setups, run the test in Section 5 before go-live.
- **Internal certificate authority.** If your mail server's TLS certificate is issued by an internal Air Force CA, Java must trust that CA. Otherwise sending fails with `PKIX path building failed`. The recommended fix is to import the CA certificate into the Java truststore of the server running AeroVisit, using `keytool -importcert`. Avoid `mail.smtp.ssl.trust=*`, which turns off certificate checking.
- **Timeouts.** The three timeout lines (10 seconds) stop a slow mail server from freezing bookings. Leave them as they are unless your mail team advises otherwise.

---

## 3. Secrets: `aerovisit/.env`

This file sits in the `aerovisit/` folder, next to `pom.xml`. AeroVisit reads it when it starts. It is **git-ignored**, so passwords never end up in source control. The full list of supported names is in `aerovisit/.env.example`.

Add or replace these lines. The sender address is not set here: it is `spring.mail.username` in `application.properties` (Section 2).

```properties
# <--- AIR FORCE IT: UPDATE THIS (PASSWORD): for PAFMuseumOfficial@gmail.com, a Gmail App Password (Google Account >
#      Security > 2-Step Verification > App passwords; 16 letters), NOT the normal account password.
#      For a different mail server, the SMTP password of the account in spring.mail.username.
AEROVISIT_MAIL_PASSWORD=your-app-password
# <--- AIR FORCE IT: UPDATE THIS (DIRECTOR INBOX): where the Director's daily report and critical-feedback alerts go
ALERT_EMAIL=director-inbox@your-official-domain
# <--- AIR FORCE IT: UPDATE THIS (SITE ADDRESS): public address of the site, used for links inside emails (feedback, "Book a new visit")
PUBLIC_BASE_URL=https://aerovisit.your-official-domain
```

**Writing values in `.env`:**
- Write values **without quotes** and **without trailing spaces**. Both would become part of the value.
- If the password contains a backslash `\`, write it twice (`\\`).
- If `ALERT_EMAIL` is left out, the daily report and alerts go to the sending account itself (`PAFMuseumOfficial@gmail.com`).

**Alternative:** you can set the same names as operating-system environment variables, for example in a Windows Service or a container definition. Environment variables take priority over `.env`.

---

## 4. Apply the change

1. Save both files.
2. **Restart AeroVisit.** Settings are read only at startup.
3. Start the application **from inside the `aerovisit/` folder**, for example `.\mvnw.cmd spring-boot:run` or `java -jar target/aerovisit-*.jar`. The `.env` file is looked up in the folder the application is started from. Started anywhere else, the passwords in it are silently ignored.

---

## 5. Verify

1. Open the public booking page and enter an email address you can read.
2. Click to send the **verification code**.
   - **Success:** a 6-digit code arrives within a minute, sent **from your official address**.
   - **Failure:** the page shows *"We couldn't send the email right now. Please check the address and try again in a moment."* The application console shows the reason, for example an authentication error, `PKIX path building failed`, or a connection timeout.
3. Optional startup check: add `spring.mail.test-connection=true` to `application.properties`. The application will then refuse to start if it cannot reach and log in to the mail server. Remove the line after the check if you prefer the system to start even while the mail server is down.

---

## 6. Handover checklist

- [ ] Only when moving off Gmail: `spring.mail.host`, `spring.mail.port`, `spring.mail.username` and the encryption lines match the official server (Section 2)
- [ ] `AEROVISIT_MAIL_PASSWORD`, `ALERT_EMAIL` and `PUBLIC_BASE_URL` are set in `.env` (or as environment variables)
- [ ] The application was restarted from the `aerovisit/` folder
- [ ] A verification code was received from the official address (Section 5)
- [ ] **Developer:** revoke the old testing-account Gmail App Password at https://myaccount.google.com/apppasswords (no longer used by AeroVisit)
