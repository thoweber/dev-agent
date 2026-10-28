# dev-agent: Master System Architecture Context & Implementation Blueprint

> **Notice for Antigravity CLI Agent (`agy`):**
> You are bootstrapping **dev-agent**, an autonomous software engineering CLI, pair programmer, and quality orchestrator built on Quarkus 3.40.x, Picocli, and Quarkus-LangChain4j. Strictly adhere to the architecture, boundary rules, two-axis extensibility SPIs, and the Architectural Decision Records (ADRs) specified below.

---

## 1. System Identity & Core Tech Stack

* **System Name:** `dev-agent`
* **Architecture Style:** Hexagonal CLI Engine with Two-Axis SPI Extensibility
* **Java Version:** Java 25 LTS (Records, Sealed Classes, Pattern Matching, Virtual Threads)
* **Core Framework:** Quarkus 3.40.x (`3.40.1`) + Picocli (`quarkus-picocli`)
* **Cognitive Orchestration:** Quarkus-LangChain4j 1.14.x (`quarkus-langchain4j-core`)
* **State Management:** Embedded H2 (`~/.config/dev-agent/data/agent.mv.db`) + Non-locking Ephemeral Sockets (`state.json`)
* **Compilation Target:** GraalVM Mandrel Native Executable (`~/.local/bin/dev-agent`) with $<50\text{ ms}$ cold boot
* **Customer Zero:** OmniDepot (`../OmniDepot`)

---

## 2. Package Layout & Boundary Rules

To enforce strict boundary isolation and native GraalVM compatibility, the codebase uses the following package structure under `org.quarkus.agent`:

```
dev-agent/
└── src/main/java/org/quarkus/agent/
    ├── DevAgentCommand.java           # TopCommand Picocli Entrypoint
    ├── cli/                           # Picocli Subcommands (Init, Plan, Feature, Status, Logs, Resume)
    ├── entity/                        # Panache Persistence Entities & TaskPersistenceService (Encapsulated)
    ├── spi/
    │   ├── build/                     # Axis 1: BackendBuildSystem, FrontendBuildSystem, SanitizedLog
    │   └── framework/                 # Axis 2: BackendFrameworkConvention, FrontendFrameworkConvention
    ├── workspace/                     # WorkspaceContextResolver, WorkspaceJailValidator, GitWorktreeService, SubprocessExecutor
    ├── cognitive/                     # AiServices, HierarchicalMemoryStore, BM25 Inverted Index RAG, Caffeine Decision Cache
    ├── model/                         # Reflection-Registered Immutable Records (ProjectId, TaskRun, StepAudit, GateVerdict)
    ├── remote/                        # Declarative GitHub, SonarCloud, and CodeRabbit API Clients
    └── gui/                           # Embedded Vert.x HTTP Review Server (:9999)
```

### Critical Boundary Constraints (ArchUnit Enforced):

1. **Engine vs. Knowledge Separation:** The engine contains zero hardcoded target domain rules. Target project guidelines reside in `./.agents/rules/` and `docs/adr/`.
2. **Two-Axis Open-Closed Principle:**
   * **Axis 1 (Build):** `BackendBuildSystem` and `FrontendBuildSystem` SPIs decouple build tools (Maven, npm, Gradle, pnpm).
   * **Axis 2 (Framework):** `BackendFrameworkConvention` and `FrontendFrameworkConvention` SPIs decouple framework idioms (Quarkus, Angular, Spring Boot, React).
