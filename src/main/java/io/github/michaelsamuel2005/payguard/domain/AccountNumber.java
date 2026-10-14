package io.github.michaelsamuel2005.payguard.domain;

import java.util.regex.Pattern;

/**
 * A UK account number: eight digits. {@link #toString()} masks all but the last four, so an account number
 * cannot leak into a log line by accident.
 */
public record AccountNumber(String value) {

    private static final Pattern EIGHT_DIGITS = Pattern.compile("\\d{8}");

    public AccountNumber {
        value = value == null ? "" : value.replace(" ", "");
        if (!EIGHT_DIGITS.matcher(value).matches()) {
            throw new InvalidRequestException("an account number is eight digits");
        }
    }

    public String masked() {
        return "****" + value.substring(4);
    }

    @Override
    public String toString() {
        return masked();
    }
}
