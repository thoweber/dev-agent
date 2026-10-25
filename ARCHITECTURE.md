# Technical Architecture Specification: Generalized Full-Stack Dev-Agent (`dev-agent`)

---

## 1. Executive Summary & Foundational Paradigm

The **Generalized Dev-Agent** (`dev-agent`) is an autonomous software engineering CLI, pair programmer, and quality orchestrator built on **Quarkus 3.x**, **Picocli**, and **Quarkus-LangChain4j**. 

The system operates as an **independent developer infrastructure tool** hosted in its own repository, compiled via GraalVM/Mandrel into a globally installed native executable (`~/.local/bin/dev-agent`) with sub-50ms cold boot times. It is completely decoupled from any single product or repository domain. Any target repository—whether OmniDepot ("Customer Zero") or an independent microservice—serves purely as an execution workspace governed by declarative configuration and architectural knowledge located in `./.agents/`.

```
                     ┌────────────────────────────────────────────────────────┐
                     │            User Invocation / CI Trigger                │
                     │   (e.g., dev-agent feature "Add Metrics Dashboard")    │
                     └───────────────────────────┬────────────────────────────┘
                                                 │
                                                 ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ HOST RUNTIME & AUTO-DETECTION LAYER (quarkus-picocli & GraalVM Native)                                  │
│                                                                                                         │
│  Picocli CLI Engine ──► Dynamic Workspace Auto-Detector ──► Configuration Cascade Service               │
│  (init, plan, feature,  (Repo Root, Build System, Frontend) (CLI > ENV > ~/.config > .agents > App)    │
│   resume, status, logs)                                                                                 │
└────────────────────────────────────────┬────────────────────────────────────────────────────────────────┘
                                         │ CDI Injection
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ TWO-AXIS EXTENSIBILITY & EXECUTION LAYER                                                                │
│                                                                                                         │
│  Axis 1: BackendBuildSystem & FrontendBuildSystem SPIs   Axis 2: BackendFrameworkConvention SPI        │
│  • MavenBuildSystem (Active) / Gradle (Extension)        • QuarkusConvention (Active) / Spring Boot     │
│  • NpmBuildSystem (Active)   / Pnpm, Yarn (Extension)   • AngularConvention (Active) / React, Vue      │
└────────────────────────────────────────┬────────────────────────────────────────────────────────────────┘
                                         │ Tool Dispatches
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ COGNITIVE ORCHESTRATION & SEMANTIC LAYER (quarkus-langchain4j)                                          │
│                                                                                                         │
│  ┌────────────────────────┐      ┌─────────────────────────┐      ┌──────────────────────────────────┐  │
│  │ Universal Planner      │      │ Universal Coder         │      │ Invariant Gatekeeper             │  │
│  │ (lead-dev / Pro Model) │ ───► │ (coder / Claude/Ollama) │ ◄──► │ (architect / Flash or Ollama)    │  │
│  │ High-Context Breakdown │      │ Java & TypeScript Loops │      │ Deterministic JSON Audit (Temp 0)│  │
│  └────────────────────────┘      └────────────┬────────────┘      └──────────────────────────────────┘  │
│                                               │                                      ▲                  │
│  Deterministic Caching (quarkus-cache)        │                  Pure-Java Lexical Retrieval (BM25)     │
│  (Memoizes Gate verdicts by AST & rule hash)  │                  (Zero-JNI, Native GraalVM Safe RAG)    │
└────────────────────────────────────────┬──────┴──────────────────────────────────────┬──────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ ISOLATION, STATE & PROCESS TOPOLOGY LAYER                                                               │
│                                                                                                         │
│  Dedicated Git Worktrees (.git/dev-agent-worktrees/<task-id>/) ──► Ephemeral State Socket (state.json)     │
│  • Non-blocking workspace concurrency (main stays clean)      • Non-locking CLI telemetry reads         │
│  • Build isolation: -Dproject.build.directory=target-agent    • Central H2 DB (~/.config/dev-agent/)    │
│  • Concurrency Gate: max-concurrent-workers = 2              • Auto-Projected Markdown (.agents/runs/) │
└────────────────────────────────────────┬────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ REMOTE AUTOMATION, CONTRACT SYNC & LOCAL GUI REVIEW                                                     │
│                                                                                                         │
│  • OpenAPI Contract Sync (SmallRye compile flags ──► @openapitools/openapi-generator-cli TypeScript)    │
│  • Declarative Reactive GitHub & SonarCloud API Clients (Draft PRs, CI Remediations, Promotion)        │
│  • CodeRabbit 3-Way Triage (Fix, ADR Rebuttal, Dismissal) via GraphQL Node ID thread resolution        │
│  • Embedded Reactive Web Review Engine (--gui on http://localhost:9999 + Storybook iframe on :6006)    │
└─────────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Architectural Invariants

1. **Engine vs. Knowledge Separation**: The CLI engine contains **zero hardcoded project domain rules, framework keywords, or fixed documentation paths**. The binary is a zero-knowledge execution engine; target projects provide their declarative guidelines in `.agents/rules/` and architecture decision records in `docs/adr/`.
2. **Two-Axis Open-Closed Principle (OCP)**: Build tooling (Maven, npm) and framework idioms (Quarkus, Angular) are decoupled into independent Service Provider Interfaces (SPIs). Adding Gradle, pnpm, Spring Boot, or React requires implementing isolated SPI beans with zero modifications to orchestrator, database, worktree, or CLI commands.
3. **Workspace Concurrency & Physical Isolation**: Background execution runs strictly inside dedicated Git worktrees (`.git/dev-agent-worktrees/<task-id>/`) with build output slicing (`target-agent/`). The developer's primary working tree (`user.dir`) remains unlocked, untouched, and unpolluted.
4. **Zero-JNI Native Safety**: Local semantic rule discovery relies on a pure-Java lexical BM25 inverted index, eliminating C++ native library JNI bindings that cause native image compilation and execution failures across operating systems.
5. **Contract-Driven Full-Stack Synchronization**: Backend data contracts and client models never drift. The agent exports OpenAPI schemas directly from build artifacts and regenerates typed TypeScript clients before frontend implementations begin.
6. **Multi-Tenancy & Ephemeral State Sockets**: Global relational state resides in an embedded H2 database (`~/.config/dev-agent/data/agent.mv.db`) namespaced by project. To prevent database lock deadlocks between background workers and foreground CLI status queries, active tasks emit atomic `state.json` heartbeats within their worktrees.

---

## 2. Two-Axis Extensibility Architecture (Build & Framework SPIs)

To guarantee that the agent begins with **Quarkus/Maven** and **Angular/npm** while remaining open for **Gradle**, **pnpm**, **yarn**, **bun**, **Spring Boot**, or **React**, capabilities are split into two orthogonal CDI extension axes.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│ HOST ORCHESTRATOR / EXECUTION TOOL PROXY                                              │
└───────────────────────────────────────────┬────────────────────────────────────────────┘
                                            │ Resolves via Capability Detection
                     ┌──────────────────────┴──────────────────────┐
                     ▼                                             ▼
┌──────────────────────────────────────────┐  ┌──────────────────────────────────────────┐
│ Axis 1: BuildSystemStrategy (SPI)        │  │ Axis 2: FrameworkConvention (SPI)        │
├──────────────────────────────────────────┤  ├──────────────────────────────────────────┤
│ + boolean isApplicable(Path root)        │  │ + boolean isApplicable(Path root)        │
│ + String resolveExecutable(Path root)    │  │ + String getFrameworkName()              │
│ + List<String> buildCompileCommand(...)  │  │ + String getPersonaInstructions()        │
│ + List<String> buildTestCommand(...)     │  │ + List<String> getBaselineInvariants()   │
│ + SanitizedLog sanitizeOutput(...)       │  │ + List<String> getTestExclusionFlags()   │
└────────────────────┬─────────────────────┘  └────────────────────┬─────────────────────┘
                     │                                             │
      ┌──────────────┴──────────────┐               ┌──────────────┴──────────────┐
      ▼                             ▼               ▼                             ▼
┌──────────────┐             ┌──────────────┐ ┌──────────────┐             ┌──────────────┐
│ MavenBuild-  │ (Active)    │ GradleBuild- │ │ Quarkus-     │ (Active)    │ SpringBoot-  │
│ System       │             │ System       │ │ Convention   │             │ Convention   │
└──────────────┘             └──────────────┘ └──────────────┘             └──────────────┘
┌──────────────┐             ┌──────────────┐ ┌──────────────┐             ┌──────────────┐
│ NpmBuild-    │ (Active)    │ PnpmBuild-   │ │ Angular-     │ (Active)    │ React-       │
│ System       │             │ System       │ │ Convention   │             │ Convention   │
└──────────────┘             └──────────────┘ └──────────────┘             └──────────────┘
```

