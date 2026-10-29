---
name: backend-engineer
description: Implements Quarkus CDI services, Picocli commands, WorkspaceContextResolver, WorkspaceJailValidator, GitWorktreeService, and dual log sanitization for dev-agent.
---

# Backend Engineer Skill (`backend-engineer`)

This skill governs the implementation of core CDI beans, Picocli CLI commands, workspace auto-detection, path validation, Git worktrees, and log sanitization in **dev-agent**. All implementations must adhere to [01-architecture-invariants.md](file:///home/developer/projects/dev-agent/.agents/rules/01-architecture-invariants.md), [02-coding-standards.md](file:///home/developer/projects/dev-agent/.agents/rules/02-coding-standards.md), and [05-testing-standards.md](file:///home/developer/projects/dev-agent/.agents/rules/05-testing-standards.md).

---

## 1. Picocli & CLI Conventions

* Commands must be annotated with Picocli's `@Command` and implement `Runnable` or `Callable<Integer>`.
* Subcommands register on `DevAgentCommand` via `subcommands = { ... }`.
* Terminal output must use ANSI styling cleanly, degrade gracefully in non-interactive terminals, and honor `--verbose`.

---

## 2. Workspace & Sandboxing Invariants

* **Workspace Detection:** `WorkspaceContextResolver` discovers repo root by walking parent directories for `.git`, `pom.xml`, or `build.gradle`.
* **Canonical Path Jail:** `WorkspaceJailValidator` validates every path against `activeWorkingRoot.toRealPath()`. Reject any path traversing outside or attempting to modify `.git/**` or `~/.config/dev-agent/**`.
* **Git Worktrees:** Detached execution runs inside `.git/dev-agent-worktrees/<task-id>/`. If uncommitted changes exist, create an ephemeral stash and apply it in the worktree.

---

## 3. Two-Axis SPI Implementations

* When implementing `BackendBuildSystem` or `FrontendBuildSystem`, ensure `sanitizeOutput()` strips task execution noise and isolates error lines.
* When implementing `BackendFrameworkConvention` or `FrontendFrameworkConvention`, keep framework-specific flags and baseline invariants declarative.

---

## 4. Test Engineering & Fixture Invariants

* **1-to-1 Test Mapping:** Author dedicated `<TargetClassName>Test.java` for every service, validator, and command.
* **Zero-Reflection Policy:** Verify observable behaviors through public APIs; never use reflection (`field.setAccessible(true)`) to manipulate private state in tests.
* **Canonical Fixtures:** Use `TaskRunMother` and `WorktreeTestFixture` instead of repeating inline builder code.
* **Programmatic Transactions:** Never annotate test methods with `@Transactional`; use `QuarkusTransaction.requiringNew()` for integration tests.
