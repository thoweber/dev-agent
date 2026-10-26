# dev-agent Core Architecture Invariants

> **Master Architecture Reference:** Consult `PROJECT_CONTEXT.md` and `docs/adr/index.md` (ADR-001 through ADR-010).

## 1. Zero-Knowledge Engine vs. Knowledge Separation (ADR-001)
* The CLI binary is a zero-knowledge execution engine. It contains **zero hardcoded project domain rules, framework keywords, or fixed documentation paths**.
* Target repositories supply declarative guidelines in `.agents/rules/` and architecture decision records in `docs/adr/`.
* Cognitive system prompts must use dynamic template variables (`{rulesContext}`, `{retrievedAdrs}`) rather than static hardcoded assumptions.

## 2. Two-Axis Extensibility (OCP) (ADR-002)
* **Build Systems (Axis 1):** Maven and npm are implemented as isolated SPI providers (`BackendBuildSystem`, `FrontendBuildSystem`). New build tools (Gradle, pnpm, bun) are added by creating SPI beans without modifying the orchestrator or CLI commands.
* **Framework Conventions (Axis 2):** Quarkus and Angular are decoupled into isolated SPI providers (`BackendFrameworkConvention`, `FrontendFrameworkConvention`). New frameworks (Spring Boot, React, Vue) implement isolated SPI beans.

## 3. Workspace Sandboxing & Path Jail (ADR-003)
* Every file path handled by tools must pass through `WorkspaceJailValidator.validateAndResolve(untrustedPath)`.
* Paths must resolve strictly within `activeWorkingRoot.toRealPath()`. Any directory traversal attempt outside this root throws a `SecurityException`.
* Direct write access to `.git/**`, `~/.config/dev-agent/**`, or the CLI binary itself is strictly forbidden.

## 4. Worktree Concurrency & Build Slicing (ADR-004)
* Background task execution runs inside dedicated Git worktrees allocated at `.git/dev-agent-worktrees/<task-id>/`.
* The developer's primary workspace (`user.dir`) remains untouched and unlocked.
* Build target directories are sliced to `target-agent/` (Maven) or `build-agent/` (Gradle) to prevent collision with primary IDE build artifacts.
* `target-agent/` and `.cache-agent/` are excluded from Git status.

## 5. Zero-JNI Native Safety (ADR-005)
* Semantic rule and ADR discovery uses a pure-Java lexical BM25 inverted index.
* Native C++ ONNX runtime JNI bindings are strictly forbidden in core search pipelines to ensure flawless GraalVM native binary portability.

## 6. Non-Locking Ephemeral State Sockets (ADR-006)
* Active tasks write atomic `state.json` heartbeats inside their isolated worktree.
* Foreground CLI commands (`dev-agent status`, `dev-agent logs`) read `state.json` directly from the filesystem without opening JDBC connections to the central H2 database, avoiding database lock crashes.

## 7. Context-Aware Secret Redaction Quarantine (ADR-008)
* Secret scrubbing is strictly quarantined:
  * **Never applied** to raw Java/TypeScript source code or test files.
  * **Strictly applied** to tool outputs before writing to H2 `StepAuditEntity` (via strongly-typed `SanitizedOutput`), HTTP Authorization headers sent to remote APIs, and exported Markdown runbook metadata.

## 8. Panache Entity Encapsulation & Domain Decoupling (ADR-012)
* Panache entities (`@Entity`) are strictly quarantined within `org.quarkus.agent.entity` and must never be accessed by classes outside this package.
* Classes outside `org.quarkus.agent.entity` consume immutable domain models (`TaskRun`, `StepAudit`) mapped through `TaskPersistenceService`.
* Enforced via ArchUnit `entitiesMustNotBeAccessedOutsideTheirPackage`.
