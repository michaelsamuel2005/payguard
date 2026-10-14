package io.github.michaelsamuel2005.payguard.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of a payment's audit trail: who did what, the status before and after, and why. The trail is
 * append-only; in PostgreSQL a trigger refuses any update or delete.
 *
 * @param from null for the event that created the payment
 */
public record AuditEvent(PaymentId paymentId, String actor, String action, PaymentStatus from, PaymentStatus to,
                         String detail, Instant at) {

    public AuditEvent {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(at, "at");
    }
}
