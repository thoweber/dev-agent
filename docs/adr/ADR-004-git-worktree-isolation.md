# ADR-004: Dedicated Git Worktree Isolation for Background Tasks

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
When an autonomous agent runs extensive full-stack TDD cycles in the background, modifying files in the developer's active workspace disrupts their local IDE focus, creates file watch storms, and risks file locking issues.

## Decision
Execute all background task runs inside dedicated Git worktrees allocated at `.git/dev-agent-worktrees/<task-id>/`. If uncommitted changes exist in the user's workspace, create an ephemeral stash and apply it in the isolated worktree. Slice build output to `target-agent/`.

## Consequences
### Positive
* Zero workspace disruption: the developer's primary checkout stays clean and unlocked.
* Sliced target directories prevent colliding with primary compilation artifacts.

### Negative
* Additional disk space used for isolated worktree checkouts.

## Non-Negotiable Invariants
* Background workers must never modify the primary workspace `user.dir` during active runs.