### Axis 1: Build Tooling Execution SPI (`BuildSystemStrategy.java`)

```java
public interface BackendBuildSystem {
    int priority();
    boolean isApplicable(Path projectRoot);
    String resolveExecutable(Path projectRoot);
    List<String> buildCompileCommand(Path projectRoot, String modulePath, String targetDir);
    List<String> buildTestCommand(Path projectRoot, String modulePath, String testPattern, String targetDir);
    SanitizedLog sanitizeOutput(String rawStdout, String rawStderr);
}

public interface FrontendBuildSystem {
    int priority();
    boolean isApplicable(Path frontendRoot);
    String resolveExecutable(Path frontendRoot);
    List<String> buildTypeCheckCommand(Path frontendRoot);
    List<String> buildTestCommand(Path frontendRoot, String testPattern);
    List<String> buildClientCodegenCommand(Path frontendRoot, Path openApiSchema, Path targetDir);
    SanitizedLog sanitizeOutput(String rawStdout, String rawStderr);
}
```

#### Active Implementations

1. **`MavenBuildSystem`**:
   * Probes for `./mvnw` $\rightarrow$ `mvn`.
   * Enforces `-Dproject.build.directory=target-agent` to slice build targets.
   * Derives `-pl <relative-module-path> -am` for multi-module reactors; omits `-pl` for single-module repositories.
   * Injects Surefire memory limits: `-DargLine="-Xmx512m -XX:+TieredCompilation -XX:TieredStopAtLevel=1"`.
   * Sanitizes output by dropping download progress and task execution headers, isolating `[ERROR]` compilation lines and Surefire assertion failures.