3. **Canonical Path Sandboxing & Strongly-Typed `RepoPath`:** `WorkspaceJailValidator` validates every path against `activeWorkingRoot.toRealPath()`. Symbolic links targeting or originating within repository paths, traversal escapes, or `.git/**` / `~/.config/dev-agent/**` writes immediately throw `SecurityException`. File write sinks strictly consume `RepoPath`.
4. **Context-Aware Secret Redaction Quarantine & `SanitizedOutput`:** Secret scrubbing is quarantined: never applied to raw Java/TypeScript source code or test files, but strictly applied to tool outputs before writing to audit entities and remote APIs. Persistence sinks strictly consume `SanitizedOutput`.
5. **Panache Entity Encapsulation (ADR-012):** Panache entities (`@Entity`) are strictly quarantined to `org.quarkus.agent.entity` and must never be accessed by classes outside their package. Services outside `entity` consume immutable domain models (`TaskRun`, `StepAudit`) mapped through `TaskPersistenceService`.
6. **Global Project ID Host Isolation & Deterministic UUIDv3 (ADR-013):** Repositories are identified via strongly-typed `ProjectId` value objects wrapping deterministic RFC 4122 UUIDv3 instances derived from canonical, credential-scrubbed coordinates (`host/namespace/repo` or `local:<path>`).
7. **Exhaustive Crash Recovery & Physical Worktree Verification:** `CrashRecoveryDetector` evaluates complete active candidate queues (`findAllResumableTasks`). Tasks are resumable strictly if non-terminal, registered PID is dead on host OS, and their Git worktree physically exists on disk.
8. **Subprocess Lifetime & Timeout Governance:** All external process forks (`git`, `mvnw`, etc.) are executed via `SubprocessExecutor` with mandatory timeouts, non-blocking virtual thread stream draining, 2-tier bounded output buffering (`BoundedOverflowBuffer`: 1 MB RAM -> 10 MB Disk overflow), and immediate `destroyForcibly()` on timeout/interruption.
9. **Programmatic Test Transaction Scope Protocol:** Test and setup methods (`@Test`, `@BeforeEach`, `@AfterEach`) must never be annotated with `@Transactional`. Integration tests use programmatic transactions (`QuarkusTransaction.requiringNew().run(...)`).
10. **Tool Annotation Scoping:** All `@Tool`-annotated methods MUST be declared inside `@ApplicationScoped` classes.
11. **Native Reflection Registration:** All classes and records residing in `..model..`, `..entity..`, `..remote..`, or `..gui..` MUST be annotated with `@RegisterForReflection`.

---

## 3. Two-Axis SPI Specifications

### Axis 1: Build Tooling Execution SPI (`org.quarkus.agent.spi.build`)
* `BackendBuildSystem`: `priority()`, `isApplicable(Path root)`, `resolveExecutable(Path root)`, `buildCompileCommand(...)`, `buildTestCommand(...)`, `sanitizeOutput(...)`.
* `FrontendBuildSystem`: `priority()`, `isApplicable(Path root)`, `resolveExecutable(Path root)`, `buildTypeCheckCommand(...)`, `buildTestCommand(...)`, `buildClientCodegenCommand(...)`, `sanitizeOutput(...)`.

### Axis 2: Framework Conventions & Idioms SPI (`org.quarkus.agent.spi.framework`)
* `BackendFrameworkConvention`: `priority()`, `isApplicable(Path root)`, `getFrameworkName()`, `getPersonaInstructions()`, `getBaselineInvariants()`, `getTestExecutionFlags()`, `getOpenApiExportFlags(...)`.
* `FrontendFrameworkConvention`: `priority()`, `isApplicable(Path root)`, `getFrameworkName()`, `getComponentGuidance()`, `getBaselineInvariants()`, `resolveSourceRoot(...)`.

---

## 4. Master Architectural Decision Record (ADR) Index

When making architectural or implementation decisions, ensure full compliance with all 13 ADRs:

| ADR ID | Title | Summary & Implementation Guardrails |
| :--- | :--- | :--- |
| **ADR-001** | Quarkus Picocli Native CLI | Ahead-of-Time native binary targeting sub-50ms cold boot via GraalVM/Mandrel. |
| **ADR-002** | Two-Axis SPI Extensibility | Independent SPIs for build systems and framework conventions. |
| **ADR-003** | Workspace Path Jail | Canonical realPath boundary containment against traversal attacks. |
| **ADR-004** | Git Worktree Isolation | Dedicated worktrees under `.git/dev-agent-worktrees/<task-id>/` for non-blocking concurrency. |
| **ADR-005** | Pure-Java BM25 Retrieval | Zero-JNI lexical inverted index eliminating native C++ ONNX library failures. |
| **ADR-006** | Non-Locking State Sockets | Ephemeral `state.json` heartbeats avoiding H2 file lock deadlocks during telemetry reads. |
| **ADR-007** | Contract-Driven Sync | Automated SmallRye OpenAPI spec extraction and TypeScript client code generation. |
| **ADR-008** | Context-Aware Secret Redaction | Strict redaction quarantine excluding raw source and test code. |
| **ADR-009** | Cache & Circuit Breaker | Caffeine gatekeeper memoization and normalized error loop circuit breaker. |
| **ADR-010** | Embedded Vert.x Review GUI | Embedded Vert.x HTTP review server on `:9999` with Storybook iframe integration. |
| **ADR-011** | Hierarchical Memory Invariants | 3-tier memory store: Tier 1 immutable pinned system invariants, Tier 2 ephemeral task turns with atomic tool request/execution eviction, Tier 3 long-term knowledge. |
| **ADR-012** | Panache Entity Encapsulation | Panache entities quarantined to `entity` package; external services consume immutable `TaskRun`/`StepAudit` domain models via `TaskPersistenceService`. |
| **ADR-013** | Global Project ID Host Isolation | Multi-tenant isolation using RFC 4122 UUIDv3 coordinates derived from remote URLs or local paths with host isolation and credential scrubbing. |

