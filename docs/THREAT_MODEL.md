# Threat model

What payguard defends against, how, and what it leaves to the platform around it. STRIDE, briefly.

| threat | example | defence here | tested in |
|---|---|---|---|
| **Spoofing** | a forged or stolen token | RS256 JWTs checked for signature and expiry; customer identity comes only from the token's `sub`, never from a request body | `PaymentsApiTest`: no token 401, expired 401, signed by another key 401 |
| **Tampering** | a retried request with a changed amount | the Idempotency-Key is bound to a hash of the body: a changed body is a 409 | `PaymentServiceTest`, `PaymentsApiTest` |
| | two reviewers deciding one payment | optimistic locking on `version`: the second write is refused | `ReviewServiceTest`, `JdbcRepositoriesTest` |
| | editing the audit trail | a trigger refuses any update, delete or truncate of `audit_events` | `JdbcRepositoriesTest` |
| **Repudiation** | "I never approved that" | every change writes an audit line with the actor (token subject), the status before and after and the reason | `PaymentServiceTest`, `PaymentsApiTest` |
| **Information disclosure** | reading another customer's payment | lookups filter by owner and answer 404, so existence is not revealed | `PaymentServiceTest`, `PaymentsApiTest` |
| | learning who holds an account | a no-match returns no name; only a close match shows the holder's name, as in Confirmation of Payee | `PayeeServiceTest` |
| | account numbers in logs | `AccountNumber.toString()` masks all but the last four digits; API responses mask them too | `ValueObjectsTest` |
| | stack traces in errors | errors are problem details with a message written for the caller; anything unexpected is a bare 500 | `ApiExceptionHandler` |
| **Denial of service** | a script starting payments in a loop | a per-customer token bucket answers 429 with Retry-After | `PaymentRateLimiterTest`, `PaymentsApiTest` |
| | a dead or slow name directory | an 800 ms timeout and a circuit breaker; payments carry on with a warning | `PaymentsApiTest` |
| **Elevation of privilege** | a customer using the review queue | scopes: `payments` and `reviews` are separate; anything unlisted is denied | `PaymentsApiTest` |
| | a reviewer approving their own payment | refused (403) in the domain, whatever the token's scopes | `PaymentTest`, `PaymentsApiTest` |

## Left to the platform, or not done yet

- **Transport security.** TLS terminates at the gateway; there is no mutual TLS between services here.
- **The rate limit is per instance.** With N instances the effective limit is N times higher; a shared store
  (Redis, or the gateway) would make it global. Idle buckets are never evicted.
- **Secrets** come from environment variables (a Kubernetes Secret in `deploy/k8s`), not a vault.
- **The name directory is simulated.** A real Confirmation of Payee integration has its own authentication,
  message signing and scheme rules.
- **Warnings that time out.** A payment awaiting confirmation waits indefinitely; a real one would expire.
- **Joint accounts and trading names** are outside the name matcher's rules.
- **Dependencies** are scanned by Dependabot and the code by CodeQL in CI; the container image is not scanned yet.
