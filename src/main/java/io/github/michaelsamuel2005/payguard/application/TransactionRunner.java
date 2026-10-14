package io.github.michaelsamuel2005.payguard.application;

import java.util.function.Supplier;

/**
 * Runs work in one database transaction: committed if it returns, rolled back if it throws. A call made inside
 * another joins it. Services never hold a transaction open across a call to another system.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);

    default void execute(Runnable work) {
        inTransaction(() -> {
            work.run();
            return null;
        });
    }
}
