package io.github.michaelsamuel2005.payguard.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a payment's context into signals, a score and an outcome. Deliberately simple and explainable: every
 * decision can be read back as a list of named signals.
 *
 * <p>One rule overrides the score: if the name could not be checked, the payment is never silently allowed.
 */
public final class ScamRules {

    /** Matched as whole words or phrases in the upper-cased reference. */
    static final List<String> SCAM_PHRASES = List.of("SAFE ACCOUNT", "SAFE ACC", "SAFEACCOUNT", "SECURE ACCOUNT",
            "HMRC", "TAX REFUND", "CRYPTO", "BITCOIN", "BTC", "INVEST", "INVESTMENT", "INVESTMENTS");

    private final ScamPolicy policy;

    public ScamRules(ScamPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public ScamPolicy policy() {
        return policy;
    }

    public RiskAssessment assess(PaymentContext c) {
        Set<Signal> signals = EnumSet.noneOf(Signal.class);
        if (c.newPayee()) {
            signals.add(Signal.NEW_PAYEE);
        }
        switch (c.nameMatch()) {
            case CLOSE_MATCH -> signals.add(Signal.NAME_CLOSE_MATCH);
            case NO_MATCH -> signals.add(Signal.NAME_NO_MATCH);
            case UNAVAILABLE -> signals.add(Signal.NAME_CHECK_UNAVAILABLE);
            case MATCH -> { }
        }
        if (isUnusual(c.amount(), c.recentReleased())) {
            signals.add(Signal.UNUSUAL_AMOUNT);
        }
        if (mentionsScamLanguage(c.reference())) {
            signals.add(Signal.SCAM_LANGUAGE);
        }
        if (c.newPayee() && c.recentNewPayeePayments() + 1 >= policy.rapidNewPayees()) {
            signals.add(Signal.RAPID_NEW_PAYEES);
        }
        int score = signals.stream().mapToInt(Signal::weight).sum();
        Outcome outcome = score >= policy.holdAt() ? Outcome.HOLD
                : score >= policy.warnAt() ? Outcome.WARN
                : Outcome.ALLOW;
        if (outcome == Outcome.ALLOW && signals.contains(Signal.NAME_CHECK_UNAVAILABLE)) {
            outcome = Outcome.WARN; // fail safe: an unchecked name is never a silent pass
        }
        WarningCode warning = outcome == Outcome.ALLOW ? null : warningFor(signals);
        return new RiskAssessment(outcome, score, List.copyOf(signals), warning);
    }

    boolean isUnusual(Money amount, List<Money> recentReleased) {
        if (recentReleased.isEmpty()) {
            return amount.isAtLeast(policy.largeWithoutHistory());
        }
        return amount.isAtLeast(policy.unusualFloor())
                && amount.isGreaterThan(median(recentReleased).times(policy.unusualMultiple()));
    }

    static Money median(List<Money> amounts) {
        long[] p = amounts.stream().mapToLong(Money::pence).sorted().toArray();
        int mid = p.length / 2;
        return Money.ofPence(p.length % 2 == 1 ? p[mid] : (p[mid - 1] + p[mid]) / 2);
    }

    static boolean mentionsScamLanguage(String reference) {
        String words = " " + reference.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim() + " ";
        return SCAM_PHRASES.stream().anyMatch(p -> words.contains(" " + p + " "));
    }

    private static WarningCode warningFor(Set<Signal> s) {
        if (s.contains(Signal.SCAM_LANGUAGE)) {
            return WarningCode.SCAM_LANGUAGE;
        }
        if (s.contains(Signal.NAME_NO_MATCH)) {
            return WarningCode.NAME_MISMATCH;
        }
        if (s.contains(Signal.NAME_CHECK_UNAVAILABLE)) {
            return WarningCode.NAME_CHECK_UNAVAILABLE;
        }
        if (s.contains(Signal.RAPID_NEW_PAYEES)) {
            return WarningCode.RAPID_NEW_PAYEES;
        }
        if (s.contains(Signal.UNUSUAL_AMOUNT) && s.contains(Signal.NEW_PAYEE)) {
            return WarningCode.LARGE_NEW_PAYEE;
        }
        if (s.contains(Signal.NAME_CLOSE_MATCH)) {
            return WarningCode.NAME_CLOSE_MATCH;
        }
        if (s.contains(Signal.UNUSUAL_AMOUNT)) {
            return WarningCode.UNUSUAL_AMOUNT;
        }
        return WarningCode.NEW_PAYEE;
    }
}
