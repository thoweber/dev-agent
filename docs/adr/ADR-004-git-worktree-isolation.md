# ADR-004: Dedicated Git Worktree Isolation & Zero-Symlink Dependency Resolution

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
When an autonomous agent runs extensive full-stack TDD cycles in the background, modifying files in the developer's active workspace disrupts their local IDE focus, creates file watch storms, and risks file locking issues. Furthermore, linking the root `node_modules` via directory symlinks or NTFS junctions risks workspace mutation (tools writing into `node_modules/.cache`), platform permission failures on Windows/WSL2, and file watcher recursion loops.

## Decision
1. **Worktree Allocation**: Execute all background task runs inside dedicated Git worktrees allocated at `.git/dev-agent-worktrees/<task-id>/`. If uncommitted changes exist in the user's workspace, create an ephemeral stash and apply it in the isolated worktree.
2. **Backend Target Slicing**: Slice Maven builds with `-Dproject.build.directory=target-agent` and Gradle with `-PbuildDir=build-agent`.
3. **Zero-Symlink Frontend Module Resolution**: Skip symlinks and NTFS junctions entirely. When invoking frontend tools (`tsc`, `ng`, `vitest`, `eslint`), inject `NODE_PATH=<root>/node_modules` and prepend `<root>/node_modules/.bin` to `PATH`.
4. **Cache & Artifact Slicing**: Slices frontend tool caches into `target-agent/` (e.g., `VITE_CACHE_DIR=target-agent/.vite-cache`, `--cacheDirectory target-agent/.jest-cache`, `--tsBuildInfoFile target-agent/tsconfig.tsbuildinfo`). The primary workspace `node_modules` remains strictly **Read-Only**.

## Consequences
### Positive
* **Absolute Workspace Isolation**: The developer's primary checkout and `node_modules` remain completely unpolluted, unlocked, and unmodified.
* **Cross-Platform Safety**: Zero dependence on Windows symlink permissions or NTFS junction quirks.
* **Instant Speed**: Reads dependencies directly from the primary workspace with 0 extra disk duplication.

### Negative
* Additional disk space used for isolated Git worktree checkouts.
* Ephemeral `tsconfig.agent.json` or `NODE_PATH` passing required for frontend build tools.

## Non-Negotiable Invariants
* Background workers must never modify the primary workspace `user.dir` or write to root `node_modules` during active runs.
* Symlinks linking the isolated worktree to root `node_modules` are strictly forbidden.