---

## 5. Retrospective Remediation Learnings & Engineering Guardrails

Following systematic reviews across Cycle 1 and Cycle 2, all 10 high-priority architectural and security remediations have been implemented and codified:

| Priority | Task ID | Core Focus & Guardrail | Resolution Vehicle |
|:---:|:---:|:---|:---|
| **P1** | **REM-01** | Physical Symlink & Canonical Path Jail (CWE-59) | Strongly-typed `RepoPath` value object & canonical symlink validation |
| **P2** | **REM-02** | Universal Credential Scrubbing Quarantine (CWE-312) | Strongly-typed `SanitizedOutput` domain value object for audit sinks |
| **P3** | **REM-03** | Regex Sanitization Bounds & Length Flexibility | Bounded regexes, word boundaries, quoted space handling |
| **P4** | **REM-04** | 3-Tier Hierarchical Memory Turn Invariants | Atomic tool eviction, Tier 1 system consolidation (ADR-011) |
| **P5** | **REM-05** | Panache Entity Encapsulation & Domain Decoupling | Entity package isolation, immutable `TaskRun`/`StepAudit`, ADR-012 |
| **P6** | **REM-06** | Global Project ID Host Isolation & UUIDv3 | Deterministic RFC 4122 UUIDv3 coordinate hashing, ADR-013 |
| **P7** | **REM-07** | Exhaustive Crash Recovery & Worktree Health Inspection | Complete queue evaluation `findAllResumableTasks`, physical worktree check |
| **P8** | **REM-08** | Subprocess Lifetime & Timeout Governance | Centralized `SubprocessExecutor`, 2-tier `BoundedOverflowBuffer` (1 MB RAM -> 10 MB Disk) |
| **P9** | **REM-09** | Two-Phase Test Execution & JaCoCo Isolation | Failsafe for `*IT.java` (`<quarkus.jacoco.enabled>false`), Surefire for pure unit test coverage |
| **P10** | **REM-10** | Programmatic Test Transaction Scope Protocol | Prohibit `@Transactional` on test methods, use `QuarkusTransaction.requiringNew()`, ArchUnit rule |

---

## 6. Development Scripts & Verification Tooling

To ensure high-speed developer ergonomics and autonomous feedback loops, key utility scripts are maintained in `scripts/`:

* `scripts/parse_jacoco.py`: Parses JaCoCo CSV reports (`target/site/jacoco/jacoco.csv`), displays metric tables (Instructions, Branches, Lines, Complexity, Methods), flags classes falling below the $\ge 80\%$ quality threshold, and supports `--fail-under <pct>` gating for automated local verification and pre-commit hooks.
  * Audit coverage: `./scripts/parse_jacoco.py --threshold 80.0`
  * Gate verification: `./scripts/parse_jacoco.py --threshold 80.0 --fail-under 80.0`
  * Verbose class breakdown: `./scripts/parse_jacoco.py --verbose`
* `scripts/check_pr_reviews.py`: Ingests, audits, and remediates GitHub PR review threads (including CodeRabbit AI reviews and peer discussions). Detects active in-progress reviews, parses findings with severity and CWE categorization, and enables CLI-driven thread replies and resolutions.
  * Audit open threads: `./scripts/check_pr_reviews.py`
  * Detailed inspection with prompts: `./scripts/check_pr_reviews.py --details`
  * Automated JSON export: `./scripts/check_pr_reviews.py --json`
  * Check active review status: `./scripts/check_pr_reviews.py --check-status`
  * Reply & resolve thread: `./scripts/check_pr_reviews.py --thread <THREAD_ID> --reply "Resolved..." --resolve`
  * Gate check on unresolved threads: `./scripts/check_pr_reviews.py --fail-on-unresolved`
