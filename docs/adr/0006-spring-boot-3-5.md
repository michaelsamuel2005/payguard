# 6. Spring Boot 3.5 on Java 21

Status: accepted, 14 October 2026

## Context

Spring Boot 4 is out, but many enterprise estates still run the 3.x line, and 3.5 is its final minor release.

## Decision

Use Spring Boot 3.5 (pinned in `pom.xml`) on Java 21, with virtual threads for the name-check timeout.

## Consequences

- Moving to Spring Boot 4 is a contained change: the core does not depend on Spring (ADR 1), so only `web`,
  `config` and the tests' Spring parts would change.
- Dependabot proposes version updates weekly.
