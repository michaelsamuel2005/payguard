package io.github.michaelsamuel2005.payguard.adapters.directory;

import io.github.michaelsamuel2005.payguard.application.AccountNameDirectory;
import io.github.michaelsamuel2005.payguard.application.DirectoryUnavailableException;
import io.github.michaelsamuel2005.payguard.domain.AccountNumber;
import io.github.michaelsamuel2005.payguard.domain.SortCode;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stands in for the receiving banks' side of Confirmation of Payee: a fixed set of fictional accounts. Its failure
 * mode can be switched at run time, so tests and demos can take it down or slow it down and watch payguard fail safe.
 */
public final class SimulatedAccountNameDirectory implements AccountNameDirectory {

    public enum FailureMode { NONE, DOWN, SLOW }

    /** Fictional sort codes, account numbers and names, for tests and the demo. */
    public static final Map<String, String> SAMPLE_ACCOUNTS = Map.of(
            "123456" + "12345678", "JOHN SMITH",
            "123456" + "87654321", "SARAH O'CONNOR",
            "203040" + "11112222", "ACME TRADING LIMITED",
            "203040" + "33334444", "PRIYA PATEL",
            "405060" + "55556666", "GREENFIELD LETTINGS LTD",
            "607080" + "77778888", "ALEX MORGAN",
            "607080" + "99990000", "DANIEL HUGHES");

    private final Map<String, String> accounts;
    private final Duration slowDelay;
    private volatile FailureMode mode = FailureMode.NONE;

    public SimulatedAccountNameDirectory(Map<String, String> accounts, Duration slowDelay) {
        this.accounts = new ConcurrentHashMap<>(accounts);
        this.slowDelay = Objects.requireNonNull(slowDelay, "slowDelay");
    }

    public static SimulatedAccountNameDirectory withSampleAccounts() {
        return new SimulatedAccountNameDirectory(SAMPLE_ACCOUNTS, Duration.ofSeconds(5));
    }

    @Override
    public Optional<String> accountName(SortCode sortCode, AccountNumber accountNumber) {
        switch (mode) {
            case DOWN -> throw new DirectoryUnavailableException("simulated directory outage");
            case SLOW -> sleep(slowDelay);
            case NONE -> { }
        }
        return Optional.ofNullable(accounts.get(sortCode.value() + accountNumber.value()));
    }

    public void setFailureMode(FailureMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public FailureMode failureMode() {
        return mode;
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirectoryUnavailableException("interrupted while waiting", e);
        }
    }
}
