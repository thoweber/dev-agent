---
name: backend-engineer
description: Implements Quarkus CDI services, Picocli commands, WorkspaceContextResolver, WorkspaceJailValidator, GitWorktreeService, and dual log sanitization for dev-agent.
---

# Backend Engineer Skill (`backend-engineer`)

This skill governs the implementation of core CDI beans, Picocli CLI commands, workspace auto-detection, path validation, Git worktrees, and log sanitization in **dev-agent**.

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
