package io.github.michaelsamuel2005.payguard.domain;

import java.util.regex.Pattern;

/** The reference the payee sees: 1 to 18 characters of letters, digits, spaces and {@code - . / &}. */
public record PaymentReference(String value) {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9 ./&-]{1,18}");

    public PaymentReference {
        value = value == null ? "" : value.trim();
        if (!ALLOWED.matcher(value).matches()) {
            throw new InvalidRequestException(
                    "a reference is 1 to 18 letters, digits, spaces or the characters - . / &");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
