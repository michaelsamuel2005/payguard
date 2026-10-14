package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.AccountNumber;
import io.github.michaelsamuel2005.payguard.domain.NameCheckResult;
import io.github.michaelsamuel2005.payguard.domain.NameMatch;
import io.github.michaelsamuel2005.payguard.domain.NameMatcher;
import io.github.michaelsamuel2005.payguard.domain.SortCode;
import java.util.Objects;
import java.util.Optional;

/**
 * Asks the directory who holds an account and compares the answer with the name the customer typed. Fails safe:
 * if the directory throws for any reason, the result is UNAVAILABLE, which the rules never treat as a pass.
 */
public final class NameCheck {

    private static final System.Logger LOG = System.getLogger(NameCheck.class.getName());

    private final AccountNameDirectory directory;
    private final NameMatcher matcher;

    public NameCheck(AccountNameDirectory directory, NameMatcher matcher) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
    }

    public NameCheckResult check(String enteredName, SortCode sortCode, AccountNumber accountNumber) {
        Optional<String> holder;
        try {
            holder = directory.accountName(sortCode, accountNumber);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "name check unavailable for {0} {1}: {2}",
                    sortCode, accountNumber, e.toString());
            return NameCheckResult.unavailable();
        }
        if (holder.isEmpty()) {
            return NameCheckResult.of(NameMatch.NO_MATCH);
        }
        NameMatch match = matcher.compare(enteredName, holder.get());
        return match == NameMatch.CLOSE_MATCH ? NameCheckResult.closeMatch(holder.get()) : NameCheckResult.of(match);
    }
}
