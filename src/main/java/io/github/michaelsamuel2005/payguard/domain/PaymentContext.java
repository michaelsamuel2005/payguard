package io.github.michaelsamuel2005.payguard.domain;

import java.util.List;
import java.util.Objects;

/**
 * Everything the rules look at for one payment.
 *
 * @param recentReleased        amounts this customer released within the policy's history window
 * @param recentNewPayeePayments payments this customer made to new payees within the policy's rapid window,
 *                               not counting this one
 */
public record PaymentContext(Money amount, String reference, NameMatch nameMatch, boolean newPayee,
                             List<Money> recentReleased, int recentNewPayeePayments) {

    public PaymentContext {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(nameMatch, "nameMatch");
        recentReleased = List.copyOf(recentReleased);
        if (recentNewPayeePayments < 0) {
            throw new IllegalArgumentException("recentNewPayeePayments cannot be negative");
        }
    }
}
