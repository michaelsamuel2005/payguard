package io.github.michaelsamuel2005.payguard.adapters.jdbc;

import io.github.michaelsamuel2005.payguard.application.DuplicateIdempotencyKeyException;
import io.github.michaelsamuel2005.payguard.application.IdempotencyRecord;
import io.github.michaelsamuel2005.payguard.application.IdempotencyStore;
import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The primary key (customer_id, idempotency_key) does the real work: of two concurrent inserts, the second waits
 * for the first to commit and then fails with a unique violation, which becomes
 * {@link DuplicateIdempotencyKeyException}.
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcTransactionRunner tx;

    public JdbcIdempotencyStore(JdbcTransactionRunner tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public Optional<IdempotencyRecord> find(CustomerId customer, String key) {
        try {
            return Sql.queryOne(tx.current(), "select request_hash, payment_id, created_at from idempotency_keys "
                            + "where customer_id = ? and idempotency_key = ?",
                    rs -> new IdempotencyRecord(customer, key, rs.getString("request_hash"),
                            new PaymentId(rs.getObject("payment_id", UUID.class)), Sql.instant(rs, "created_at")),
                    customer.value(), key);
        } catch (SQLException e) {
            throw new DataAccessFailure("could not read idempotency key", e);
        }
    }

    @Override
    public void insert(IdempotencyRecord r) {
        try {
            Sql.update(tx.current(), "insert into idempotency_keys (customer_id, idempotency_key, request_hash, "
                            + "payment_id, created_at) values (?, ?, ?, ?, ?)",
                    r.customer().value(), r.key(), r.requestHash(), r.paymentId().value(),
                    Sql.timestamp(r.createdAt()));
        } catch (SQLException e) {
            if (Sql.isUniqueViolation(e)) {
                throw new DuplicateIdempotencyKeyException();
            }
            throw new DataAccessFailure("could not save idempotency key", e);
        }
    }
}
