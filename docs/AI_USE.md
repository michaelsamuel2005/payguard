# How AI was used in this project

payguard was written with an AI coding assistant (Claude, by Anthropic). The assistant drafted the design, code,
tests and documentation from Michael Samuel's brief: a payments service in Java and Spring Boot that checks payees
and stops likely scams. Its output was accepted only when the tests, a real PostgreSQL database and CI agreed with
it, and the decisions it involved are written down in docs/adr so they can be challenged.

## How output was checked

- Every rule has a test that states it in plain words (the test names read as the specification).
- The database layer is tested against a real PostgreSQL 16, including eight clients racing with one
  Idempotency-Key.
- The API is tested end to end with real signed tokens in GitHub Actions, along with a Docker build, a smoke test
  through docker compose, CodeQL and Kubernetes manifest validation.
- The thresholds come from a rule written before the results (ADR 5), not from looking at numbers.

## What the checks caught

Recorded as they happened, during development.

- NameMatcher: the class comment promised "an extra middle name or middle initial on either side", but the code let only a single-letter middle initial through on the payer's side, so "John Alan Smith" vs "JOHN SMITH" came out NO_MATCH. Caught while writing the test table, before any run; fixed in the code, with "Alan Smith John" added as a NO_MATCH case so the rule stays tight.
- Thresholds: the placeholder in ScamPolicy.DEFAULT was warn at 2, hold at 6, a guess. The written rule (ADR 0005, written before the first run) picked hold at 4; the evaluation test failed on the mismatch, and DEFAULT was changed to the rule's choice. Consequence, stated in the scorecard: any first payment to a payee whose name does not match is held (0.9% of simulated genuine payments, inside the 1% cap).
- Kubernetes config map: the first draft named the rate-limit variable PAYGUARD_RATE_LIMIT_PAYMENTS_PER_MINUTE, which Spring's relaxed binding reads as payguard.rate.limit.payments.per.minute and would silently ignore. Caught on review; renamed to PAYGUARD_RATELIMIT_PAYMENTSPERMINUTE (dashes removed, dots to underscores).
