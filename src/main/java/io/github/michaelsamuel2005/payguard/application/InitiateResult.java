package io.github.michaelsamuel2005.payguard.application;

/** @param replayed true if this answers a retry of an earlier request with the same Idempotency-Key */
public record InitiateResult(PaymentView payment, boolean replayed) {
}
