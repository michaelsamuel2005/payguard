package io.github.michaelsamuel2005.payguard.adapters.jdbc;

import io.github.michaelsamuel2005.payguard.application.DuplicatePayeeException;
import io.github.michaelsamuel2005.payguard.application.PayeeRepository;
import io.github.michaelsamuel2005.payguard.domain.AccountNumber;
import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.NameCheckResult;
import io.github.michaelsamuel2005.payguard.domain.NameMatch;
import io.github.michaelsamuel2005.payguard.domain.Payee;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.SortCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcPayeeRepository implements PayeeRepository {

    private static final String COLUMNS =
            "id, customer_id, name, sort_code, account_number, name_check, close_match_name, created_at";

    private final JdbcTransactionRunner tx;

    public JdbcPayeeRepository(JdbcTransactionRunner tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
    }

    @Override
    public void insert(Payee p) {
        try {
            Sql.update(tx.current(), "insert into payees (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?)",
                    p.id().value(), p.customer().value(), p.name(), p.sortCode().value(), p.accountNumber().value(),
                    p.nameCheck().match().name(), p.nameCheck().accountName(), Sql.timestamp(p.createdAt()));
        } catch (SQLException e) {
            if (Sql.isUniqueViolation(e)) {
                throw new DuplicatePayeeException();
            }
            throw new DataAccessFailure("could not save payee", e);
        }
    }

    @Override
    public Optional<Payee> find(CustomerId customer, PayeeId id) {
        try {
            return Sql.queryOne(tx.current(), "select " + COLUMNS + " from payees where id = ? and customer_id = ?",
                    JdbcPayeeRepository::map, id.value(), customer.value());
        } catch (SQLException e) {
            throw new DataAccessFailure("could not read payee", e);
        }
    }

    @Override
    public List<Payee> list(CustomerId customer) {
        try {
            return Sql.query(tx.current(),
                    "select " + COLUMNS + " from payees where customer_id = ? order by created_at, id",
                    JdbcPayeeRepository::map, customer.value());
        } catch (SQLException e) {
            throw new DataAccessFailure("could not list payees", e);
        }
    }

    @Override
    public void updateNameCheck(PayeeId id, NameCheckResult result) {
        try {
            Sql.update(tx.current(), "update payees set name_check = ?, close_match_name = ? where id = ?",
                    result.match().name(), result.accountName(), id.value());
        } catch (SQLException e) {
            throw new DataAccessFailure("could not update payee name check", e);
        }
    }

    private static Payee map(ResultSet rs) throws SQLException {
        NameMatch match = NameMatch.valueOf(rs.getString("name_check"));
        return new Payee(new PayeeId(rs.getObject("id", UUID.class)), new CustomerId(rs.getString("customer_id")),
                rs.getString("name"), new SortCode(rs.getString("sort_code")),
                new AccountNumber(rs.getString("account_number")),
                new NameCheckResult(match, rs.getString("close_match_name")), Sql.instant(rs, "created_at"));
    }
}
