package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.RiskAssessment;

/** Where decisions are counted (metrics). Called once per new payment, after its transaction commits. */
@FunctionalInterface
public interface DecisionRecorder {

    DecisionRecorder NONE = assessment -> { };

    void record(RiskAssessment assessment);
}
