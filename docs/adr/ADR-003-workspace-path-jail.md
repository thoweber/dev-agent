# ADR-003: Canonical Path Jail and Workspace Sandboxing

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
Autonomous agents generate file paths based on LLM output. Without strict sandboxing, path traversal patterns (`../../etc/passwd` or symbolic link escapes) could corrupt the developer's system or overwrite global Git metadata.

## Decision
Implement `WorkspaceJailValidator`. All relative or untrusted paths are resolved against `activeWorkingRoot.toRealPath()`. Any path that does not begin with the canonical root throws a `SecurityException`. Modifications to `.git/**`, `~/.config/dev-agent/**`, or the CLI binary are strictly blocked.

## Consequences
### Positive
* Complete filesystem sandboxing and protection against directory traversal.
* Predictable, reproducible workspace mutations.

### Negative
* Requires resolving canonical paths for all file operations.

## Non-Negotiable Invariants
* Every file access tool must call `WorkspaceJailValidator.validateAndResolve()`.
