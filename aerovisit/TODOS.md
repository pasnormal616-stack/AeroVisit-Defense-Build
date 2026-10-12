# TODOS

## Give old bookings a Booking Reference ID

- **What:** One-time job that assigns a Reference ID to every visitor with `referenceId IS NULL` and a visit date today or later, then emails each one their ID with a short new email template.
- **Why:** Bookings made before Reference IDs existed can't use Manage Booking; `/check-status` can only tell them to contact the museum.
- **Pros:** Closes the gap for every upcoming old booking.
- **Cons:** One new email template and a one-off batch send. Limit it to future visit dates so past visitors aren't emailed.
- **Context:** Start at the legacy-booking branch of `checkStatus` in `VisitorPortalController` and at `EmailService`. `Visitor`'s `@PrePersist` only fills the ID on insert, so existing rows stay null.
- **Depends on / blocked by:** A new Gmail app password must be set (`MAIL_PASSWORD`) before sending; the old one was exposed in commit df5aa5b and is being revoked.
