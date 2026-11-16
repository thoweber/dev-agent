# dev-agent Core Architecture Invariants

> **Master Architecture Reference:** Consult `PROJECT_CONTEXT.md` and `docs/adr/index.md` (ADR-001 through ADR-013).

## 1. Zero-Knowledge Engine vs. Knowledge Separation (ADR-001)
* The CLI binary is a zero-knowledge execution engine. It contains **zero hardcoded project domain rules, framework keywords, or fixed documentation paths**.
* Target repositories supply declarative guidelines in `.agents/rules/` and architecture decision records in `docs/adr/`.
* Cognitive system prompts must use dynamic template variables (`{rulesContext}`, `{retrievedAdrs}`) rather than static hardcoded assumptions.

## 2. Two-Axis Extensibility (OCP) (ADR-002)
* **Build Systems (Axis 1):** Backend and frontend build tooling are decoupled via SPI providers (`BackendBuildSystem`, `FrontendBuildSystem`). For Cycles 1–5, execution centers on the `MavenBuildSystem` vertical slice, with `FrontendBuildSystem` / npm deferred to post-Cycle 5. New build tools (Gradle, pnpm, bun) are added as isolated SPI beans without modifying the orchestrator or CLI commands.
* **Framework Conventions (Axis 2):** Quarkus and Angular are decoupled into isolated SPI providers (`BackendFrameworkConvention`, `FrontendFrameworkConvention`). New frameworks (Spring Boot, React, Vue) implement isolated SPI beans.

