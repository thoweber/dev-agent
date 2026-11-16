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
* CLI `status` and `logs` commands are statically decoupled from `..entity..`, `jakarta.persistence..`, and `javax.sql..` (`AgentArchitectureTest.statusAndLogsCommandsMustNotAccessPersistenceOrJpa`), reading `state.json` (and `worker.log` / `.agents/runs/` fallbacks) directly. Note: full suppression of Quarkus eager Hibernate/H2 bootstrap during read-only CLI invocations is tracked alongside native H2 hardening in Cycle 6 (`ADR-001`).
* `StateSocketService` writes `state.json` atomically using temp files + `StandardCopyOption.ATOMIC_MOVE` / `REPLACE_EXISTING` with `SecretRedactionFilter` applied to both `lastMessage` and `branch`, plus `pidStartedAt` kernel start timestamps to guard against OS PID reuse in `isProcessAlive` and `AbortCommand`.
* `DetachedWorkerLauncher` spawns hidden `worker` subprocesses inside the worktree directory with stdin closed immediately, `--instruction=<val>` flag safety, and stdout/stderr redirected to `.git/dev-agent-worktrees/<task-id>/worker.log`. `LogsCommand` streams chunks using bounded 8 KB reads filtered through `SecretRedactionFilter`.
* `DesktopAlertService` sends non-blocking OS alerts (`notify-send`, `osascript`, or Windows PowerShell Toast) using secret-redacted XML/script-escaped messages, respecting `agent.notifications.desktop-enabled` / `on-success` / `on-failure` toggles.
