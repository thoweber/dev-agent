---
name: backend-engineer
description: Implements Quarkus CDI services, Picocli commands, WorkspaceContextResolver, WorkspaceJailValidator, GitWorktreeService, and dual log sanitization for dev-agent.
---

# Backend Engineer Skill (`backend-engineer`)

This skill governs the implementation of core CDI beans, Picocli CLI commands, workspace auto-detection, path validation, Git worktrees, and log sanitization in **dev-agent**. All implementations must adhere to [01-architecture-invariants.md](../../rules/01-architecture-invariants.md), [02-coding-standards.md](../../rules/02-coding-standards.md), and [05-testing-standards.md](../../rules/05-testing-standards.md).

---

## 1. Picocli & CLI Conventions

* Commands must be annotated with Picocli's `@Command` and implement `Runnable` or `Callable<Integer>`.
* Subcommands register on `DevAgentCommand` via `subcommands = { ... }` (internal subprocess entrypoints like `WorkerCommand` use `hidden = true`).
* `StatusCommand` and `LogsCommand` must NEVER import `..entity..`, `jakarta.persistence..`, or `javax.sql..` (enforced via ArchUnit); read `state.json`, `worker.log`, and `.agents/runs/` directly.
* Terminal output must use ANSI styling cleanly, degrade gracefully in non-interactive terminals, and honor `--verbose`.

---

## 2. Workspace & Sandboxing Invariants

* **Workspace Detection & Context Propagation:** `WorkspaceContextResolver.resolveRepoRoot()` skips worktree `.git` pointer files to return the primary repository root; `resolveExecutionRoot()` honors `WorktreeExecutionContext` (`ScopedValue<Path>`) when active inside an isolated worktree.
* **Canonical Path Jail:** `WorkspaceJailValidator` validates every path against `activeWorkingRoot.toRealPath()`. Reject any path traversing outside or attempting to modify `.git/**` or `~/.config/dev-agent/**`.
* **Git Worktrees & Exclude Slicing:** Detached and isolated execution runs inside `.git/dev-agent-worktrees/<task-id>/`. Hydrate uncommitted changes via `git stash create` + `apply`, exclude configured build target directories (`target-agent/`) in `.git/info/exclude` via `GitExcludeManager`, and preserve `feature/{task-id}-...` branches on `abort` unless `--delete-branch` is specified.
* **Detached Workers, Concurrency & Alerts:** Enforce per-CLI-invocation concurrency via `LocalWorkerLimiter` (`agent.max-concurrent-workers`, default `2`), spawn background workers via `DetachedWorkerLauncher` (closing stdin immediately and redirecting output to `worker.log`), emit redacted atomic sockets via `StateSocketService`, and send OS notifications via `DesktopAlertService`.

---

## 3. Two-Axis SPI Implementations

* Axis 1 execution centers on `BackendBuildSystem` (Maven) for Cycles 1–5, with `FrontendBuildSystem` deferred to post-Cycle 5. When implementing build systems, ensure `sanitizeOutput()` strips task execution noise and isolates error lines.
* When implementing `BackendFrameworkConvention` or `FrontendFrameworkConvention`, keep framework-specific flags and baseline invariants declarative.

---

## 4. Test Engineering & Fixture Invariants

* **1-to-1 Test Mapping:** Author dedicated `<TargetClassName>Test.java` for every service, validator, and command.
* **Zero-Reflection Policy:** Verify observable behaviors through public APIs; never use reflection (`field.setAccessible(true)`) to manipulate private state in tests.
* **Canonical Fixtures:** Use `TaskRunMother`, `StepAuditMother`, `WorktreeStateMother`, and `WorktreeTestFixture` instead of repeating inline builder code. All CLI unit tests (`*CommandTest`) must pass `--dir=<tempDir>` so test execution never mutates the live repository.
* **Programmatic Transactions:** Never annotate test methods with `@Transactional`; use `QuarkusTransaction.requiringNew()` for integration tests.
