package io.github.michaelsamuel2005.payguard.domain;

import java.util.Objects;
import java.util.UUID;

public record PaymentId(UUID value) {

    public PaymentId {
        Objects.requireNonNull(value, "value");
    }

    public static PaymentId parse(String text) {
        try {
            return new PaymentId(UUID.fromString(text));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException("a payment id is a UUID");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