2. **`NpmBuildSystem`**:
   * Resolves executable via an embedded fallback chain: checks `frontend/node/npm` (downloaded by `frontend-maven-plugin`) before searching system `PATH`.
   * Enforces headless, non-interactive execution flags: `npm test -- --watch=false --browsers=ChromeHeadless` (or `--watch=false --runInBand --ci` for Jest/Vitest), avoiding terminal hangs.
   * Executes type checks via `npx tsc --noEmit`.
   * Compacts npm and Vite/Webpack noise down to error codes (`TS2304`, `TS2322`) and failing assertions.

---

### Axis 2: Framework Conventions & Idioms SPI (`FrameworkConvention.java`)

```java
public interface BackendFrameworkConvention {
    int priority();
    boolean isApplicable(Path projectRoot);
    String getFrameworkName();
    String getPersonaInstructions();
    List<String> getBaselineInvariants();
    List<String> getTestExecutionFlags();
    List<String> getOpenApiExportFlags(Path targetDir);
}

public interface FrontendFrameworkConvention {
    int priority();
    boolean isApplicable(Path frontendRoot);
    String getFrameworkName();
    String getComponentGuidance();
    List<String> getBaselineInvariants();
    Path resolveSourceRoot(Path frontendRoot);
}
```

#### Active Implementations

1. **`QuarkusConvention`**:
   * Injects CDI scoping invariants (`@ApplicationScoped` vs `@RequestScoped`) and Vert.x reactive non-blocking loop rules.
   * Directs TDD tests to use `@QuarkusTest` and `@InjectMock`.
   * **Dev Services Container Suppressor**: Injects `-Dquarkus.devservices.enabled=false -Dquarkus.datasource.jdbc.url=jdbc:h2:mem:tdd_test;DB_CLOSE_DELAY=-1 -Dquarkus.datasource.db-kind=h2` during inner micro-TDD loops to prevent unexpected Docker container pulls and startup crashes.
   * Exports OpenAPI schemas by passing `-Dquarkus.smallrye-openapi.store-schema-file=true -Dquarkus.smallrye-openapi.store-schema-directory=target-agent/openapi` during compilation.

2. **`AngularConvention`**:
   * Parses `angular.json` to resolve the true `sourceRoot` (e.g., `projects/<app>/src`) rather than assuming flat directories.
   * Enforces Standalone Components, Material Design 3 design tokens, and Angular Signals reactivity.
   * **Reactivity Bridge Invariant**: Enforces that generated OpenAPI client RxJS Observables are bridged using `toSignal(service.call(), { initialValue: null })` from `@angular/core/rxjs-interop`, forbidding raw `.subscribe()` calls in component controllers.
   * Mandates accompanying `*.stories.ts` files for reusable UI components.

---

## 3. Host Runtime, Workspace Sandboxing & Path Jails

The host layer is packaged as an independent CLI built on `quarkus-picocli`, configured for Ahead-of-Time (AOT) GraalVM compilation.

