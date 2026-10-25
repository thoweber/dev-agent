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
    ├── spi/
    │   ├── build/                     # Axis 1: BackendBuildSystem, FrontendBuildSystem, SanitizedLog
    │   └── framework/                 # Axis 2: BackendFrameworkConvention, FrontendFrameworkConvention
    ├── workspace/                     # WorkspaceContextResolver, WorkspaceJailValidator, GitWorktreeService
    ├── cognitive/                     # AiServices, BM25 Inverted Index RAG, Caffeine Decision Cache
    ├── model/                         # Reflection-Registered Records (GateVerdict, StepAuditEntity)
    ├── remote/                        # Declarative GitHub, SonarCloud, and CodeRabbit API Clients
    └── gui/                           # Embedded Vert.x HTTP Review Server (:9999)
```

### Critical Boundary Constraints (ArchUnit Enforced):

1. **Engine vs. Knowledge Separation:** The engine contains zero hardcoded target domain rules. Target project guidelines reside in `./.agents/rules/` and `docs/adr/`.
2. **Two-Axis Open-Closed Principle:**
   * **Axis 1 (Build):** `BackendBuildSystem` and `FrontendBuildSystem` SPIs decouple build tools (Maven, npm, Gradle, pnpm).
   * **Axis 2 (Framework):** `BackendFrameworkConvention` and `FrontendFrameworkConvention` SPIs decouple framework idioms (Quarkus, Angular, Spring Boot, React).
3. **Canonical Path Sandboxing:** `WorkspaceJailValidator` validates every path against `activeWorkingRoot.toRealPath()`. Access outside boundary throws `SecurityException`.
4. **Tool Annotation Scoping:** All `@Tool`-annotated methods MUST be declared inside `@ApplicationScoped` classes.
5. **Native Reflection Registration:** All classes and records residing in `..model..`, `..entity..`, `..remote..`, or `..gui..` MUST be annotated with `@RegisterForReflection`.

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

When making code generation decisions, ensure full compliance with all 10 ADRs:

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
