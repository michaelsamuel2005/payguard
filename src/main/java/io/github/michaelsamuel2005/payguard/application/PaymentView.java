package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.Payment;
import io.github.michaelsamuel2005.payguard.domain.Signal;
import java.time.Instant;
import java.util.List;

/** A payment as the API shows it: its status and why. {@code warning} is null when the payment was allowed. */
public record PaymentView(String id, String payeeId, long amountPence, String reference, String status,
                          String outcome, int riskScore, List<String> signals, WarningView warning,
                          Instant createdAt, Instant updatedAt) {

    public static PaymentView of(Payment p) {
        return new PaymentView(p.id().toString(), p.payee().toString(), p.amount().pence(), p.reference().value(),
                p.status().name(), p.risk().outcome().name(), p.risk().score(),
                p.risk().signals().stream().map(Signal::name).toList(), WarningView.of(p.risk().warning()),
                p.createdAt(), p.updatedAt());
    }
}
