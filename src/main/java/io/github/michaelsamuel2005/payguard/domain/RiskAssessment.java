package io.github.michaelsamuel2005.payguard.domain;

import java.util.List;
import java.util.Objects;

/** A payment's signals, score and outcome, and the warning to show unless it was allowed. */
public record RiskAssessment(Outcome outcome, int score, List<Signal> signals, WarningCode warning) {

    public RiskAssessment {
        Objects.requireNonNull(outcome, "outcome");
        signals = List.copyOf(signals);
        if (score < 0) {
            throw new IllegalArgumentException("score cannot be negative");
        }
        if ((outcome == Outcome.ALLOW) != (warning == null)) {
            throw new IllegalArgumentException("a warning is shown exactly when a payment is not allowed");
        }
    }

    public String describe() {
        return "outcome=" + outcome + " score=" + score + " signals=" + signals;
    }
}
