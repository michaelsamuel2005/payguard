package io.github.michaelsamuel2005.payguard.domain;

/** A payment cannot move from its current status by the requested action. */
public class InvalidTransitionException extends ConflictException {
    private static final long serialVersionUID = 1L;

    public InvalidTransitionException(PaymentStatus from, String action) {
        super("a payment that is " + from + " cannot be " + action);
    }
}