### Dynamic Workspace Auto-Detection
On invocation, `WorkspaceContextResolver` inspects `System.getProperty("user.dir")`:
1. **Workspace Root (`REPO_ROOT`)**: Discovered by traversing parent directories to locate the root `.git` directory, root `pom.xml`, or `build.gradle`.
2. **Subsystem Discovery**: Scans for frontend roots (`frontend/`, `ui/`, `src/main/webapp/`, or Angular workspace projects) and checks for package configurations.
3. **Target Build Isolation**: Ensures that all build executions write to `target-agent/` (Maven) or `build-agent/` (Gradle). Automatically adds `target-agent/` and `.cache-agent/` to the repository's `.git/info/exclude` to prevent dirtying `git status`.

### Sandboxing & Canonical Path Jail (`WorkspaceJailValidator`)
To prevent directory traversal or accidental host corruption:
* All path inputs must resolve strictly within the active execution root (`activeWorkingRoot.toRealPath()`):
  ```java
  public Path validateAndResolve(String untrustedPath) {
      try {
          Path realRoot = activeWorkingRoot.toRealPath();
          Path resolved = realRoot.resolve(untrustedPath).toRealPath();
          if (!resolved.startsWith(realRoot)) {
              throw new SecurityException("Jailbreak attempt: Path traverses outside boundary: " + untrustedPath);
          }
          return resolved;
      } catch (IOException e) {
          // If file does not yet exist, validate normalized parent
          Path parent = activeWorkingRoot.resolve(untrustedPath).normalize().getParent();
          if (!parent.startsWith(activeWorkingRoot)) {
              throw new SecurityException("Jailbreak attempt: " + untrustedPath);
          }
          return activeWorkingRoot.resolve(untrustedPath).normalize();
      }
  }
  ```
* Explicitly forbids modifications to `.git/**`, global configurations (`~/.config/dev-agent/**`), or the CLI binary itself.

### Tool Scoping via Dynamic Proxy (`ExecutionToolProxy`)
Prevents backend-only projects from receiving frontend LLM tools:
* If no frontend subsystem is detected in the workspace, frontend methods (`runFrontendTest`, `generateTypeScriptClient`) return a structural no-op message:
  `"System Context: Current project has no frontend subsystem. Focus strictly on backend source."`

---

## 4. Cognitive Orchestration & Universal Personas

Cognitive capabilities are managed by `quarkus-langchain4j`. System prompts contain **zero hardcoded framework keywords or project names**. All domain rules and architectural invariants are injected dynamically via template variables.

```java
// Universal Role 1: Principal System Planner
@RegisterAiService(modelName = "planner")
public interface FullStackPlannerAiService {
    @SystemMessage("""
        You are a Principal Software Architect.
        Analyze specifications, DTOs, schemas, and UI components.
        Generate a phased implementation DAG adhering to the project's architectural guidelines:
        {rulesContext}
        """)
    String draftPlan(@UserMessage String userStory, @V("rulesContext") String rulesContext);
}

// Universal Role 2: Invariant Gatekeeper
@RegisterAiService(modelName = "architect-gate")
public interface UniversalArchitectGatekeeperAiService {
    @SystemMessage("""
        You are the Architectural Invariant Gatekeeper.
        Evaluate the proposed plan or diff against the active project architectural decisions:
        {retrievedAdrs}
        Return ONLY valid JSON matching the GateVerdict schema.
        """)
    GateVerdict evaluate(@UserMessage String targetContent, @V("retrievedAdrs") String retrievedAdrs);

    @RegisterForReflection
    record GateVerdict(
        boolean approved,
        String summary,
        List<String> violations,
        List<String> recommendations
    ) {}
}

// Universal Role 3: Implementation Synthesizer
@RegisterAiService(
    modelName = "coder",
    tools = { CodeWorkspaceTools.class, ExecutionToolProxy.class }
)
public interface FullStackCodeSynthesizerAiService {
    @SystemMessage("""
        You are the Implementation Engineer.
        1. Write failing tests first (TDD) for backend or frontend layers.
        2. Apply precision modifications using 'patchFile' or 'addImports'. Never rewrite entire files.
        3. Synchronize API contracts when backend interfaces change.
        4. Validate changes using targeted test tools.
        """)
    String executeImplementationStep(@UserMessage String instruction);
}
```

