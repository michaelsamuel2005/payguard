package io.github.michaelsamuel2005.payguard.domain;

/** Someone else changed the payment between our read and our write (optimistic locking). */
public class ConcurrentUpdateException extends ConflictException {
    private static final long serialVersionUID = 1L;

    public ConcurrentUpdateException(PaymentId id) {
        super("payment " + id.value() + " was changed by another request; read it again and retry");
    }
}
