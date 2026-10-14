package io.github.michaelsamuel2005.payguard.testsupport;

import io.github.michaelsamuel2005.payguard.application.AccountNameDirectory;
import io.github.michaelsamuel2005.payguard.application.DirectoryUnavailableException;
import io.github.michaelsamuel2005.payguard.domain.AccountNumber;
import io.github.michaelsamuel2005.payguard.domain.SortCode;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/** A directory the test controls; it fails the test if it is ever called inside a database transaction. */
public final class FakeDirectory implements AccountNameDirectory {

    private final Map<String, String> accounts = new HashMap<>();
    private final BooleanSupplier inTransaction;
    private boolean down;
    private int calls;

    public FakeDirectory(BooleanSupplier inTransaction) {
        this.inTransaction = inTransaction;
    }

    public FakeDirectory account(String sortCode, String accountNumber, String holder) {
        accounts.put(sortCode.replace("-", "") + accountNumber, holder);
        return this;
    }

    public void setDown(boolean down) {
        this.down = down;
    }

    public int calls() {
        return calls;
    }

    @Override
    public Optional<String> accountName(SortCode sortCode, AccountNumber accountNumber) {
        if (inTransaction.getAsBoolean()) {
            throw new AssertionError("the directory was called inside a database transaction");
        }
        calls++;
        if (down) {
            throw new DirectoryUnavailableException("down for the test");
        }
        return Optional.ofNullable(accounts.get(sortCode.value() + accountNumber.value()));
    }
}