### Pure-Java Lexical Retrieval Engine (BM25 RAG)
To avoid the GraalVM native JNI traps inherent in C++ ONNX runtimes, the primary local search engine is implemented in pure Java:
* **Dynamic ADR Discovery**: Scans `docs/adr/`, `doc/architecture/decisions/`, `docs/tech/architecture/adrs.md`, `.agents/rules/`, and `ADR.md`.
* **Inverted Index (BM25)**: Parses headers, code tags, and rule summaries into an in-memory lexical index.
* **Selective Retrieval**: Queries the index using the task prompt and touched file paths, retrieving the **top 3–5 matching ADRs** with zero external network requests and zero native C-library dependencies.
* **Token Optimization**: Cuts Gatekeeper prompt sizes by over $70\%$, dropping context token payloads from $\sim 15,000$ to under $3,000$ tokens per turn.

### Deterministic Decision Caching (`quarkus-cache`)
Audits performed at `temperature: 0.0` are purely deterministic functions of the code diff and active rule digests:
$$K_{\text{cache}} = \text{SHA-256}(\text{UnifiedDiff} + \text{RetrievedAdrsDigest})$$
* Gatekeeper evaluations are annotated with `@CacheResult(cacheName = "architect-verdicts")` backed by Caffeine.
* Repeated evaluations of identical patches or comment-only edits return cached verdicts in **0 ms** without consuming remote LLM tokens.

### Normalized Loop Circuit Breaker
Detects oscillatory self-healing loops ($A \rightarrow B \rightarrow A \rightarrow B$):
* **`ErrorSignatureExtractor`**: Normalizes compiler and linter traces by stripping absolute paths, timestamps, line numbers, and column offsets.
* **Circuit Breaker Logic**:
  * *Consecutive Repeat ($A \rightarrow A$)*: Injects an explicit warning into the sliding scratchpad memory.
  * *Oscillating Regression ($A \rightarrow B \rightarrow A \rightarrow B$)*: Invokes `git reset --hard` to rollback to the snapshot and forces a strategy pivot.
  * *Exhaustion (3 failures after pivot)*: Trips the circuit breaker, rolls back all workspace edits, and halts with exit code 1.

---

## 5. Contract-Driven Full-Stack Synchronization

The contract synchronization engine bridges backend API models with frontend web clients, eliminating contract drift.

```yaml
agent:
  contract:
    enabled: true
    spec:
      strategy: quarkus # 'quarkus', 'plugin', 'live-url', 'custom'
      output-file: "target-agent/openapi/openapi.yaml"
    client:
      tool: openapi-generator # 'openapi-generator', 'orval', 'npm-script', 'custom'
      target-dir: "frontend/src/app/core/api/generated"
      generator: typescript-angular
      additional-properties:
        ngVersion: "18.0.0"
        standalone: "true"
        providedInRoot: "true"
        serviceSuffix: "Service"
        stringEnums: "true"
```

```
Backend Implementation (Quarkus REST/Panache)
                        │
                        ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 1. Backend Spec Extraction (ContractSyncTool)                          │
│    • Injects SmallRye flags during compilation                         │
│    • Multi-module reactor safe: -pl <app-module> -am                   │
│    • Emits target-agent/openapi/openapi.yaml                           │
└───────────────────────┬────────────────────────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 2. Client Code Generation                                              │
│    • Outputs strictly to isolated subfolder: .../api/generated/        │
│    • Preserves custom barrel exports, interceptors, and hand code      │
│    • Honors .openapi-generator-ignore                                  │
└───────────────────────┬────────────────────────────────────────────────┘
                        │
                        ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 3. Frontend TDD Loop (Signals & Standalone Components)                 │
│    • Converts RxJS Observables to Signals via toSignal()               │
│    • Validates with tsc --noEmit and targeted Jest/Vitest specs         │
└────────────────────────────────────────────────────────────────────────┘
```

### Safety & Blast Radius Governance
1. **Isolated Output Directory**: Generated code writes strictly to `.../core/api/generated/`. It never overwrites the parent directory, preserving manually authored HTTP interceptors, error bridges, or index files.
2. **Diff Noise Suppression**: If a codegen run updates file timestamps without changing schema definitions, the tool executes `git checkout --` on unmodified content, preventing Git diff pollution.

---

## 6. Isolation, Multi-Tenant State & Process Topology

To support uninterrupted developer workflows, the Dev-Agent decouples execution from the user's active workspace and prevents multi-process database deadlocks.

```
Main Workspace (Branch: main)             Isolated Worktree (Branch: feat/US-042)
┌───────────────────────────────┐         ┌──────────────────────────────────────┐
│ Developer edits in IDE        │         │ Background Worker (PID: 49120)       │
│                               │         │                                      │
│ Runs: dev-agent status        │         │ 1. Executes Full-Stack TDD Loop      │
│ Reads: state.json (0ms lock)  │ ◄────── │ 2. Emits state.json Heartbeat        │
│ No DB lock collision!         │         │ 3. Commits to feat/US-042            │
└───────────────────────────────┘         └──────────────────────────────────────┘
```

