# ADR-006: Non-Locking Ephemeral State Sockets (state.json)

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
Embedded H2 databases enforce single-process file locking in file mode (`agent.mv.db`). When a background worker process holds the database lock during build and test execution, running foreground commands like `dev-agent status` or `dev-agent logs` fails with `Database may be already in use`.

## Decision
Active background tasks emit atomic `state.json` heartbeats within their dedicated worktrees (`.git/dev-agent-worktrees/<task-id>/state.json`). Foreground CLI status queries read these JSON files directly with 0ms lock overhead. Database transactions occur strictly at major task lifecycle transitions.

## Consequences
### Positive
* Non-blocking, instant telemetry reads for CLI commands.
* Eliminates H2 database lock collision crashes between foreground CLI and background workers.

### Negative
* Requires maintaining state synchronization between filesystem heartbeats and relational storage.

## Non-Negotiable Invariants
* CLI `status` and `logs` commands must read `state.json` without opening JDBC database connections.
