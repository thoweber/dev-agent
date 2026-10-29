# dev-agent: Repository Agent Guidelines & Autonomous Guardrails

> **Notice for Antigravity CLI Agent (`agy`):**
> This file defines non-negotiable repository guardrails, local feedback verification loops, and the mandatory Test-Driven Development (TDD) execution protocol for autonomous multi-file engineering in `dev-agent`.

---

## 1. Tech Stack & Runtime Baseline

* **JDK Runtime:** Java 25 LTS (Records, Sealed Interfaces, Pattern Matching, Virtual Threads)
* **Core Framework:** Quarkus 3.40.x (`3.40.1`) + Picocli (`quarkus-picocli`)
* **Cognitive Orchestration:** Quarkus-LangChain4j 1.14.x (`quarkus-langchain4j-core`)
* **State Management:** Embedded H2 (`~/.config/dev-agent/data/agent.mv.db`) + Non-locking Ephemeral Sockets (`state.json`)
* **Architecture Style:** Hexagonal CLI Engine with Two-Axis SPI Extensibility (Build Tooling & Framework Conventions)
* **Compilation Target:** Ahead-of-Time (AOT) GraalVM Mandrel Native Binary (`~/.local/bin/dev-agent`) with $<50\text{ ms}$ cold boot

---

## 2. Local Feedback Loops & Verification Commands

Remote CI/CD runs are too slow for autonomous iteration. Execute these fast local verification commands inside `agy`'s loop to self-correct in under 30 seconds:

> [!IMPORTANT]
> **Spotless-First Invariant:** Whenever adding or modifying code, run `./mvnw spotless:apply` BEFORE running any other Maven goals (such as `./mvnw test` or `./mvnw clean verify`). Otherwise, the build is extremely likely to fail due to formatting violations.

* **Auto-Format Code (Always Run First):**  
  `./mvnw spotless:apply`
* **Compile & Fast Unit Tests ($< 10\text{ s}$):**  
  `./mvnw test -Dtest=*Test`
* **ArchUnit Native Compliance & Path Jail Verification ($< 15\text{ s}$):**  
  `./mvnw test -Dtest=AgentArchitectureTest,WorkspaceJailValidatorTest`
* **Audit JaCoCo Coverage & Enforce Quality Gates ($< 2\text{ s}$):**  
  `./scripts/parse_jacoco.py --threshold 95.0 --fail-under-gates`
* **Audit & Ingest PR Review Threads & CodeRabbit ($< 2\text{ s}$):**  
  `./scripts/check_pr_reviews.py --check-status`
* **Run Python Script Unit Tests ($< 2\text{ s}$):**  
  `python3 -m unittest discover -s tests/scripts`
* **Full Clean Build & Verification ($< 30\text{ s}$):**  
  `./mvnw clean verify`
* **SonarCloud Quality Gate Analysis:**  
  `./mvnw sonar:sonar -Dsonar.qualitygate.wait=true`

---

## 3. Mandatory Development Protocol (Strict TDD)

All feature development, SPI additions, and engine enhancements MUST follow the 3-phase TDD loop:

```text
  ┌───────────────┐        ┌───────────────┐        ┌───────────────┐
  │   1. RED      │ ─────► │   2. GREEN    │ ─────► │  3. REFACTOR  │
  │ Write Failing │        │ Minimal Prod  │        │ Apply Clean   │
  │ Test (*Test)  │        │ Code to Pass  │        │ Format & Audit│
  └───────────────┘        └───────────────┘        └───────────────┘
```

1. **RED Phase:** Write unit or component tests (`*Test.java`) using AssertJ assertions (`assertThat()`). Run `./mvnw spotless:apply` then `./mvnw test` to confirm the test fails with an expected assertion failure or compile error.
2. **GREEN Phase:** Write minimal production code. Run `./mvnw spotless:apply` then `./mvnw test` until it passes cleanly.
3. **REFACTOR Phase:** Run `./mvnw spotless:apply`, check coverage via `./scripts/parse_jacoco.py --fail-under-gates`, and perform static analysis / Sonar clean-up. Never declare success with unformatted code or open quality regressions.

---

## 4. Autonomous Agent Directives

