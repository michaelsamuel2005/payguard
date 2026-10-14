package io.github.michaelsamuel2005.payguard.adapters.observability;

import io.github.michaelsamuel2005.payguard.application.DecisionRecorder;
import io.github.michaelsamuel2005.payguard.domain.RiskAssessment;
import io.github.michaelsamuel2005.payguard.domain.Signal;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/** Counts decisions by outcome and signals by name: payguard_decisions_total and payguard_signals_total. */
public final class MicrometerDecisionRecorder implements DecisionRecorder {

    private final MeterRegistry registry;

    public MicrometerDecisionRecorder(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public void record(RiskAssessment assessment) {
        registry.counter("payguard.decisions", "outcome", assessment.outcome().name().toLowerCase(Locale.ROOT))
                .increment();
        for (Signal s : assessment.signals()) {
            registry.counter("payguard.signals", "signal", s.name().toLowerCase(Locale.ROOT)).increment();
        }
    }
}
