package io.github.michaelsamuel2005.payguard.adapters.jdbc;

import io.github.michaelsamuel2005.payguard.application.TransactionRunner;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;

/**
 * Plain JDBC transactions: one connection per outermost call, bound to the thread; commit on return, roll back on
 * any exception. Nested calls join the outer transaction. Repositories ask for {@link #current()} and fail loudly
 * if called outside a transaction, so no query can run in auto-commit mode by accident.
 */
public final class JdbcTransactionRunner implements TransactionRunner {

    private final DataSource dataSource;
    private final ThreadLocal<Connection> current = new ThreadLocal<>();

    public JdbcTransactionRunner(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        if (current.get() != null) {
            return work.get();
        }
        Connection connection = open();
        current.set(connection);
        try {
            T result = work.get();
            commit(connection);
            return result;
        } catch (RuntimeException | Error e) {
            rollbackQuietly(connection, e);
            throw e;
        } finally {
            current.remove();
            closeQuietly(connection);
        }
    }

    /** The connection of the transaction running on this thread. */
    Connection current() {
        Connection c = current.get();
        if (c == null) {
            throw new IllegalStateException("repositories must be called inside TransactionRunner.inTransaction");
        }
        return c;
    }

    private Connection open() {
        try {
            Connection c = dataSource.getConnection();
            c.setAutoCommit(false);
            return c;
        } catch (SQLException e) {
            throw new DataAccessFailure("could not open a database connection", e);
        }
    }

    private static void commit(Connection c) {
        try {
            c.commit();
        } catch (SQLException e) {
            throw new DataAccessFailure("commit failed", e);
        }
    }

    private static void rollbackQuietly(Connection c, Throwable cause) {
        try {
            c.rollback();
        } catch (SQLException e) {
            cause.addSuppressed(e);
        }
    }

    private static void closeQuietly(Connection c) {
        try {
            c.setAutoCommit(true);
        } catch (SQLException ignored) {
            // the connection is being closed anyway
        }
        try {
            c.close();
        } catch (SQLException ignored) {
            // nothing useful to do
        }
    }
}
