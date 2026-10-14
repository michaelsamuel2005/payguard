package io.github.michaelsamuel2005.payguard.application;

import io.github.michaelsamuel2005.payguard.domain.CustomerId;
import java.util.Optional;

public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(CustomerId customer, String key);

    /**
     * @throws DuplicateIdempotencyKeyException if the key is already stored for this customer, including by a
     *         concurrent request that committed first
     */
    void insert(IdempotencyRecord record);
}
