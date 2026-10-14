package io.github.michaelsamuel2005.payguard.domain;

/** An idempotency key was reused with a different request body. */
public class IdempotencyConflictException extends ConflictException {
    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException() {
        super("this Idempotency-Key was already used for a different request");
    }
}
