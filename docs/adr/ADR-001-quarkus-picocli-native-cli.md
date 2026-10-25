# ADR-001: Quarkus Picocli GraalVM Native CLI Runtime

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
Developers expect CLI tools to execute in milliseconds. Standard JVM cold start latencies (1.5–3.0 seconds) create unacceptable friction when issuing commands like `dev-agent status` or invoking short lint/test verification cycles.

## Decision
Build `dev-agent` as an independent CLI on **Quarkus 3.40.x**, **Picocli** (`quarkus-picocli`), and **GraalVM/Mandrel Native Image**. Package the resulting native executable directly to `~/.local/bin/dev-agent`.

## Consequences
### Positive
* Sub-50ms cold boot latency for CLI invocations.
* Low memory footprint ($<40\text{ MB}$ RSS idle).
* Rich CDI dependency injection available at compile-time Ahead-of-Time (AOT).

### Negative
* Requires reflection declarations (`@RegisterForReflection`) for classes serialized or invoked reflectively.

## Non-Negotiable Invariants
* All model records, entities, and DTOs must register `@RegisterForReflection`.
* CLI startup time must not exceed 50ms in native mode.
