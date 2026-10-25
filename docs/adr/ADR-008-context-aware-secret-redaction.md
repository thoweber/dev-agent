# ADR-008: Context-Aware Secret Redaction Quarantine

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
Aggressive regex secret scrubbers often corrupt legitimate test assertions (e.g. `assertEquals("Basic dXNlcjpwYXNz", header)`), binary fixtures, or Content-Addressable Storage hashes, causing phantom test failures.

## Decision
Quarantine secret redaction to specific output boundaries:
1. **Never apply** redaction to raw source code, test files, or compiler inputs.
2. **Strictly apply** redaction to tool execution outputs before writing to H2 `StepAuditEntity`, telemetry logs, exported Markdown runbooks, and remote API request payloads.
3. **Strongly-Typed Value Object (`SanitizedOutput`):** Wrap credential-scrubbed text in the `SanitizedOutput` domain value object. Persistence sinks and audit entities (`StepAuditEntity`) consume `SanitizedOutput` to make writing unredacted strings a compile-time impossibility.

## Consequences
### Positive
* Prevents false positives and corrupted code fixtures.
* Guarantees credentials never leak into persistent logs or audit traces.

### Negative
* Requires explicit filtering at serialization and logging boundaries.

## Non-Negotiable Invariants
* Raw source code files must never be modified by secret scrubbers.
