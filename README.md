# payguard

[![ci](https://github.com/michaelsamuel2005/payguard/actions/workflows/ci.yml/badge.svg)](https://github.com/michaelsamuel2005/payguard/actions/workflows/ci.yml)

A payments API that checks who you are paying and stops likely scams before the money leaves.
Java 21, Spring Boot 3.5, PostgreSQL 16, Resilience4j, Micrometer, Docker, Kubernetes manifests, GitHub Actions.

- **Payee name check**, in the spirit of Confirmation of Payee: match, close match (the payer is shown the real
  name so they can fix a typo) or no match (no name is shown).
- **Every payment is assessed** on seven named signals and then **released**, **warned** (the customer must
  acknowledge the specific warning they saw) or **held** for a review team, who approve or reject it with a reason.
  A reviewer can never decide their own payment.
- **Retries never pay twice.** Every payment carries an Idempotency-Key; two identical requests racing each other
  still create one payment.
- **It fails safe.** If the name check is down or slow, payments are warned, never silently released; calls have a
  timeout and a circuit breaker.
- **An audit trail the database itself keeps append-only**, recording who did what and why.
- **OAuth 2.0 resource server**: signed JWTs, separate scopes for customers and reviewers, and customers can only
  ever see their own data.
- **A web page** for the customer's journey and the review desk, written for a phone first and tested for
  accessibility, keyboard use and security headers in a real browser. See [The web page](#the-web-page).

## Status: what has run where

| | state |
|---|---|
| Domain rules, services, PostgreSQL layer, threshold evaluation | 101 tests; they run without Spring, against a real PostgreSQL 16 |
| Spring Boot layer: HTTP API, security, rate limit, circuit breaker, metrics | built and tested by GitHub Actions on every push, including 17 end-to-end API tests with real signed tokens |
| Docker image, docker compose, end-to-end smoke test | GitHub Actions |
| Web page: 76 browser test runs (the journeys again on a phone-sized screen) and 38 unit and contract tests | the browser tests run in GitHub Actions against the Docker image; they first ran against a stand-in for the web layer (real domain, services and PostgreSQL) where Spring could not be built, so the run against the real application is the one that counts |
| Web page, accessibility | axe-core in a real browser (Chromium), keyboard-only journeys, reflow, text spacing. **Not** tested with screen readers, voice control or other browsers, and not with disabled people: see [the page's statement](src/main/resources/static/accessibility.html) |
| Kubernetes manifests | validated with kubeconform in CI; not deployed to a cluster |
| Data | **simulated**: fictional accounts and generated payments; no real customer or payment data |
| Warn and hold thresholds | chosen by a written rule on simulated payments ([scorecard](docs/SCORECARD.md)); real thresholds need real labelled data |

## How a payment is decided

```
POST /v1/payees      name check: MATCH | CLOSE_MATCH (shows real name) | NO_MATCH | UNAVAILABLE
POST /v1/payments    signals -> score -> ALLOW | WARN | HOLD
                       ALLOW -> RELEASED
                       WARN  -> AWAITING_CONFIRMATION --confirm (acknowledge the warning)--> RELEASED
                       HOLD  -> HELD --reviewer approves--> RELEASED
                                     --reviewer rejects---> REJECTED
```

| signal | weight | fires when |
|---|---:|---|
| NEW_PAYEE | 1 | no earlier released payment to this payee |
| NAME_CLOSE_MATCH | 1 | the name is close to the account holder's |
| NAME_NO_MATCH | 3 | the name does not match, or there is no such account |
| NAME_CHECK_UNAVAILABLE | 2 | the check could not be made (always at least a warning) |
| UNUSUAL_AMOUNT | 2 | over 5x the customer's median payment and at least £250, or £1,000+ with no history |
| SCAM_LANGUAGE | 2 | the reference says "safe account", "HMRC", "crypto", "invest"... |
| RAPID_NEW_PAYEES | 2 | the third payment to a new payee within an hour |

A score of 2 or more is warned and 4 or more is held. Those two numbers were not picked by hand: a rule written
before any results ([ADR 5](docs/adr/0005-thresholds-by-a-written-rule.md)) chose them on simulated payments with
planted scams, and a test fails if the code drifts from that choice. On that simulation they hold 64.6% of planted
scams and warn on another 17.9%, while holding 0.9% of genuine payments and warning on 5.1%. What gets through is
mostly purchase scams paid to a matching name for an ordinary amount: nothing in the payment itself gives them away.

## The web page

A customer can send money, and a member of the review team can decide on held payments, in a web page served by the
same application at `/`. It has no framework and no build step ([ADR 7](docs/adr/0007-a-static-page-served-by-the-api.md)).
It is a responsive page designed for a phone-sized screen first, not a mobile app.

<p>
<img src="docs/screens/02-name-close-match.png" width="230" alt="The name check screen. The heading says the name is close, but not the same, and shows the name on the account.">
<img src="docs/screens/03-scam-warning.png" width="230" alt="The scam warning screen: a heading that says the reference looks like a common scam, three questions to ask yourself, and a check box that must be ticked before Send payment anyway.">
<img src="docs/screens/05-held-for-a-check.png" width="230" alt="A held payment as the customer sees it: the reason in plain words, with no risk score.">
</p>

What it is built to do:

- **Say the point first.** Every warning has a heading that states what is wrong (for example "The name does not match
  this account"), then the three questions that catch most scams, then the choice. To send a warned payment the
  customer must tick a box, and Cancel comes before "Send payment anyway".
- **Not help a scammer.** A held payment shows the customer a reason in plain words, never the risk score or the
  signals; the review desk sees both.
- **Never pay twice, and never guess.** A second press on a busy button is ignored; the Idempotency-Key is made once
  per set of details and reused on a retry; and if a send cannot be confirmed, the page says it could not confirm,
  not "nothing was sent".
- **Work for everyone it can.** Headings and landmarks, a skip link, focus that moves to the new screen's heading, a
  GOV.UK-style error summary that links to each field, status messages announced politely, targets of at least 24 px,
  no layout break at 320 px or at 200% text, dark mode, and no information carried by colour alone. Money is read
  and written as whole pence, with no floating-point sums.
- **Hold a token safely for a demo.** It is kept in memory only (no storage, no cookie, never in a URL), under a strict
  Content-Security-Policy with no inline script or style; text from other people is always shown as text.

Try it:

```bash
docker compose up --build
bash scripts/demo-links.sh        # prints a customer link and a reviewer link; open them in two windows
```

The page's own "Try the demo" screen lists the fictional accounts and five scenarios (a normal payment, a close
name, scam wording, a large first payment, a name that does not match).

What it does not do: sign in properly (pasting a token is a demo device; a real page would use the authorization-code
flow with PKCE, see the ADR), keep your payments after a reload (the API has no "list my payments"), or refresh the
review queue by itself. It has been tested in Chromium only, with no screen reader and no disabled users.

## Architecture

```
web, config (Spring)   controllers, problem-detail errors, OAuth 2.0, rate limit, correlation ids, wiring
        |
application            PayeeService, PaymentService, ReviewService, NameCheck, and the ports they need
        |
domain                 Money, NameMatcher, ScamRules, the Payment state machine: plain Java, no dependencies
        ^
adapters               PostgreSQL over JDBC; the simulated name directory; Resilience4j; Micrometer
```

The domain and services do not depend on Spring or on the database; a test reads the source and fails if they do.
Each choice is recorded with its alternatives in [docs/adr](docs/adr).

## API

| method and path | scope | what it does |
|---|---|---|
| `POST /v1/payees` | payments | add a payee; the name is checked first |
| `GET /v1/payees`, `GET /v1/payees/{id}` | payments | the customer's payees (account numbers masked) |
| `POST /v1/payments` | payments | start a payment (needs `Idempotency-Key`) |
| `GET /v1/payments/{id}`, `GET /v1/payments/{id}/audit` | payments | a payment and its audit trail |
| `POST /v1/payments/{id}/confirm` | payments | release a warned payment by acknowledging its warning |
| `POST /v1/payments/{id}/cancel` | payments | cancel a warned or held payment |
| `GET /v1/reviews` | reviews | held payments, oldest first |
| `POST /v1/reviews/{id}` | reviews | approve or reject, with a reason |
| `GET /v1/reviews/{id}/audit` | reviews | any payment's audit trail |

The full contract is [api/openapi.yaml](api/openapi.yaml); a test fails if it and the controllers disagree. Errors
are RFC 9457 problem details. Health is at `/actuator/health`; Prometheus metrics at `/actuator/prometheus`
(scope `metrics`).

## Run it

You need Java 21, Maven 3.9 and Docker.

```bash
java scripts/DevToken.java > /dev/null        # once: creates a local signing key in .dev-keys/
docker compose up --build                     # PostgreSQL and payguard (dev profile) on :8080

TOKEN=$(java scripts/DevToken.java --sub alice --scope payments)
curl -s -X POST localhost:8080/v1/payees -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Jon Smith","sortCode":"12-34-56","accountNumber":"12345678"}'
# -> "nameCheck":"CLOSE_MATCH","accountName":"JOHN SMITH"
```

`scripts/smoke.sh` walks the main journeys against a running instance. The simulated directory knows a handful of
fictional accounts (`SimulatedAccountNameDirectory`); any other account is a no-match.

## Tests

```bash
docker compose up -d db
export SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/payguard SPRING_DATASOURCE_USERNAME=payguard \
       SPRING_DATASOURCE_PASSWORD=payguard PAYGUARD_TEST_DB_URL=jdbc:postgresql://localhost:5432/payguard
mvn verify
```

- **Domain:** a table of name pairs and how they should match; each signal and outcome; every state transition,
  including a reviewer trying to approve their own payment.
- **Services**, on in-memory stores that roll back like the database: a retried request, a reused key with a new
  body, a request that loses the race for its key, a name re-checked after an outage, three new payees in an hour.
- **PostgreSQL:** each repository; optimistic locking; the audit trail refusing update, delete and truncate;
  eight clients racing with one Idempotency-Key leaving exactly one payment.
- **API**, end to end with real signed tokens: expired and foreign-signed tokens refused, scopes enforced, other
  customers' data answering 404, the circuit breaker opening, a slow directory timing out, the rate limit, the
  OpenAPI contract.
- **Evaluation:** the thresholds the written rule picks, and the [scorecard](docs/SCORECARD.md).
- **Web page** (`ui-tests`): unit tests of its pure code (money, sort codes, the API client); contract tests that
  fail if its wording, patterns or API calls drift from `openapi.yaml` and the Java; and Playwright tests of every
  journey (including a retry after a dropped connection and the Back button), keyboard-only use, axe-core on about 28
  screens in light and dark, reflow, text spacing, target size, and the security headers and policy. One test
  injects markup as a payee name and checks it is shown as text.

```bash
cd ui-tests && npm ci && npx playwright install chromium
npm run test:unit                       # no browser, no service needed
PAYGUARD_URL=http://localhost:8080 npm run test:e2e     # with docker compose up
```
- **CI** also runs CodeQL, builds the Docker image, runs the smoke test and then the browser tests through docker
  compose, and validates the Kubernetes manifests.

## More

- [Threat model](docs/THREAT_MODEL.md): what is defended, how, and what is left to the platform.
- [Runbook](docs/RUNBOOK.md): alerts, what they mean and what to do, and the demo page.
- [Accessibility statement](src/main/resources/static/accessibility.html): what was checked and what was not.
- [Decisions](docs/adr): a plain-Java core, idempotency, the fail-safe name check, plain JDBC, thresholds by a
  written rule, Spring Boot 3.5, a static page served by the API.
- [How AI was used](docs/AI_USE.md), and what the checks caught.