### Git Worktree Lifecycle Engine (`GitWorktreeService`)
* **Worktree Allocation**: Detached runs create an isolated working directory under `.git/dev-agent-worktrees/<task-id>/` checked out to branch `feat/<task-id>`.
* **Automatic Working-Tree Hydration**: If the developer has uncommitted changes in their IDE, the agent creates an ephemeral stash (`git stash create`) and applies it inside the newly allocated worktree, capturing in-progress thoughts without altering the primary workspace index.
* **Dependency Cache Sharing**: The agent uses global package manager caches (`~/.npm` via `--cache`) rather than directory symlinks, preventing permission crashes on Windows/WSL2 and eliminating mutation leaks into the host tree.

### Non-Locking Ephemeral State Sockets (`state.json`)
To avoid H2 MVStore database lock crashes (`Database may be already in use`) when running status queries while a background worker is compiling:
1. Active background workers write an atomic JSON heartbeat file into their worktree:
   `.git/dev-agent-worktrees/<task-id>/state.json` containing `taskId`, `status`, `phase`, `tokens`, `cost`, and `pid`.
2. CLI read commands (`dev-agent status`, `dev-agent logs`) read these JSON heartbeats directly without opening JDBC database connections.
3. Central H2 database writes (`~/.config/dev-agent/data/agent.mv.db`) occur strictly at major phase boundaries and task completion, fully namespaced by `project_id`.

### Concurrency & Host Resource Guardrails (`LocalWorkerLimiter`)
* **Worker Limit**: Caps simultaneous background worker JVMs at **2** (configurable via `agent.max-concurrent-workers`). Additional detached runs enter a `QUEUED` state.
* **Surefire Heap Slicing**: Inner Maven test forks are bounded with `-Xmx512m -XX:+TieredCompilation -XX:TieredStopAtLevel=1`, preventing workstation memory exhaustion.

### Context-Aware Secret Redaction (`SecretRedactionFilter`)
To prevent corrupting binary base64 fixtures, Content-Addressable Storage (CAS) digests, or test assertions (e.g., `assertEquals("Basic dXNlcjpwYXNz", header)`), secret scrubbing is strictly quarantined:
* **Never applied** to raw Java/TypeScript source code or test files.
* **Strictly applied** to tool outputs before writing to H2 `StepAuditEntity`, HTTP Authorization headers sent to remote APIs, and exported Markdown runbook metadata.

---

## 7. Declarative Configuration Cascade & User Overrides

Configuration resolves across five tiers, allowing zero-boilerplate defaults for standard repositories while supporting deep overrides for complex monorepos:

```
[1. Picocli CLI Flags]       (--offline, --max-retries=5, --yes, --gui, --dir)
         │
         ▼
[2. Environment Variables]   (GEMINI_API_KEY, AGENT_MAX_CONCURRENT_WORKERS)
         │
         ▼
[3. Project Configuration]   (.agents/dev-agent.yaml & .agents/config.json)
         │
         ▼
[4. User Global Scope]       (~/.config/dev-agent/settings.json)
         │
         ▼
[5. Classpath Defaults]      (Convention over Configuration)
```

### Complete Schema Specification (`.agents/dev-agent.yaml`)

