package io.github.michaelsamuel2005.payguard.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A payment and its state machine (see {@link PaymentStatus}). Every change returns the {@link AuditEvent} that
 * records it, so a caller cannot change a payment without having the audit line in hand.
 *
 * <p>{@link #version()} is the version this object was read at; the repository's update succeeds only if the
 * stored row still has it (optimistic locking), so two reviewers cannot both decide the same payment.
 */
public final class Payment {

    private final PaymentId id;
    private final CustomerId customer;
    private final PayeeId payee;
    private final Money amount;
    private final PaymentReference reference;
    private final RiskAssessment risk;
    private final Instant createdAt;
    private final int version;
    private PaymentStatus status;
    private Instant updatedAt;

    private Payment(PaymentId id, CustomerId customer, PayeeId payee, Money amount, PaymentReference reference,
                    RiskAssessment risk, Instant createdAt, PaymentStatus status, Instant updatedAt, int version) {
        this.id = Objects.requireNonNull(id, "id");
        this.customer = Objects.requireNonNull(customer, "customer");
        this.payee = Objects.requireNonNull(payee, "payee");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.reference = Objects.requireNonNull(reference, "reference");
        this.risk = Objects.requireNonNull(risk, "risk");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = Objects.requireNonNull(status, "status");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.version = version;
    }

    /** A new payment, released, awaiting confirmation or held according to its assessment. */
    public static Payment initiate(PaymentId id, CustomerId customer, PayeeId payee, Money amount,
                                   PaymentReference reference, RiskAssessment risk, Instant now) {
        if (amount.pence() <= 0) {
            throw new InvalidRequestException("a payment must be for more than £0.00");
        }
        return new Payment(id, customer, payee, amount, reference, risk, now, PaymentStatus.after(risk.outcome()),
                now, 0);
    }

    /** A payment read back from storage. */
    public static Payment restore(PaymentId id, CustomerId customer, PayeeId payee, Money amount,
                                  PaymentReference reference, RiskAssessment risk, Instant createdAt,
                                  PaymentStatus status, Instant updatedAt, int version) {
        return new Payment(id, customer, payee, amount, reference, risk, createdAt, status, updatedAt, version);
    }

    public AuditEvent initiatedEvent() {
        return new AuditEvent(id, customer.value(), "INITIATED", null, status, risk.describe(), createdAt);
    }

    /** The customer releases a warned payment by acknowledging the exact warning they were shown. */
    public AuditEvent confirm(CustomerId by, WarningCode acknowledged, Instant now) {
        requireOwner(by);
        requireStatus(PaymentStatus.AWAITING_CONFIRMATION, "confirmed");
        if (acknowledged != risk.warning()) {
            throw new InvalidRequestException("to confirm, acknowledge the warning you were shown: " + risk.warning());
        }
        return move(PaymentStatus.RELEASED, by.value(), "CONFIRMED", "customer acknowledged " + acknowledged, now);
    }

    public AuditEvent cancel(CustomerId by, Instant now) {
        requireOwner(by);
        if (status != PaymentStatus.AWAITING_CONFIRMATION && status != PaymentStatus.HELD) {
            throw new InvalidTransitionException(status, "cancelled");
        }
        return move(PaymentStatus.CANCELLED, by.value(), "CANCELLED", "cancelled by the customer", now);
    }

    public AuditEvent approve(String reviewer, String reason, Instant now) {
        String why = checkReview(reviewer, reason);
        requireStatus(PaymentStatus.HELD, "approved");
        return move(PaymentStatus.RELEASED, reviewer, "APPROVED", why, now);
    }

    public AuditEvent reject(String reviewer, String reason, Instant now) {
        String why = checkReview(reviewer, reason);
        requireStatus(PaymentStatus.HELD, "rejected");
        return move(PaymentStatus.REJECTED, reviewer, "REJECTED", why, now);
    }

    private String checkReview(String reviewer, String reason) {
        if (reviewer == null || reviewer.isBlank()) {
            throw new InvalidRequestException("a review needs a reviewer");
        }
        if (reviewer.equals(customer.value())) {
            throw new ForbiddenException("a reviewer cannot decide on their own payment");
        }
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty() || why.length() > 500) {
            throw new InvalidRequestException("a review decision needs a reason of 1 to 500 characters");
        }
        return why;
    }

    private void requireOwner(CustomerId by) {
        if (!customer.equals(by)) {
            throw new NotFoundException("no such payment");
        }
    }

    private void requireStatus(PaymentStatus expected, String action) {
        if (status != expected) {
            throw new InvalidTransitionException(status, action);
        }
    }

    private AuditEvent move(PaymentStatus to, String actor, String action, String detail, Instant now) {
        PaymentStatus from = status;
        status = to;
        updatedAt = now;
        return new AuditEvent(id, actor, action, from, to, detail, now);
    }

    public PaymentId id() {
        return id;
    }

    public CustomerId customer() {
        return customer;
    }

    public PayeeId payee() {
        return payee;
    }

    public Money amount() {
        return amount;
    }

    public PaymentReference reference() {
        return reference;
    }

    public RiskAssessment risk() {
        return risk;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public PaymentStatus status() {
        return status;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public int version() {
        return version;
    }
}
