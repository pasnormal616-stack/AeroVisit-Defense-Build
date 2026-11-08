package com.paf.aerovisit.config;

import com.paf.aerovisit.model.BookingSlot;
import com.paf.aerovisit.model.Visitor;
import com.paf.aerovisit.repository.BookingSlotRepository;
import com.paf.aerovisit.repository.VisitorRepository;
import com.paf.aerovisit.service.EmailService;
import com.paf.aerovisit.service.MailOutbox;
import com.paf.aerovisit.service.PaymentWorkflow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

// 💳 ONE-TIME MOVE TO PAY FIRST, production-safe. Bookings made under the old approve-then-pay flow that still wait for
// approval (PENDING / PENDING_MANUAL_REVIEW / VERIFIED, dated today or later) and have no receipt:
//   - with an admission total above 0: they get a billing number, a payment deadline (the Director's window, never past
//     the visit start) and the payment-link email, and become AWAITING_RECEIPT (out of Pending Approval until the
//     receipt arrives);
//   - free ones need no change: a pending booking holds its seats now (Visitor.SEAT_HOLDING_STATUSES).
// Slots that end up over capacity (pending bookings never held seats before) are listed for the Director.
//
// By DEFAULT it only LOGS what it would change (counts and Reference IDs) and changes nothing, sends nothing. It changes
// rows and sends the emails only with aerovisit.migration.pay-first.apply=true (env AEROVISIT_MIGRATION_PAY_FIRST_APPLY).
// Safe to run on every start: a moved booking is AWAITING_RECEIPT, so a second run finds nothing to move. A failure is
// only logged; the app starts either way.
@Component
public class PayFirstMigration {

    static final String LOG = "💳 [PAY-FIRST MIGRATION] ";

    private final VisitorRepository repo;
    private final BookingSlotRepository slotRepo;

    @Autowired(required = false) private PaymentWorkflow paymentWorkflow;
    @Autowired(required = false) private EmailService emailService;
    @Autowired(required = false) private MailOutbox mailOutbox;

    @Value("${aerovisit.migration.pay-first.apply:false}")
    boolean apply;

    public PayFirstMigration(VisitorRepository repo, BookingSlotRepository slotRepo) {
        this.repo = repo;
        this.slotRepo = slotRepo;
    }

    // What one run found (and, with apply on, changed)
    record Result(boolean applied, List<String> paidMoved, List<String> freeHolding, List<String> overCapacity) {}

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            Result r = run(LocalDateTime.now());
            if (r.paidMoved().isEmpty() && r.freeHolding().isEmpty()) {
                System.out.println(LOG + "nothing to move: no pending booking from the old approve-then-pay flow.");
                return;
            }
            String mode = r.applied() ? "APPLIED: moved " : "DRY RUN (nothing changed; set AEROVISIT_MIGRATION_PAY_FIRST_APPLY=true to apply): would move ";
            System.out.println(LOG + mode + r.paidMoved().size() + " paid booking(s) to AWAITING_RECEIPT: " + r.paidMoved()
                    + "; " + r.freeHolding().size() + " free booking(s) now hold seats: " + r.freeHolding()
                    + "; over-capacity slots: " + (r.overCapacity().isEmpty() ? "none" : r.overCapacity()));
        } catch (RuntimeException e) {
            System.err.println("⚠️ " + LOG + "skipped: " + e.getMessage());
        }
    }

    Result run(LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        List<Visitor> paid = new ArrayList<>();
        List<Visitor> free = new ArrayList<>();
        for (Visitor v : repo.findByStatusIn(Visitor.APPROVABLE_STATUSES)) {
            if (!v.awaitsApproval() || v.getVisitDate() == null || v.getVisitDate().isBefore(today)) continue;
            if (v.receiptOnFile()) continue; // a receipt is in (or was): the booking belongs in Pending Approval as it is
            if (v.paymentRequired()) paid.add(v);
            else free.add(v);
        }
        boolean applying = apply && paymentWorkflow != null && !paid.isEmpty();
        if (apply && paymentWorkflow == null && !paid.isEmpty()) {
            System.err.println("⚠️ " + LOG + "the payment service is unavailable, so nothing was applied.");
        }
        List<String> paidIds = new ArrayList<>();
        for (Visitor v : paid) {
            paidIds.add(ref(v));
            if (!applying) continue;
            paymentWorkflow.onAdvanceBooking(v, now);
            v.setStatus(Visitor.STATUS_AWAITING_RECEIPT);
            Visitor saved = repo.save(v);
            if (emailService != null) {
                EmailService.PaymentNotice notice = EmailService.PaymentNotice.of(saved);
                MailOutbox.send(mailOutbox, "pay-first payment email for " + ref(saved), () -> emailService.sendPaymentRequest(notice), null);
            }
        }
        List<String> freeIds = free.stream().map(PayFirstMigration::ref).toList();
        List<Visitor> touched = new ArrayList<>(paid);
        touched.addAll(free);
        return new Result(applying, paidIds, freeIds, overCapacity(touched));
    }

    // "2026-11-20 Morning (09:00 AM - 12:00 PM) 52/50" for each slot of these bookings whose seat-holding bookings exceed
    // its capacity
    private List<String> overCapacity(List<Visitor> bookings) {
        Map<String, Integer> capacity = new TreeMap<>();
        for (Visitor v : bookings) capacity.putIfAbsent(v.getVisitDate() + " " + v.getTimeWindow(), 0);
        if (capacity.isEmpty()) return List.of();
        for (BookingSlot s : slotRepo.findAll()) {
            String key = s.getSlotDate() + " " + s.getTimeWindow();
            if (capacity.containsKey(key)) capacity.merge(key, s.getMaxCapacity(), Integer::sum);
        }
        List<String> over = new ArrayList<>();
        Map<LocalDate, List<Visitor>> byDate = new TreeMap<>();
        for (Visitor v : bookings) byDate.computeIfAbsent(v.getVisitDate(), repo::findByVisitDate);
        for (Map.Entry<String, Integer> e : capacity.entrySet()) {
            int held = 0;
            for (List<Visitor> day : byDate.values()) {
                for (Visitor o : day) {
                    if (o.holdsSeat() && e.getKey().equals(o.getVisitDate() + " " + o.getTimeWindow())) held += Math.max(1, o.getGroupSize());
                }
            }
            if (held > e.getValue()) over.add(e.getKey() + " " + held + "/" + e.getValue());
        }
        return over;
    }

    private static String ref(Visitor v) {
        return v.getReferenceId() != null ? v.getReferenceId() : "#" + v.getId();
    }
}
