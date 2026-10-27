# Retrospective Learnings: Cycle 1 & Cycle 2 Remediation Analysis

> **Analysis Scope:** Systematic evaluation of all CodeRabbit reviews, SonarCloud Quality Gate findings, and CI build anomalies across PR #1 (Cycle 1 Refinement) and PR #2 (State Engine & Memory Architecture).

---

## 1. Top 10 Most Common Mistakes (Frequency & Presence)

| Rank | Mistake Category | Description & Typical Pattern | Detection Source |
|:---:|:---|:---|:---|
| **C1** | **Field Injection Anti-Pattern** | Using `@Inject FieldType field;` instead of constructor injection (`java:S6813`), inhibiting unit testing without container reflection. | SonarCloud, CodeRabbit |
| **C2** | **Java 25 Unnamed Patterns (`_`)** | Catching exceptions without utilizing unnamed patterns `catch (IOException _)` (`java:S7467`), or lambda params `(_, _) -> ...`. | SonarCloud |
| **C3** | **Exception Swallowing & Thread Interruption** | Catching `InterruptedException` without calling `Thread.currentThread().interrupt()` (`java:S2142`), or swallowing exceptions without logging (`java:S1166`). | SonarCloud |
| **C4** | **Regex Class Duplication & Character Escaping** | Duplicating characters in character classes like `[a-zA-Z0-9_-]` where `_` is redundant with `\w` (`java:S6353`, `java:S5869`). | SonarCloud |
| **C5** | **Constant Definition & Modifiers** | Defining class-level immutable constants as instance fields instead of `private static final` (`java:S1170`). | SonarCloud |
| **C6** | **H2 Test Database Isolation & Foreign Key Cascades** | Calling `TaskRunEntity.deleteAll()` in test setup without first purging child `StepAuditEntity` rows, causing referential integrity violations (`23503-240`). | Fast Maven Tests |
| **C7** | **Lexical Path Validation vs. Real Path Containment** | Relying on `path.normalize().startsWith(dir)` without verifying symlinks or physical canonical paths via `toRealPath()`. | CodeRabbit |
| **C8** | **Dynamic Bytecode Coverage Masking in Quarkus** | Standard `jacoco-maven-plugin` reporting 0% coverage on Panache entities loaded by `QuarkusClassLoader` unless `quarkus-jacoco` is integrated. | CI Pipeline / SonarCloud |
| **C9** | **Test Transaction Scope Leakage (`@Transactional` on `@Test`)** | Annotating test methods with `@Transactional`, which prevents transaction commitment before method return and breaks deferred listeners (e.g. JTA synchronization hooks). | CI Pipeline |
| **C10** | **Overly Restrictive Regex Quantifiers** | Imposing arbitrary length minimums like `{8,}` on key-value credential parsers, leaving shorter valid secrets (e.g., `password=hunter2`) exposed. | CodeRabbit |

---

## 2. Top 10 Most Severe Mistakes (Security, Data Integrity & System Stability)

| Rank | Severity Issue | Impact & Risk Analysis | Security / Architecture Vulnerability |
|:---:|:---|:---|:---|
| **S1** | **Symlink Traversal Path Jailbreak (CWE-59)** | Writing task journals (`.agents/runs/<taskId>.md`) without rejecting symbolic links allowed an attacker to overwrite arbitrary files writable by the user process outside the repo root. | High (Security) |
| **S2** | **Selective Credential Quarantine Failure (CWE-312)** | Scrubbing secrets in Markdown exports while persisting un-scrubbed tool outputs directly to the `step_audits` SQL database table, violating Directive 7. | High (Data Privacy) |
| **S3** | **Short Credential Scrubbing Bypass (CWE-312)** | Using `{8,}` in key-value regex patterns enabled short credentials (`password=hunter2`, `password=admin`) to bypass sanitization entirely. | High (Data Privacy) |
| **S4** | **LLM Turn Eviction & Prompt Invariant Poisoning** | Allowing incoming `SystemMessage` via `add()` to overwrite pinned project invariants, and evicting tool requests without/before results, provoking LLM API 400 Bad Request errors. | High (Functional Correctness) |
| **S5** | **Premature Uncommitted State Disk Projection** | Projecting execution state to Markdown on disk before database transactions committed, leading to permanent disk/DB divergence on transaction rollbacks. | High (Data Integrity) |
| **S6** | **Multi-Tenant Project Identity Collision** | Stripping the Git remote host (`github.com` vs `gitlab.com` vs enterprise) caused unrelated repositories with identical org/repo names to share database states in the embedded H2 instance. | Medium (Data Isolation) |
| **S7** | **Process Recovery Blind Spot** | Querying only the latest task (`firstResult()`) in `CrashRecoveryDetector` caused an active running process to completely mask older crashed tasks that required resumption. | Medium (Availability) |
| **S8** | **OS Process Handle Leakage** | Running external processes (such as `git config --get remote.origin.url`) without calling `process.destroyForcibly()` upon timeout, causing lingering zombie OS processes. | Medium (Resource Leak) |
| **S9** | **Bytecode Double-Instrumentation Conflicts** | Running `jacoco-maven-plugin:prepare-agent` concurrently with `quarkus-jacoco` without `<exclClassLoaders>*QuarkusClassLoader</exclClassLoaders>`, risking runtime test crashes. | Medium (Build Stability) |
| **S10** | **Test Transaction Deadlocks on Deferred Projections** | Running tests with `@Transactional` while the production service relies on JTA post-commit synchronization, causing integration tests to assert against unprojected files. | Medium (Test Reliability) |

