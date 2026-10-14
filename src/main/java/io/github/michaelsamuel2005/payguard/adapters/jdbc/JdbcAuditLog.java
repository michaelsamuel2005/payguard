package io.github.michaelsamuel2005.payguard.adapters.jdbc;

import io.github.michaelsamuel2005.payguard.application.AuditLog;
import io.github.michaelsamuel2005.payguard.domain.AuditEvent;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import io.github.michaelsamuel2005.payguard.domain.PaymentStatus;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class JdbcAuditLog implements AuditLog {

    private final JdbcTransactionRunner tx;

    public JdbcAuditLog(JdbcTransactionRunner tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public void append(AuditEvent e) {
        try {
            Sql.update(tx.current(), "insert into audit_events (payment_id, actor, action, from_status, to_status, "
                            + "detail, at) values (?, ?, ?, ?, ?, ?, ?)",
                    e.paymentId().value(), e.actor(), e.action(), e.from() == null ? null : e.from().name(),
                    e.to().name(), e.detail(), Sql.timestamp(e.at()));
        } catch (SQLException ex) {
            throw new DataAccessFailure("could not write audit event", ex);
        }
    }

    @Override
    public List<AuditEvent> forPayment(PaymentId payment) {
        try {
            return Sql.query(tx.current(), "select actor, action, from_status, to_status, detail, at "
                            + "from audit_events where payment_id = ? order by id",
                    rs -> {
                        String from = rs.getString("from_status");
                        return new AuditEvent(payment, rs.getString("actor"), rs.getString("action"),
                                from == null ? null : PaymentStatus.valueOf(from),
                                PaymentStatus.valueOf(rs.getString("to_status")), rs.getString("detail"),
                                Sql.instant(rs, "at"));
                    },
                    payment.value());
        } catch (SQLException ex) {
            throw new DataAccessFailure("could not read audit trail", ex);
        }
    }
}
