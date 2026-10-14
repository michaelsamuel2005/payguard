package io.github.michaelsamuel2005.payguard.domain;

import java.util.Objects;

/**
 * A name check and, for a close match only, the account holder's real name. As in Confirmation of Payee, a close
 * match shows the payer the real name so they can correct a typo; a no-match shows nothing, so the check cannot be
 * used to discover who holds an account.
 */
public record NameCheckResult(NameMatch match, String accountName) {

    public NameCheckResult {
        Objects.requireNonNull(match, "match");
        boolean close = match == NameMatch.CLOSE_MATCH;
        if (close && (accountName == null || accountName.isBlank())) {
            throw new IllegalArgumentException("a close match carries the account name");
        }
        if (!close && accountName != null) {
            throw new IllegalArgumentException("only a close match carries the account name");
        }
    }

    public static NameCheckResult of(NameMatch match) {
        return new NameCheckResult(match, null);
    }

    public static NameCheckResult closeMatch(String accountName) {
        return new NameCheckResult(NameMatch.CLOSE_MATCH, accountName);
    }

    public static NameCheckResult unavailable() {
        return of(NameMatch.UNAVAILABLE);
    }
}
