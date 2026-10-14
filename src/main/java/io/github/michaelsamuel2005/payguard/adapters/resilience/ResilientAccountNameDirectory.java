package io.github.michaelsamuel2005.payguard.adapters.resilience;

import io.github.michaelsamuel2005.payguard.application.AccountNameDirectory;
import io.github.michaelsamuel2005.payguard.application.DirectoryUnavailableException;
import io.github.michaelsamuel2005.payguard.domain.AccountNumber;
import io.github.michaelsamuel2005.payguard.domain.SortCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Wraps the directory in a timeout and a circuit breaker. A slow or failing directory costs each request at most
 * the timeout; once enough calls fail, the breaker opens and requests stop waiting at all until it half-opens.
 * Every failure surfaces as {@link DirectoryUnavailableException}, which NameCheck turns into UNAVAILABLE and the
 * rules into at least a warning.
 */
public final class ResilientAccountNameDirectory implements AccountNameDirectory, AutoCloseable {

    private final AccountNameDirectory delegate;
    private final CircuitBreaker breaker;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResilientAccountNameDirectory(AccountNameDirectory delegate, CircuitBreaker breaker, Duration timeout) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public Optional<String> accountName(SortCode sortCode, AccountNumber accountNumber) {
        try {
            return breaker.executeCallable(() -> callWithTimeout(sortCode, accountNumber));
        } catch (CallNotPermittedException e) {
            throw new DirectoryUnavailableException("circuit open: the directory is not being called", e);
        } catch (DirectoryUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new DirectoryUnavailableException("the directory call failed", e);
        }
    }

    private Optional<String> callWithTimeout(SortCode sortCode, AccountNumber accountNumber) {
        Future<Optional<String>> call = executor.submit(() -> delegate.accountName(sortCode, accountNumber));
        try {
            return call.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new DirectoryUnavailableException("the directory did not answer within " + timeout, e);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new DirectoryUnavailableException("interrupted while waiting for the directory", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new DirectoryUnavailableException("the directory call failed", e.getCause());
        }
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    /** Closes the breaker again (tests, and operators after a confirmed recovery). */
    public void reset() {
        breaker.reset();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
