# 1. The domain and services are plain Java; Spring is only the delivery layer

Status: accepted, 14 October 2026

## Context

The rules that decide whether money leaves are the part that must be right, readable and testable. Frameworks
change faster than those rules, and code that needs a running Spring context to test is slower to test.

## Decision

- `domain` (money, name matching, signals, the payment state machine) depends on the JDK only.
- `application` (services and the ports they need: repositories, the name directory, transactions, metrics)
  depends on `domain` only.
- `adapters` implement the ports: PostgreSQL over plain JDBC, the simulated name directory, the circuit breaker,
  Micrometer.
- `web` and `config` are Spring: controllers, security, wiring. Nothing in the core carries an annotation.

`CoreDependencyRulesTest` reads the source files and fails if the core imports Spring, Resilience4j, Micrometer,
Jakarta or the JDBC packages.

## Consequences

- About 100 tests of the core and the database layer run in seconds with no Spring context.
- Wiring is explicit (`WiringConfig`), so there is one place to read how the application is assembled.
- A little more code than annotating services directly.
