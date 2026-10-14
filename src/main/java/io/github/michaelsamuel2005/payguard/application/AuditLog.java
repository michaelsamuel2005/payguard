package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.AuditEvent;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import java.util.List;

/** Append-only: there is no way to change or remove an event once written. */
public interface AuditLog {

    void append(AuditEvent event);

    /** Oldest first. */
    List<AuditEvent> forPayment(PaymentId payment);
}
