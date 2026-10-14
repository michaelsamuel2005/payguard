package io.github.michaelsamuel2005.payguard.application;

/** Thrown by {@link IdempotencyStore#insert}; the service answers it as a replay or a conflict. */
public class DuplicateIdempotencyKeyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DuplicateIdempotencyKeyException() {
        super("idempotency key already stored");
    }
}