---

## 3. Prioritized Action Matrix (Weighing Severity & Presence)

The composite priority score ($W = 2 \times \text{Severity} + 1 \times \text{Presence}$, scale 1-10) yields the following execution order:

| Priority | Task ID | Core Problem & Remediation Target | Impact Area | Status | Proposed Remediation Vehicle |
|:---:|:---:|:---|:---|:---:|:---|
| **P1** | **REM-01** | **Physical Symlink & Canonical Jail Enforcement** | Security (CWE-59) | **COMPLETED** | Strongly-typed `RepoPath` value object & canonical jail checks (commit `5cd1641`) |
| **P2** | **REM-02** | **Universal Credential Scrubbing Quarantine** | Security (CWE-312) | **COMPLETED** | Strongly-typed `SanitizedOutput` domain value object for sinks (commit `db18aed`) |
| **P3** | **REM-03** | **Regex Sanitization Bounds & Length Flexibility** | Security (CWE-312) | **COMPLETED** | Bounded regexes, word boundaries, quoted space handling (commit `d281d77`) |
| **P4** | **REM-04** | **3-Tier Hierarchical Memory Turn Invariants** | Cognitive / LLM Stability | **COMPLETED** | Atomic turn eviction, Tier 1 system consolidation, ADR-011 (commit `20c4912`) |
| **P5** | **REM-05** | **Panache Entity Encapsulation & Domain Decoupling** | Data Integrity & Architecture | **COMPLETED** | Panache entity isolation, domain models `TaskRun`/`StepAudit`, `TaskPersistenceService`, ADR-012 (commit `f423941`) |
| **P6** | **REM-06** | **Global Project ID Host Isolation & Deterministic UUIDv3** | Multi-Tenancy Isolation | **COMPLETED** | Protocol normalization, credential scrubbing, `ProjectId` domain value object, ADR-013 |
| **P7** | **REM-07** | **Exhaustive Crash Recovery & Worktree Health Inspection** | High Availability | **COMPLETED** | Complete queue evaluation `findAllResumableTasks`, physical worktree check, interactive `ResumeCommand` |
| **P8** | **REM-08** | **Subprocess Lifetime & Timeout Governance** | System Resource Hygiene | **COMPLETED** | Centralized `SubprocessExecutor`, bounded 2-tier `BoundedOverflowBuffer` (1 MB RAM -> 10 MB Disk), mandatory `destroyForcibly()` |
| **P9** | **REM-09** | **Quarkus & JaCoCo Dual-Instrumentation Build Setup** | Build & CI Infrastructure | **COMPLETED** | Failsafe isolation for `*IT.java` with `quarkus.jacoco.enabled=false`, Surefire pure unit test coverage via `jacoco-maven-plugin` |
| **P10** | **REM-10** | **Test Transaction Scope Protocol (`QuarkusTransaction` vs `@Transactional`)** | Test Framework Strategy | Pending | Update `test-engineer` skill and `AGENTS.md` testing protocol to avoid `@Transactional` on test methods with event projection. |

---

## 4. Execution Step 1: REM-01 Ready for Discussion

* **Mistake Focus:** Physical Filesystem Escape via Symlink Traversal (S1 / C7).
* **Suggested Remediation:**
  1. Update `AGENTS.md` Directive 4: Explicitly mandate checking `Files.isSymbolicLink()` and validating `.toRealPath()` on both parent directories and target files before write operations.
  2. Enhance `WorkspaceJailValidator`: Add `validateTargetFileForWrite(Path target, Path jailRoot)` that checks symlink status and verifies real canonical containment prior to any `Files.write*` or `Files.newByteChannel`.
  3. Author an ADR (ADR-0006: Physical Filesystem Jail & Symlink Defense) documenting this invariant.
