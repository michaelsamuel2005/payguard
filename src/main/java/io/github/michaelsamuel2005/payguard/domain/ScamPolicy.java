package io.github.michaelsamuel2005.payguard.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * The thresholds the rules use. {@link #DEFAULT}'s warn and hold thresholds are the ones the written rule in
 * docs/adr/0005-thresholds-by-a-written-rule.md picks on simulated payments; ScamPolicyEvaluationTest fails if
 * they drift from that choice.
 *
 * @param warnAt              a score at or above this is warned
 * @param holdAt              a score at or above this is held for review
 * @param largeWithoutHistory with no released payments in the history window, at least this much is unusual
 * @param unusualMultiple     otherwise, more than this many times the customer's median is unusual...
 * @param unusualFloor        ...as long as it is also at least this much
 * @param rapidNewPayees      this many payments to new payees within {@code rapidWindow}, including this one
 * @param maxPayment          the largest single payment accepted at all
 */
public record ScamPolicy(int warnAt, int holdAt, Money largeWithoutHistory, int unusualMultiple, Money unusualFloor,
                         int rapidNewPayees, Duration rapidWindow, Duration historyWindow, Money maxPayment) {

    public static final ScamPolicy DEFAULT = new ScamPolicy(2, 4, Money.ofPounds(1_000), 5, Money.ofPounds(250),
            3, Duration.ofHours(1), Duration.ofDays(90), Money.ofPounds(25_000));

    public ScamPolicy {
        Objects.requireNonNull(largeWithoutHistory, "largeWithoutHistory");
        Objects.requireNonNull(unusualFloor, "unusualFloor");
        Objects.requireNonNull(rapidWindow, "rapidWindow");
        Objects.requireNonNull(historyWindow, "historyWindow");
        Objects.requireNonNull(maxPayment, "maxPayment");
        if (warnAt < 1 || holdAt <= warnAt) {
            throw new IllegalArgumentException("need 1 <= warnAt < holdAt, got " + warnAt + " and " + holdAt);
        }
        if (unusualMultiple < 2 || rapidNewPayees < 2) {
            throw new IllegalArgumentException("unusualMultiple and rapidNewPayees must be at least 2");
        }
        if (rapidWindow.isNegative() || rapidWindow.isZero() || historyWindow.isNegative() || historyWindow.isZero()) {
            throw new IllegalArgumentException("windows must be positive");
        }
    }

    public ScamPolicy withThresholds(int newWarnAt, int newHoldAt) {
        return new ScamPolicy(newWarnAt, newHoldAt, largeWithoutHistory, unusualMultiple, unusualFloor,
                rapidNewPayees, rapidWindow, historyWindow, maxPayment);
    }
}
