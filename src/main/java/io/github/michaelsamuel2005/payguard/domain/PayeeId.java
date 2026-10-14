package io.github.michaelsamuel2005.payguard.domain;

import java.util.Objects;
import java.util.UUID;

public record PayeeId(UUID value) {

    public PayeeId {
        Objects.requireNonNull(value, "value");
    }

    public static PayeeId parse(String text) {
        try {
            return new PayeeId(UUID.fromString(text));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException("a payee id is a UUID");
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
