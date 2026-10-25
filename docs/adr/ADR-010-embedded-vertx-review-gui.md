# ADR-010: Embedded Vert.x Local Web Diff Review Engine

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
Reviewing complex multi-file full-stack diffs solely via terminal CLI outputs is challenging. Developers need visual syntax-highlighted side-by-side diffs and real-time UI preview capabilities before approving PR creation or code commits.

## Decision
Embed a lightweight Vert.x HTTP server that starts in $<20\text{ ms}$ on `http://localhost:9999` when invoked with `--gui`. Bundle zero-CDN native web assets (`diff2html.min.js`, dark theme CSS) directly in the native binary. Embed an `<iframe>` pointing to the local Storybook development server (`:6006`) for interactive component validation.

## Consequences
### Positive
* Instant, visual, interactive code review experience on localhost.
* Zero external network dependencies or CDN tracking.
* Simple decision callback (`POST /api/review/decision`) releases `CompletableFuture` to resume CLI execution.

### Negative
* Adds Vert.x HTTP route handlers and bundled static HTML/JS assets to the native binary.

## Non-Negotiable Invariants
* Web review server must boot in $<20\text{ ms}$ and use zero external CDN dependencies.