1. **Engine vs. Knowledge Separation:** The CLI engine contains **zero hardcoded project domain rules, framework keywords, or fixed documentation paths**. The binary is a zero-knowledge execution engine; target projects provide declarative guidelines in `.agents/rules/` and architecture decision records in `docs/adr/`.
2. **Two-Axis Open-Closed Principle (OCP):** Build tooling (`BackendBuildSystem`, `FrontendBuildSystem`) and framework idioms (`BackendFrameworkConvention`, `FrontendFrameworkConvention`) are decoupled into independent SPIs. Adding Gradle, pnpm, Spring Boot, or React requires implementing isolated SPI beans with zero modifications to orchestrator, database, worktree, or CLI commands.
3. **Workspace Concurrency & Physical Isolation:** Background execution runs strictly inside dedicated Git worktrees (`.git/dev-agent-worktrees/<task-id>/`) with build output slicing (`target-agent/`). The developer's primary working tree (`user.dir`) remains unlocked, untouched, and unpolluted.
4. **Canonical Path Jail & Strongly-Typed `RepoPath`:** All path inputs MUST resolve strictly within the active execution root (`activeWorkingRoot.toRealPath()`) via `WorkspaceJailValidator` and be modeled through the strongly-typed `RepoPath` value object. File write sinks must strictly consume `RepoPath`. Symbolic links targeting or originating within repository paths, as well as jailbreak attempts or modifications to `.git/**` or `~/.config/dev-agent/**`, must immediately throw `SecurityException`.
5. **Zero-JNI Native Safety:** Local semantic rule discovery relies on a pure-Java lexical BM25 inverted index, eliminating C++ native library JNI bindings that cause native image compilation and execution failures across operating systems.
6. **Non-Locking Ephemeral State Sockets:** Active background tasks emit atomic `state.json` heartbeats within their worktrees. CLI read commands (`status`, `logs`) read these JSON heartbeats directly without opening JDBC database connections to prevent H2 database lock crashes.
7. **Context-Aware Secret Redaction Quarantine & `SanitizedOutput`:** Secret scrubbing is strictly quarantined: never applied to raw Java/TypeScript source code or test files, but strictly applied to tool outputs before writing to audit entities and remote APIs. Persistence sinks and audit entities (`StepAuditEntity`) must consume the strongly-typed `SanitizedOutput` value object to guarantee credential isolation at compile time.
8. **Mandatory Builder Pattern for Complex Records:** All Java `record` classes or domain value objects with 4 or more fields MUST provide a fluent builder (`@Builder(toBuilder = true)`). Instantiations across production and test code MUST use `Record.builder()...build()` or `record.toBuilder()`.
9. **ArchUnit Native Compliance:** All tools MUST be `@ApplicationScoped` and all records/models participating in reflection or serialization MUST be annotated with `@RegisterForReflection`.
10. **Mandatory Feature Branch Naming Pattern:** Work on dedicated Git feature branches following the exact pattern `feature/TASK-XXX-short-description`.
11. **Panache Entity Encapsulation & Domain Decoupling (ADR-012):** Panache entities (`@Entity`) are strictly quarantined to `org.quarkus.agent.entity` and must never be accessed by classes outside their package. Services outside `entity` consume immutable domain models (`TaskRun`, `StepAudit`) mapped through `TaskPersistenceService`. Enforced via ArchUnit.
12. **Global Project ID Host Isolation & Deterministic UUIDv3 (ADR-013):** Multi-tenant task execution and persistence MUST identify repositories via strongly-typed `ProjectId` value objects wrapping deterministic RFC 4122 UUIDv3 instances derived from canonical, credential-scrubbed repository coordinates (`host/namespace/repo` or `local:<path>`). Raw remote URLs or unvalidated strings must never be used directly as project identifiers.
13. **Exhaustive Crash Recovery & Physical Worktree Verification:** Background task recovery (`CrashRecoveryDetector`) MUST evaluate complete active candidate queues (`findAllResumableTasks`) rather than only inspecting the queue head. Tasks are resumable strictly if their status is non-terminal, registered PID is dead on the host OS, and their Git worktree (`.git/dev-agent-worktrees/<taskId>`) physically exists on disk.
14. **Subprocess Lifetime & Timeout Governance:** All external process forks (`git`, `mvnw`, `npm`, etc.) MUST be executed via `SubprocessExecutor` with explicit timeouts. Subprocesses must use non-blocking stream draining with 2-tier bounded output buffering (`BoundedOverflowBuffer`: 1 MB RAM -> 10 MB Disk overflow) to prevent OS pipe deadlocks. On timeout or thread interruption, `destroyForcibly()` MUST be called immediately, and thread interrupt status MUST be preserved.
15. **Programmatic Test Transaction Scope Protocol:** Test and setup methods (`@Test`, `@BeforeEach`, `@AfterEach`) must NEVER be annotated with `@Transactional`. Production services manage their own transaction boundaries. When integration tests require explicit transaction isolation or data seeding, they MUST use programmatic transactions via `QuarkusTransaction.requiringNew().run(...)` or `.call(...)`. This prevents uncommitted Hibernate session caching and post-commit synchronization deadlocks. Enforced via ArchUnit.
16. **Spotless-First Execution Protocol:** Whenever writing, patching, or refactoring Java source or test files, execute `./mvnw spotless:apply` before running test suites or compiler checks to eliminate Spotless formatting failures in fast iteration loops.
17. **Mandatory Quality Gates Enforcement:** Overall repository code coverage MUST strictly satisfy Line Coverage $\ge 95.0\%$ and Condition/Branch Coverage $\ge 90.0\%$, verified via `./scripts/parse_jacoco.py --fail-under-gates`.
18. **Autonomous Merge Prohibition Invariant (Maintainer Authority):** Under NO circumstances may an autonomous agent execute `gh pr merge`, fast-forward `main`, or use administrative bypass flags (`--admin`) to merge a Pull Request. Merging is strictly reserved for the human maintainer (USER). The agent's workflow terminates with full CI green verification, Quality Gate compliance (Line >= 95%, Condition >= 90%), and resolved PR review threads. Once achieved, the agent must submit a hand-off summary report inviting the USER to review and merge the PR.
19. **Test Architecture & Zero-Reflection Behavioral Standards (`05-testing-standards.md`):** All testing MUST adhere to `.agents/rules/05-testing-standards.md`. Tests must practice black-box behavioral verification; using reflection (`field.setAccessible(true)`) to mutate or inspect private state in tests is strictly prohibited. Complex records (4+ fields) must be instantiated in tests using centralized test factories (`TaskRunMother`, `StepAuditMother`, `WorktreeTestFixture`) rather than repetitive inline builder plumbing. Python scripts in `scripts/` must have automated unit tests under `tests/scripts/`. L3 Native CLI tests must verify the Mandrel binary cold start SLA ($<50\text{ ms}$).