```yaml
# ==============================================================================
# Quarkus Dev-Agent Declarative Configuration (.agents/dev-agent.yaml)
# Pre-populated via auto-detection on `dev-agent init`.
# ==============================================================================

agent:
  # 1. Project & Workspace Roots
  workspace:
    # backend-root: "."
    # frontend-root: "frontend"
    # active-backend-module: "repo-app"

  # 2. Build Tooling & Test Slicing
  build:
    backend:
      # executable: "./mvnw"
      # extra-args: ["-Pfast"]
      dev-services:
        enabled: false # Enforces in-memory/mock beans for fast inner TDD
      target-dir: "target-agent"
    frontend:
      # executable: "npm"
      # custom-test-command: "npm test -- --watch=false <specPath>"
      # custom-type-check-command: "npx tsc --noEmit"

  # 3. Contract Synchronization (OpenAPI -> TypeScript)
  contract:
    enabled: true
    spec:
      strategy: quarkus # 'quarkus', 'plugin', 'live-url', 'custom'
      output-file: "target-agent/openapi/openapi.yaml"
    client:
      tool: openapi-generator # 'openapi-generator', 'orval', 'npm-script', 'custom'
      target-dir: "frontend/src/app/core/api/generated"
      generator: typescript-angular
      additional-properties:
        ngVersion: "18.0.0"
        standalone: "true"
        providedInRoot: "true"
        serviceSuffix: "Service"
        stringEnums: "true"

  # 4. Cognitive Model Routing & Dynamic Prompts
  models:
    planner:
      provider: "gemini"
      model-name: "gemini-1.5-pro"
      temperature: 0.2
    gatekeeper:
      provider: "gemini"
      model-name: "gemini-1.5-flash"
      temperature: 0.0
    coder:
      provider: "anthropic"
      model-name: "claude-3-5-sonnet"
      temperature: 0.1
    fallback:
      provider: "ollama"
      endpoint: "http://localhost:11434"
      model-name: "qwen2.5-coder:7b"

  # 5. Knowledge Ingestion & ADR Lexical Retrieval
  knowledge:
    rules-paths:
      - ".agents/rules/**/*.md"
      - "docs/adr/*.md"
    retrieval:
      engine: "bm25" # Pure Java native-safe search
      max-adrs-per-prompt: 4
      min-relevance-score: 0.65

  # 6. Git Isolation & Worktree Placement
  isolation:
    use-worktrees: true
    branch-template: "feat/{task-id}-{story-slug}"
    auto-stash-uncommitted: true
    auto-prune-on-complete: true

  # 7. Remote Delivery & Quality Gates
  delivery:
    github:
      target-branch: "main"
      draft: true
      labels: ["dev-agent", "automated-pr"]
    ci:
      enabled: true
      timeout: "20m"
      poll-interval: "20s"
      job-filter-regex: "^(build|unit-tests|lint).*"
    sonar:
      enabled: false
      project-key: "my-org_my-project"
    coderabbit:
      enabled: true
      max-bot-turns: 2 # Prevents infinite bot-to-bot arguments

  # 8. Financial Ceilings & Loop Guardrails
  budget:
    max-task-cost-usd: 2.00
    max-task-tokens: 250000
    max-self-healing-retries: 3
    on-limit-exceeded: "rollback"

  # 9. Local Web Review GUI (--gui)
  gui:
    port: 9999
    bind-address: "127.0.0.1"
    auto-open-browser: true
    storybook:
      enabled: true
      url: "http://localhost:6006"
```

---

## 8. Remote Quality Gates & Automated Remediation

The remote delivery engine bridges verified local worktrees with GitHub Actions, SonarCloud, and CodeRabbit review bots.

```
Push Feature Branch ─────────────► Open GitHub Draft PR (draft: true)
                                                  │
                                                  ▼
Poll Remote CI & SonarCloud      ◄── Auto-Patch Quality Gate Issues
                                                  │
                                                  ▼
Promote PR to Ready for Review   ──► Ingest & Triage CodeRabbit Bot Comments
```

### 1. GitHub Draft PR Lifecycle & GraphQL Promotion
* Pushes the isolated feature branch to `origin/feature/<task-id>`.
* Opens a Pull Request in **Draft** state (`draft: true`) via declarative REST clients.
* When all remote CI check-runs pass and SonarCloud reports `OK`, promotes the PR to **"Ready for Review"** using a GraphQL mutation:
  ```graphql
  mutation MarkPrReady($pullRequestId: ID!) {
    markPullRequestReadyForReview(input: {pullRequestId: $pullRequestId}) {
      pullRequest { isDraft }
    }
  }
  ```

### 2. Autonomous CI & SonarCloud Remediation
* **Remote CI Failure Loop**: Downloads failing GitHub Actions check-run logs, passes them through `LogSanitizerService`, synthesizes fixes, applies patches, commits, and pushes up to a maximum of 3 remediation turns.
* **SonarCloud Auto-Patching**: If SonarCloud returns `ERROR`, queries `/api/issues/search`, extracts rule violations (e.g., `java:S1144`), applies local fixes, and pushes commits matching `fix(sonar): resolve <rule-id>`.

### 3. CodeRabbit Review Triage & GraphQL Thread Resolution
* **3-Way Action Classification**:
  1. `ACCEPT_AND_FIX`: Applies code modification, runs local tests, pushes a commit, and marks the thread resolved.
  2. `REBUT_WITH_ADR`: Formulates a technical rebuttal citing the project's specific ADR (e.g., defending a zero-copy buffer pattern required by performance invariants).
  3. `DISMISS`: Dismisses stylistic nitpicks or invalid recommendations with an explanation.
