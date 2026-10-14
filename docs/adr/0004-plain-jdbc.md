# 4. Plain JDBC and SQL migrations, not an ORM

Status: accepted, 14 October 2026

## Context

The data model is small (payees, payments, idempotency keys, audit events), but the correctness rules live partly
in the database: unique constraints for idempotency, optimistic locking, an append-only audit trail, check
constraints that mirror the domain.

## Decision

- SQL migrations (Flyway naming, `src/main/resources/db/migration`) define the schema, including check
  constraints, partial indexes for the review queue and a trigger that refuses any update, delete or truncate of
  `audit_events`.
- Repositories are plain JDBC behind the application's ports. `JdbcTransactionRunner` binds one connection per
  transaction to the thread, commits on return and rolls back on any exception; repositories refuse to run outside
  a transaction.
- Optimistic locking is a `version` column checked in the `UPDATE ... WHERE version = ?`.

## Consequences

- Every query is visible and reviewable, and the database layer is tested against a real PostgreSQL without Spring.
- The same migration files run in the application (Flyway) and in the tests.
- More code than Spring Data for the same tables; acceptable at this size.
