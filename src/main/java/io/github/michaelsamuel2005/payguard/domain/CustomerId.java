package io.github.michaelsamuel2005.payguard.domain;

/** The authenticated subject who owns payees and payments (the JWT {@code sub} claim). */
public record CustomerId(String value) {

    public CustomerId {
        if (value == null || value.isBlank() || value.length() > 64) {
            throw new InvalidRequestException("a customer id is 1 to 64 characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
