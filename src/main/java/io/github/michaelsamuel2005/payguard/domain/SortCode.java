package io.github.michaelsamuel2005.payguard.domain;

import java.util.regex.Pattern;

/** A UK sort code: six digits, accepted with or without the usual hyphens or spaces. */
public record SortCode(String value) {

    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");

    public SortCode {
        value = value == null ? "" : value.replace("-", "").replace(" ", "");
        if (!SIX_DIGITS.matcher(value).matches()) {
            throw new InvalidRequestException("a sort code is six digits, for example 12-34-56");
        }
    }

    public String formatted() {
        return value.substring(0, 2) + "-" + value.substring(2, 4) + "-" + value.substring(4);
    }

    @Override
    public String toString() {
        return formatted();
    }
}
