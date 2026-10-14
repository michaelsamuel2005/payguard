# 2. Every new payment needs an Idempotency-Key, and the database settles races

Status: accepted, 14 October 2026

## Context

Networks fail after a request is processed but before the response arrives. A client that retries must not pay
twice. Two retries can also arrive at the same moment.

## Decision

- `POST /v1/payments` requires an `Idempotency-Key` header (8 to 64 characters; a UUID is fine).
- The key is stored with a SHA-256 hash of the request body and the payment it created, in the same transaction
  as the payment. The primary key is (customer, key), so keys never collide across customers.
- Same key, same body: the original payment comes back with `200` and `Idempotent-Replayed: true`.
- Same key, different body: `409`.
- Two racing requests: both may pass the first lookup, but only one insert of the key can commit. The other waits
  on the primary key, fails with a unique violation, rolls back its payment and answers as a replay of the winner.

## Consequences

- `PaymentServiceOnPostgresTest` starts eight clients with one key at once and checks that exactly one payment
  and one audit line exist afterwards.
- A replay returns the payment's current state, which may have moved on (for example, confirmed) since the first
  response.
- Keys are kept forever here; a real system would expire them after a retention period.
