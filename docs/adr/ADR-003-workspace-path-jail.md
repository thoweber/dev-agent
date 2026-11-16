# ADR-003: Canonical Path Jail and Workspace Sandboxing

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
Autonomous agents generate file paths based on LLM output. Without strict sandboxing, path traversal patterns (`../../etc/passwd` or symbolic link escapes) could corrupt the developer's system or overwrite global Git metadata.

## Decision
Implement `WorkspaceJailValidator` and the strongly-typed `RepoPath` value object. All relative or untrusted paths are resolved against `activeWorkingRoot.toRealPath()`. Any path that does not begin with the canonical root, contains traversal elements, or is a symbolic link throws a `SecurityException`. Modifications to `.git/**`, `~/.config/dev-agent/**`, or the CLI binary are strictly blocked.

File write sinks must strictly consume `RepoPath`, making un-jailed or symlinked file writes unrepresentable at compile time.

## Consequences
### Positive
* Complete filesystem sandboxing and protection against directory traversal and symlink attacks (CWE-59).
* Compile-time safety: write sinks require `RepoPath`, preventing raw `String` or unvalidated `Path` usage.
* Predictable, reproducible workspace mutations.

### Negative
* Requires resolving canonical paths for all file operations.

## Non-Negotiable Invariants
* Every LLM/agent file access tool must call `WorkspaceJailValidator.validateAndResolve()` or `validateForWrite()`.
* Agent file write operations must strictly consume `RepoPath`, blocking writes to `.git/**`, `~/.config/dev-agent/**`, or symbolic links.
* Trusted internal engine lifecycle services (`GitWorktreeService`, `GitExcludeManager`, `StateSocketService`, `DetachedWorkerLauncher`, `LogsCommand`) are the sole bounded exception permitted to touch `.git/info/exclude` or `.git/dev-agent-worktrees/<task-id>/{state.json,state.json.tmp,worker.log}`, and MUST enforce strict `taskId` allowlist validation (`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`, no `..`) plus full-ancestor symlink rejection via `StateSocketService.validateWorktreePathSymlinks(...)`.
* Symbolic links within repository or worktree metadata paths (`gitdir`, `commondir`, `.git`, `.git/info/exclude`, `.agents/runs/*.md`) are strictly forbidden and immediately rejected with `SecurityException`.
