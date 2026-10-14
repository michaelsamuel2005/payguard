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
| **Cross-site scripting** (web page) | a payee name or reference containing markup or script, shown later to the customer or to a reviewer | the page builds every element from text nodes and has no `innerHTML`; a strict Content-Security-Policy forbids inline script and any other origin | `ui-tests`: an XSS payee name on both the customer and reviewer screens; source checks for `innerHTML` and inline handlers |
| **Token theft** (web page) | reading a token from browser storage, a URL, a log or a referrer | the token is held in memory only: no storage, no cookie; a `#token=` link is read and removed from the address bar at once (a fragment is never sent to a server) and no referrer is sent | `ui-tests`: security spec (nothing in storage, no referer, `connect-src 'self'`) |
| **Clickjacking** (web page) | framing the page to trick a click | `frame-ancestors 'none'` in the policy (Spring Security also sends its default `X-Frame-Options`) | `ui-tests`: security spec; `PaymentsApiTest`: the policy on every page file |
| **Pushing a customer through a warning** (web page) | a scammer coaching the victim to "just press send" | the customer must tick a box to send a warned payment, and Cancel comes first; a held payment shows the customer a reason but never the risk score or signals, so a coach cannot learn what to avoid | `ui-tests`: journeys |
| **Double payment from the screen** | a double click, the Back button, a dropped connection | busy buttons ignore a second press; the Idempotency-Key is made when the details change and reused on every retry; if a send cannot be confirmed the page says so and never "nothing was sent" | `ui-tests`: journeys, `api.test.mjs` |

## Left to the platform, or not done yet

- **The web page signs in by pasting a token.** That is a demo device. A real page would use the authorization-code
  flow with PKCE, or a backend-for-frontend with an `HttpOnly` cookie ([ADR 7](adr/0007-a-static-page-served-by-the-api.md)).
  A script running in the page could still read an in-memory token; the defences are that none should get in, and
  that tokens expire.
- **The page was tested in Chromium with an automated accessibility checker.** No screen reader, voice control or
  magnifier, no other browser, no disabled users. `accessibility.html` says so.
- **Transport security.** TLS terminates at the gateway; there is no mutual TLS between services here.
- **The rate limit is per instance.** With N instances the effective limit is N times higher; a shared store
  (Redis, or the gateway) would make it global. Idle buckets are never evicted.
- **Secrets** come from environment variables (a Kubernetes Secret in `deploy/k8s`), not a vault.
- **The name directory is simulated.** A real Confirmation of Payee integration has its own authentication,
  message signing and scheme rules.
- **Warnings that time out.** A payment awaiting confirmation waits indefinitely; a real one would expire.
- **Joint accounts and trading names** are outside the name matcher's rules.
- **Dependencies** are scanned by Dependabot and the code by CodeQL in CI; the container image is not scanned yet.
