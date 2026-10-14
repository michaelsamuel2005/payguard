package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.AuditEvent;
import io.github.michaelsamuel2005.payguard.domain.InvalidRequestException;
import io.github.michaelsamuel2005.payguard.domain.NotFoundException;
import io.github.michaelsamuel2005.payguard.domain.Payment;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The review team's side: the queue of held payments, and approving or rejecting them with a reason. */
public final class ReviewService {

    public static final int MAX_PAGE = 200;

    private final TransactionRunner tx;
    private final PaymentRepository payments;
    private final AuditLog audit;
    private final Clock clock;

    public ReviewService(TransactionRunner tx, PaymentRepository payments, AuditLog audit, Clock clock) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.payments = Objects.requireNonNull(payments, "payments");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Oldest first, so nothing waits behind newer work. */
    public List<PaymentView> queue(int limit) {
        int n = Math.max(1, Math.min(limit, MAX_PAGE));
        return tx.inTransaction(() -> payments.held(n)).stream().map(PaymentView::of).toList();
    }

    public PaymentView decide(String reviewer, PaymentId id, String decision, String reason) {
        boolean approve = switch (decision == null ? "" : decision.toUpperCase(Locale.ROOT)) {
            case "APPROVE" -> true;
            case "REJECT" -> false;
            default -> throw new InvalidRequestException("a decision is APPROVE or REJECT");
        };
        return tx.inTransaction(() -> {
            Payment p = payments.find(id).orElseThrow(() -> new NotFoundException("no such payment"));
            AuditEvent event = approve ? p.approve(reviewer, reason, clock.instant())
                    : p.reject(reviewer, reason, clock.instant());
            payments.update(p);
            audit.append(event);
            return PaymentView.of(p);
        });
    }

    public List<AuditEntryView> audit(PaymentId id) {
        return tx.inTransaction(() -> {
            payments.find(id).orElseThrow(() -> new NotFoundException("no such payment"));
            return audit.forPayment(id).stream().map(AuditEntryView::of).toList();
        });
    }

    public int heldCount() {
        return tx.inTransaction(payments::countHeld);
    }
}
