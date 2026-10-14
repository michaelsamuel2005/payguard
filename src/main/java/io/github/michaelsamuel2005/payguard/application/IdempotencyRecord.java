package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import java.time.Instant;
import java.util.Objects;

/** A request a customer made with an Idempotency-Key: a hash of its body and the payment it created. */
public record IdempotencyRecord(CustomerId customer, String key, String requestHash, PaymentId paymentId,
                                Instant createdAt) {

    public IdempotencyRecord {
        Objects.requireNonNull(customer, "customer");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
