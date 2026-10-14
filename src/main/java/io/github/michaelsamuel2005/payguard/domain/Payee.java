package io.github.michaelsamuel2005.payguard.domain;

import java.time.Instant;
import java.util.Objects;

/** Someone a customer pays, with the result of checking the name they typed against the account. */
public record Payee(PayeeId id, CustomerId customer, String name, SortCode sortCode, AccountNumber accountNumber,
                    NameCheckResult nameCheck, Instant createdAt) {

    public Payee {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(customer, "customer");
        name = validName(name);
        Objects.requireNonNull(sortCode, "sortCode");
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(nameCheck, "nameCheck");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /** 1 to 70 characters, at least one of them a letter. */
    public static String validName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 70 || trimmed.chars().noneMatch(Character::isLetter)) {
            throw new InvalidRequestException("a payee name is 1 to 70 characters and includes a letter");
        }
        return trimmed;
    }

    public Payee withNameCheck(NameCheckResult result) {
        return new Payee(id, customer, name, sortCode, accountNumber, result, createdAt);
    }
}
