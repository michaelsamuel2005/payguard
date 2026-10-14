# Runbook

payguard exposes Prometheus metrics at `/actuator/prometheus` (token scope `metrics`) and health at
`/actuator/health`, `/actuator/health/liveness` and `/actuator/health/readiness`. Logs are structured (ECS JSON)
and every line carries the request's `correlationId`, which is also returned in the `X-Correlation-Id` header.

## Alerts and what to do

### Name check unavailable: `payguard_directory_circuit_open == 1`

The breaker has opened: the name directory is failing or slower than the timeout.

- **Effect:** payments still go through, but every new payee is UNAVAILABLE and payments to it are warned
  (`NAME_CHECK_UNAVAILABLE`). Nothing is released unchecked.
- **Check:** the directory's own status; `payguard_signals_total{signal="name_check_unavailable"}` rising.
- **Do:** nothing in payguard needs restarting. The breaker tries again every 30 seconds and closes by itself.
  Payees saved during the outage are checked again at their next payment.

### Review queue growing: `payguard_reviews_held` above the team's capacity for 30 minutes

- **Effect:** customers wait for held payments.
- **Check:** whether the mix of decisions has shifted:
  `sum by (outcome) (rate(payguard_decisions_total[1h]))`. A jump in `hold` with no change in traffic usually
  means a signal is misfiring (for example, the directory returning NO_MATCH for everyone).
- **Do:** if a signal is misfiring, fix the cause; do not lower the thresholds by hand. Threshold changes go
  through `ScamPolicyEvaluationTest` and its scorecard (ADR 5).

### Too many 429s

- **Check:** whether one customer or many. One customer: likely a looping client or a stolen token; contact the
  customer through a known channel. Many: the limit (`payguard.rate-limit.payments-per-minute`) may be too low.

### 5xx responses

- **Check:** the log lines with the response's correlation id; `/actuator/health/readiness` for the database.
- **Do:** database unavailable: payguard reports not-ready and Kubernetes stops sending it traffic; it recovers
  when the database does.

## The demo web page

The page is static files inside the same application (`src/main/resources/static`), so it is up whenever the API is.

- **Get sign-in links:** `bash scripts/demo-links.sh` prints one link for a customer and one for a reviewer
  (`http://localhost:8080/#token=...`). Open it and the page signs in and removes the token from the address bar.
  Tokens last an hour by default; a reload signs you out, on purpose.
- **A blank page or "something went wrong" on every screen:** open the browser's developer console. A
  Content-Security-Policy violation is named there (a script or style blocked). The policy is
  `SecurityConfig.CONTENT_SECURITY_POLICY`; the page is written to need nothing outside it.
- **The page loads, then every call is 401:** the token has expired or was signed with another key. Run
  `bash scripts/demo-links.sh` again.
- **Browser tests:** `cd ui-tests && npm ci && npx playwright install chromium && npm test`, with payguard running
  (`PAYGUARD_URL`, default `http://localhost:8080`). `npm run test:unit` needs no running service and no browser.

## Useful queries

```sql
-- the review queue, oldest first
select id, customer_id, amount_pence, signals, created_at from payments
where status = 'HELD' order by created_at, id limit 50;

-- one payment's story
select at, actor, action, from_status, to_status, detail from audit_events
where payment_id = :id order by id;
```