* **Outdated Diff Resynchronization**: Resolves comment threads via **GraphQL Node IDs** (`resolveReviewThread(input: {threadId: $threadId})`), preventing HTTP 422 errors caused by shifting line numbers after fix commits are pushed.
* **Bot Ping-Pong Breaker**: Caps bot-to-bot conversational replies at **2 turns**.
* **Human Developer Override**: If any reply in a thread originates from a non-bot user, automated triage immediately halts and defers unconditionally to the human developer.

---

## 9. Local Web Review GUI (`--gui`) & Storybook Preview

To simplify reviewing complex full-stack features, running with `--gui` starts an embedded Vert.x HTTP server on `http://localhost:9999` in under 20 ms.

```
CLI Execution: $ dev-agent feature "Add Auth Health Check" --gui
                               │
                               ▼
 1. Local TDD phases complete in isolated worktree
 2. WebReviewServer boots on http://localhost:9999 (< 20 ms)
 3. Launches default browser; awaits decision via CompletableFuture
                               │
                               ▼
 ┌────────────────────────────────────────────────────────┐
 │ Local Review Dashboard (http://localhost:9999)         │
 │  • Phased Implementation Plan DAG                      │
 │  • Side-by-Side Syntax Highlighted Diffs (diff2html)   │
 │  • Embedded Storybook Preview <iframe> (localhost:6006)│
 │  • Real-Time Spend ($) and Token Telemetry Tracker     │
 │  • [Approve Step]   [Request Changes]   [Abort Task]   │
 └─────────────────────────────┬──────────────────────────┘
                               │ User clicks [Approve Step]
                               ▼
 POST /api/review/decision { approved: true }
 Server releases CompletableFuture; shuts down; CLI proceeds to commit/push
```

* **Zero-CDN Native Assets**: CSS and JavaScript assets (`diff2html.min.js`, dark-first Carbon/Cyan themes) are bundled directly inside the native executable under `src/main/resources/web-ui/`.
* **Visual Component Verification**: Embeds an `<iframe>` pointing to the local Storybook development server (`http://localhost:6006`) so engineers can interact with newly generated Angular/React components before approving the patch.

---

## 10. Verification, ArchUnit Governance & Implementation Roadmap

### ArchUnit Native Compliance Rules
To prevent GraalVM native binary crashes caused by missing reflection registrations or improper CDI scopes:

```java
@AnalyzeClasses(packages = "org.quarkus.agent")
public class AgentArchitectureTest {

    @ArchTest
    public static final ArchRule toolsMustBeApplicationScoped =
        methods()
            .that().areAnnotatedWith(Tool.class)
            .should().beDeclaredInClassesThat().areAnnotatedWith(ApplicationScoped.class);

    @ArchTest
    public static final ArchRule recordsAndEntitiesMustRegisterReflection =
        classes()
            .that().resideInAnyPackage("..model..", "..entity..", "..remote..", "..gui..")
            .and().areNotInterfaces()
            .should().beAnnotatedWith(RegisterForReflection.class);
}
```

### Evolutionary Walking Skeleton Roadmap

| Cycle | Primary Milestone | Strategic Deliverables | Validation SLA |
| :--- | :--- | :--- | :--- |
| **Cycle 1** | **Standalone Full-Stack Walking Skeleton** | Standalone repo, Maven & npm build SPIs, workspace auto-detection, canonical path jail, dual log sanitizer, transactional Git rollback. | Days 1–5 |
| **Cycle 2** | **Multi-Tenant State & Memory Hardening** | Central H2 database (`~/.config/dev-agent/`), project namespacing, Markdown projection sync, 3-tier memory store, context-aware secret scrubbing. | Week 2 |
| **Cycle 3** | **Pure-Java Lexical RAG & Caching** | Dynamic ADR discovery, pure-Java BM25 search, Caffeine gatekeeper cache (`@CacheResult`), normalized error loop circuit breaker. | Week 3 |
| **Cycle 4** | **Async Isolation & Detached Workers** | Dedicated Git worktrees, build target slicing (`target-agent/`), non-locking `state.json` heartbeats, worker concurrency gate (max 2), desktop alerts. | Week 4 |
| **Cycle 5** | **Contract Sync & Remote Quality Gates** | OpenAPI $\rightarrow$ TypeScript generator, Draft PR lifecycle, remote CI self-healing, SonarCloud auto-patcher, CodeRabbit triage state machine. | Week 5 |
| **Cycle 6** | **Native Hardening, GUI Review & Release** | Sub-50ms GraalVM binary, offline Ollama failover, financial budget stops ($2.00 limit), embedded Vert.x Web Diff & Storybook review UI (`:9999`). | Week 6 |