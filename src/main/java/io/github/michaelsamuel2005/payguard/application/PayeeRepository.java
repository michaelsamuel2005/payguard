package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import io.github.michaelsamuel2005.payguard.domain.NameCheckResult;
import io.github.michaelsamuel2005.payguard.domain.Payee;
import io.github.michaelsamuel2005.payguard.domain.PayeeId;
import java.util.List;
import java.util.Optional;

public interface PayeeRepository {

    /** @throws DuplicatePayeeException if this customer already has a payee with this sort code and account */
    void insert(Payee payee);

    /** Only the customer's own payees are found. */
    Optional<Payee> find(CustomerId customer, PayeeId id);

    List<Payee> list(CustomerId customer);

    void updateNameCheck(PayeeId id, NameCheckResult result);
}