## 3. Workspace Sandboxing & Path Jail (ADR-003)
* Every agent/LLM file access tool must pass through `WorkspaceJailValidator.validateAndResolve(untrustedPath)` and consume `RepoPath` for writes.
* Paths must resolve strictly within `activeWorkingRoot.toRealPath()`. Any directory traversal attempt outside this root throws a `SecurityException`.
* Agent tools may never write to `.git/**`, `~/.config/dev-agent/**`, or the CLI binary. Trusted engine lifecycle services (`GitWorktreeService`, `GitExcludeManager`, `StateSocketService`, `DetachedWorkerLauncher`, `LogsCommand`) are strictly bounded to `.git/info/exclude` and `.git/dev-agent-worktrees/<task-id>/{state.json,state.json.tmp,worker.log}` after strict `taskId` allowlist (`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, no `..`) and full-ancestor symlink validation.

## 4. Worktree Concurrency & Build Slicing (ADR-004)
* Isolated and background task execution runs inside dedicated Git worktrees allocated at `.git/dev-agent-worktrees/<task-id>/` and bound via `WorktreeExecutionContext` (`ScopedValue<Path>`).
* `WorkspaceContextResolver.resolveRepoRoot()` skips worktree `.git` pointer files to resolve the primary repository root; `resolveExecutionRoot()` returns the bound worktree root when active.
* The developer's primary workspace (`user.dir`) remains untouched and unlocked; uncommitted changes are hydrated via ephemeral `git stash create` + `apply`.
* Build target directories are sliced to `target-agent/` (Maven) or `build-agent/` (Gradle) and registered inside `.git/info/exclude` via `GitExcludeManager` without mutating tracked `.gitignore`.
* Per-CLI-invocation worker concurrency is bounded via `LocalWorkerLimiter` (`agent.max-concurrent-workers`, default `2`), marking overflowing tasks `TaskStatus.QUEUED`.
* `dev-agent abort` preserves `feature/{task-id}-...` branches by default unless `--delete-branch` is explicitly supplied.

## 5. Zero-JNI Native Safety (ADR-005)
* Semantic rule and ADR discovery uses a pure-Java lexical BM25 inverted index.
* Native C++ ONNX runtime JNI bindings are strictly forbidden in core search pipelines to ensure flawless GraalVM native binary portability.

## 6. Non-Locking Ephemeral State Sockets (ADR-006)
* Active tasks write atomic `state.json` heartbeats inside their isolated worktree via `StateSocketService` (temp file + `ATOMIC_MOVE` + `SecretRedactionFilter` on `lastMessage` and `branch`, plus `pidStartedAt` PID-reuse verification), mirrored by `StateSyncService`.
* Foreground CLI read commands (`StatusCommand`, `LogsCommand`) read `state.json` (and `worker.log` in secret-redacted 8 KB chunks / `.agents/runs/`) without static dependencies on `..entity..`, `jakarta.persistence..`, or `javax.sql..` (`AgentArchitectureTest.statusAndLogsCommandsMustNotAccessPersistenceOrJpa`). Full suppression of Quarkus eager datasource bootstrap for read commands is tracked in Cycle 6 (`ADR-001`, `ADR-006`).
* Detached runs spawn hidden `worker` OS subprocesses inside the worktree via `DetachedWorkerLauncher` with stdin closed immediately and stdout/stderr redirected to `worker.log`. `DesktopAlertService` sends non-blocking OS alerts using secret-redacted text.

## 7. Context-Aware Secret Redaction Quarantine (ADR-008)
* Secret scrubbing is strictly quarantined:
  * **Never applied** to raw Java/TypeScript source code or test files.
  * **Strictly applied** to tool outputs before writing to H2 `StepAuditEntity` (`SanitizedOutput`), `state.json` sockets (`lastMessage`, `branch`), `LogsCommand` streamed output, `DesktopAlertService` payloads, HTTP Authorization headers sent to remote APIs, and exported Markdown runbook metadata.

## 8. Panache Entity Encapsulation & Domain Decoupling (ADR-012)
* Panache entities (`@Entity`) are strictly quarantined within `guru.thomasweber.devagent.entity` and must never be accessed by classes outside this package.
* Classes outside `guru.thomasweber.devagent.entity` consume immutable domain models (`TaskRun`, `StepAudit`) mapped through `TaskPersistenceService`.
* Enforced via ArchUnit `entitiesMustNotBeAccessedOutsideTheirPackage`.

## 9. Global Project ID Host Isolation & Deterministic UUIDv3 (ADR-013)
* Multi-tenant task execution and state persistence identify repositories via strongly-typed `ProjectId` value objects wrapping deterministic RFC 4122 UUIDv3 instances.
* Project coordinates are extracted by `ProjectIdResolver`, normalizing Git remotes across SCP, HTTPS, SSH, and Git protocols, scrubbing credentials (`userInfo`), ports, and `.git` extensions.
* Offline / non-git directories fall back to `local:<canonicalRealPath>`.
* Raw un-normalized URLs or naked strings must never be used directly as project identifiers in domain services.

## 10. Exhaustive Crash Recovery & Physical Worktree Invariant
* Crash recovery evaluates complete candidate queues via `CrashRecoveryDetector.findAllResumableTasks(...)` rather than truncating at the queue head.
* Tasks are eligible for resumption if and only if:
  1. Task lifecycle status is non-terminal (`not in COMPLETED, FAILED, ABORTED`).
  2. The registered process handle is confirmed dead on the host OS (`ProcessHandle.of(pid).isEmpty()`).
  3. The task's physical Git worktree directory (`.git/dev-agent-worktrees/<taskId>`) physically exists on disk.
* Missing worktrees indicate intentional pruning or deletion; orphaned tasks with missing worktrees must not be selected for auto-resumption.
* Interactive CLI resumption (`dev-agent resume`) prompts the developer when multiple candidates exist, offering explicit selection.

## 11. Subprocess Lifetime & Timeout Governance
* All external operating system process forks (`git`, `mvnw`, `npm`, `vitest`, etc.) must be executed through `SubprocessExecutor` with a mandatory timeout `Duration`.
* Subprocess execution uses concurrent virtual thread stream draining with 2-tier bounded buffers (`BoundedOverflowBuffer`):
  * **Memory Tier:** In-memory byte array up to 1 MB.
  * **Disk Overflow Tier:** Temporary file on disk from 1 MB up to 10 MB.
  * **Truncation Tier:** Output exceeding 10 MB is truncated in storage while draining continues to prevent OS pipe deadlocks.
* On timeout or thread interruption, `process.destroyForcibly()` is invoked immediately, and `Thread.currentThread().interrupt()` is preserved.

## 12. Programmatic Test Transaction Scope Protocol
* Test methods (`@Test`) and lifecycle fixtures (`@BeforeEach`, `@AfterEach`) must never be annotated with `@Transactional`.
* Production services (`TaskPersistenceService`) maintain discrete transaction boundaries.
* Integration tests requiring custom transactional data seeding or entity manipulation must use programmatic transactions via `QuarkusTransaction.requiringNew().run(...)` or `.call(...)`.
* ArchUnit enforces that methods annotated with `@Test`, `@BeforeEach`, or `@AfterEach` are not annotated with `@Transactional`.
