package io.github.michaelsamuel2005.payguard.testsupport;

import io.github.michaelsamuel2005.payguard.application.TransactionRunner;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Transactions for the in-memory stores: each outermost transaction snapshots every store and restores the
 * snapshots if the work throws, so a failed transaction leaves nothing behind, as in the database.
 */
public final class FakeTransactions implements TransactionRunner {

    /** A store that can take part in a fake transaction. */
    public interface Participant {
        Object snapshot();

        void restore(Object snapshot);
    }

    private final List<Participant> participants = new ArrayList<>();
    private final ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
    private int committed;
    private int rolledBack;

    public FakeTransactions with(Participant... ps) {
        participants.addAll(List.of(ps));
        return this;
    }

    @Override
    public synchronized <T> T inTransaction(Supplier<T> work) {
        if (depth.get() > 0) {
            return work.get();
        }
        List<Object> snapshots = participants.stream().map(Participant::snapshot).toList();
        depth.set(1);
        try {
            T result = work.get();
            committed++;
            return result;
        } catch (RuntimeException | Error e) {
            for (int i = 0; i < participants.size(); i++) {
                participants.get(i).restore(snapshots.get(i));
            }
            rolledBack++;
            throw e;
        } finally {
            depth.set(0);
        }
    }

    public boolean isActive() {
        return depth.get() > 0;
    }

    public int committed() {
        return committed;
    }

    public int rolledBack() {
        return rolledBack;
    }
}
