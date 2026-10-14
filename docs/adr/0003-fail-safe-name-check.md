# 3. The name check fails safe, and never holds a database transaction open

Status: accepted, 14 October 2026

## Context

The name check asks another system (in reality, the receiving bank, through Confirmation of Payee) who holds an
account. That system can be slow or down. Two bad outcomes are possible: payments released unchecked, or every
request waiting on a dead dependency.

## Decision

- Any failure of the directory becomes the result UNAVAILABLE, never a pass. The rules always at least warn on
  it, whatever the thresholds (`ScamRules`, tested in `ScamRulesTest`).
- Each call has a timeout (800 ms by default) and goes through a Resilience4j circuit breaker: when half of the
  last calls fail (after at least five), it opens and calls fail at once for 30 seconds, then a few trial calls
  decide whether to close it.
- A payee saved while the check was unavailable is checked again when the customer pays.
- The directory is always called outside a database transaction, so a slow dependency cannot hold connections
  or row locks. The tests' fake directory fails the test if it is called inside one.

## Consequences

- During an outage customers can still pay, after a specific warning; nothing is released silently.
- The breaker's state is a metric (`payguard_directory_circuit_open`), and the runbook says what to do when it is 1.
- The breaker is per instance; each instance learns about an outage on its own.
