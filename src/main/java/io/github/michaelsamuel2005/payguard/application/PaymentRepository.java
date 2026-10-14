package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.Money;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import io.github.michaelsamuel2005.payguard.domain.Payment;
import io.github.michaelsamuel2005.payguard.domain.PaymentId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository {

    void insert(Payment payment);

    Optional<Payment> find(PaymentId id);

    /**
     * Saves a changed status.
     *
     * @throws io.github.michaelsamuel2005.payguard.domain.ConcurrentUpdateException if the stored payment is no
     *         longer at {@link Payment#version()}
     */
    void update(Payment payment);

    boolean hasReleasedPaymentTo(CustomerId customer, PayeeId payee);

    /** Released amounts since the given instant, newest first, at most 50. */
    List<Money> releasedAmountsSince(CustomerId customer, Instant since);

    /** Payments of any status since the given instant that went to a payee new at the time. */
    int newPayeePaymentsSince(CustomerId customer, Instant since);

    /** Held payments, oldest first. */
    List<Payment> held(int limit);

    int countHeld();
}
