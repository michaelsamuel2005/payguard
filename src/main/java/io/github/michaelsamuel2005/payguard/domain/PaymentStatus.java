package io.github.michaelsamuel2005.payguard.domain;

/**
 * Where a payment is in its life.
 *
 * <pre>
 *   ALLOW -> RELEASED
 *   WARN  -> AWAITING_CONFIRMATION --confirm--> RELEASED
 *                                  --cancel---> CANCELLED
 *   HOLD  -> HELD --approve (reviewer)--> RELEASED
 *                 --reject  (reviewer)--> REJECTED
 *                 --cancel  (customer)--> CANCELLED
 * </pre>
 */
public enum PaymentStatus {
    RELEASED,
    AWAITING_CONFIRMATION,
    HELD,
    REJECTED,
    CANCELLED;

    public boolean isFinal() {
        return this == RELEASED || this == REJECTED || this == CANCELLED;
    }

    static PaymentStatus after(Outcome outcome) {
        return switch (outcome) {
            case ALLOW -> RELEASED;
            case WARN -> AWAITING_CONFIRMATION;
            case HOLD -> HELD;
        };
    }
}
