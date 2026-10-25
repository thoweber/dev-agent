# ADR-009: Deterministic Caffeine Cache & Normalized Loop Breaker

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
When an autonomous agent enters a failing test loop, it may oscillate between two invalid solutions ($A \rightarrow B \rightarrow A \rightarrow B$) indefinitely, burning tokens and developer time. Additionally, re-evaluating identical code diffs wastes LLM tokens.

## Decision
1. **Deterministic Gatekeeper Caching (`quarkus-cache`):** Memoize verdicts evaluated at `temperature: 0.0` backed by Caffeine using a composite key: $\text{SHA-256}(\text{UnifiedDiff} + \text{RetrievedAdrsDigest})$.
2. **Normalized Loop Circuit Breaker:** Strip timestamps, absolute paths, and line numbers to detect repeating compiler or test error signatures.
   * Repeat ($A \rightarrow A$): Injects warning.
   * Oscillation ($A \rightarrow B \rightarrow A$): Triggers `git reset --hard` to rollback edits and forces strategy pivot.
   * Exhaustion (3 failures after pivot): Trips breaker and halts with exit code 1.

## Consequences
### Positive
* Identical diff audits return in 0ms without consuming tokens.
* Prevents infinite LLM repair loops and rollback prevents corrupted repositories.

### Negative
* Requires normalized error string hashing logic.

## Non-Negotiable Invariants
* 3 consecutive failures post-pivot must trigger immediate rollback and shutdown.
