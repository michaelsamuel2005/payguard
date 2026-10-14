package io.github.michaelsamuel2005.payguard.adapters.jdbc;

import io.github.michaelsamuel2005.payguard.application.PaymentRepository;
import io.github.michaelsamuel2005.payguard.domain.ConcurrentUpdateException;
import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.Money;
import io.github.michaelsamuel2005.payguard.domain.Outcome;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.Payment;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import io.github.michaelsamuel2005.payguard.domain.PaymentReference;
import io.github.michaelsamuel2005.payguard.domain.PaymentStatus;
import io.github.michaelsamuel2005.payguard.domain.RiskAssessment;
import io.github.michaelsamuel2005.payguard.domain.Signal;
import io.github.michaelsamuel2005.payguard.domain.WarningCode;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcPaymentRepository implements PaymentRepository {

    private static final String COLUMNS = "id, customer_id, payee_id, amount_pence, reference, status, outcome, "
            + "risk_score, signals, warning_code, created_at, updated_at, version";

    private final JdbcTransactionRunner tx;

    public JdbcPaymentRepository(JdbcTransactionRunner tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public void insert(Payment p) {
        Connection c = tx.current();
        try {
            RiskAssessment r = p.risk();
            Array signals = c.createArrayOf("text", r.signals().stream().map(Signal::name).toArray(String[]::new));
            Sql.update(c, "insert into payments (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    p.id().value(), p.customer().value(), p.payee().value(), p.amount().pence(),
                    p.reference().value(), p.status().name(), r.outcome().name(), r.score(), signals,
                    r.warning() == null ? null : r.warning().name(), Sql.timestamp(p.createdAt()),
                    Sql.timestamp(p.updatedAt()), p.version());
        } catch (SQLException e) {
            throw new DataAccessFailure("could not save payment", e);
        }
    }

    @Override
    public Optional<Payment> find(PaymentId id) {
        try {
            return Sql.queryOne(tx.current(), "select " + COLUMNS + " from payments where id = ?",
                    JdbcPaymentRepository::map, id.value());
        } catch (SQLException e) {
            throw new DataAccessFailure("could not read payment", e);
        }
    }

    @Override
    public void update(Payment p) {
        try {
            int rows = Sql.update(tx.current(),
                    "update payments set status = ?, updated_at = ?, version = version + 1 where id = ? and version = ?",
                    p.status().name(), Sql.timestamp(p.updatedAt()), p.id().value(), p.version());
            if (rows != 1) {
                throw new ConcurrentUpdateException(p.id());
            }
        } catch (SQLException e) {
            throw new DataAccessFailure("could not update payment", e);
        }
    }

    @Override
    public boolean hasReleasedPaymentTo(CustomerId customer, PayeeId payee) {
        try {
            return Sql.queryOne(tx.current(), "select exists (select 1 from payments where customer_id = ? "
                            + "and payee_id = ? and status = 'RELEASED')", rs -> rs.getBoolean(1),
                    customer.value(), payee.value()).orElse(false);
        } catch (SQLException e) {
            throw new DataAccessFailure("could not read payment history", e);
        }
    }

    @Override
    public List<Money> releasedAmountsSince(CustomerId customer, Instant since) {
        try {
            return Sql.query(tx.current(), "select amount_pence from payments where customer_id = ? "
                            + "and status = 'RELEASED' and created_at >= ? order by created_at desc limit 50",
                    rs -> Money.ofPence(rs.getLong(1)), customer.value(), Sql.timestamp(since));
        } catch (SQLException e) {
            throw new DataAccessFailure("could not read payment history", e);
        }
    }

    @Override
    public int newPayeePaymentsSince(CustomerId customer, Instant since) {
        try {
            return Sql.queryOne(tx.current(), "select count(*) from payments where customer_id = ? "
                            + "and created_at >= ? and 'NEW_PAYEE' = any (signals)", rs -> rs.getInt(1),
                    customer.value(), Sql.timestamp(since)).orElse(0);
        } catch (SQLException e) {
            throw new DataAccessFailure("could not read payment history", e);
        }
    }

    @Override
    public List<Payment> held(int limit) {
        try {
            return Sql.query(tx.current(), "select " + COLUMNS + " from payments where status = 'HELD' "
                    + "order by created_at, id limit ?", JdbcPaymentRepository::map, limit);
        } catch (SQLException e) {
            throw new DataAccessFailure("could not read the review queue", e);
        }
    }

    @Override
    public int countHeld() {
        try {
            return Sql.queryOne(tx.current(), "select count(*) from payments where status = 'HELD'",
                    rs -> rs.getInt(1)).orElse(0);
        } catch (SQLException e) {
            throw new DataAccessFailure("could not count the review queue", e);
        }
    }

    private static Payment map(ResultSet rs) throws SQLException {
        String[] names = (String[]) rs.getArray("signals").getArray();
        List<Signal> signals = Arrays.stream(names).map(Signal::valueOf).toList();
        String warning = rs.getString("warning_code");
        RiskAssessment risk = new RiskAssessment(Outcome.valueOf(rs.getString("outcome")), rs.getInt("risk_score"),
                signals, warning == null ? null : WarningCode.valueOf(warning));
        return Payment.restore(new PaymentId(rs.getObject("id", UUID.class)),
                new CustomerId(rs.getString("customer_id")), new PayeeId(rs.getObject("payee_id", UUID.class)),
                Money.ofPence(rs.getLong("amount_pence")), new PaymentReference(rs.getString("reference")), risk,
                Sql.instant(rs, "created_at"), PaymentStatus.valueOf(rs.getString("status")),
                Sql.instant(rs, "updated_at"), rs.getInt("version"));
    }
}
