package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.AuditEvent;
import java.time.Instant;

public record AuditEntryView(String actor, String action, String from, String to, String detail, Instant at) {

    public static AuditEntryView of(AuditEvent e) {
        return new AuditEntryView(e.actor(), e.action(), e.from() == null ? null : e.from().name(), e.to().name(),
                e.detail(), e.at());
    }
}
