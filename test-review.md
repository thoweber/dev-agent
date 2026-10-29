# Test Review Report: Cycles 1 & 2 Quality & Test Architecture Audit

**Author:** Test Manager (`test-manager`)  
**Scope:** Cycles 1 & 2 (`dev-agent` Hexagonal CLI, Git Worktree Isolation, Multi-Tenant Persistence, Cognitive Memory, Quality Gates)  
**Evaluation Date:** 2026-10-29  
**Status:** Working Tree Report (Untracked — Do Not Check In)

---

## 1. Executive Summary

During Cycles 1 and 2, the `dev-agent` test suite achieved strong foundational coverage (95.97% line coverage, 92.20% branch coverage across 200 passing tests) anchored by robust ArchUnit governance, strict security path jail validations, and deterministic memory and buffer invariants. However, the suite suffers from test ergonomics regressions, notably the invasive use of reflection to access private state in cognitive memory tests, heavy duplication in test fixture instantiation across integration tests, and a complete absence of automated unit tests for over 960 lines of mission-critical Python CI/review scripts. Establishing a standardized Test Data Builder pattern, enforcing black-box behavioral testing over reflection, and bridging Python script verification will solidify test reliability as the project scales into Cycle 3.

---

## 2. Test Strengths

*Ordered by their impact on system correctness and runtime safety:*

1. **Zero-Trust Filesystem Security & Path Traversal Jail Boundary Verification** ([`WorkspaceJailValidatorTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/WorkspaceJailValidatorTest.java), [`RepoPathTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/RepoPathTest.java))  
   *Correctness Impact: Maximum (Security Boundary).*  
   Provides exhaustive negative testing for path traversal jailbreak attacks (`../../etc/passwd`), symbolic link targets, symlinked ancestor directories, and protected `.git` directory mutations. Guarantees that write sinks strictly accept validated [`RepoPath`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/workspace/RepoPath.java) value objects, throwing fail-closed `SecurityException` upon any boundary breach.

2. **Architectural Guardrails via ArchUnit Invariant Governance** ([`AgentArchitectureTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/AgentArchitectureTest.java))  
   *Correctness Impact: Critical (Compile & Native Image Invariants).*  
   Automates enforcement of critical GraalVM Mandrel Ahead-of-Time (AOT) and Quarkus CDI conventions. Guarantees that all LangChain4j `@Tool` methods reside in `@ApplicationScoped` beans, all models/entities declare `@RegisterForReflection`, Panache entities remain strictly encapsulated within `org.quarkus.agent.entity`, and test methods never declare `@Transactional` to prevent post-commit deadlocks.

3. **Hierarchical Cognitive Scratchpad Integrity & Turn Pruning Invariants** ([`HierarchicalMemoryStoreTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cognitive/HierarchicalMemoryStoreTest.java))  
   *Correctness Impact: High (LLM Context & Decision Correctness).*  
   Verifies the 3-tier memory model: immutable Tier 1 invariants, Tier 2 task specifications, and Tier 3 sliding scratchpad. Tests atomic user/AI turn eviction, tool request and response co-eviction (preventing dangling tool execution result messages), and intra-turn tool budget trimming while strictly verifying turn structure invariants.

4. **Crash Recovery Candidate Queue Evaluation & Physical Worktree Presence** ([`CrashRecoveryDetectorIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/CrashRecoveryDetectorIT.java), [`CrashRecoveryDetectorTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/CrashRecoveryDetectorTest.java))  
   *Correctness Impact: High (Fault Recovery & State Consistency).*  
   Tests that task resumption verifies both host OS process liveness (`ProcessHandle.of(pid).isPresent()`) and physical filesystem existence of the dedicated Git worktree (`.git/dev-agent-worktrees/<taskId>`). Verifies exhaustive candidate searching across `findAllResumableTasks` rather than naively checking only the head of the candidate queue.

5. **Two-Tier Process Output Buffer Spilling & Truncation Invariants** ([`BoundedOverflowBufferTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/BoundedOverflowBufferTest.java))  
   *Correctness Impact: High (Process Stability & Memory Limits).*  
   Validates bounded 2-tier output buffering (1 MB RAM threshold spilling over to a 10 MB temporary disk file). Accurately tests memory preservation, disk file streaming via segregated input/output streams, capacity ceiling enforcement, single-byte boundary writes, and truncation flags under overflow conditions.

6. **Programmatic Transaction Boundaries & Cross-Tenant Data Isolation** ([`StatePersistenceIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/entity/StatePersistenceIT.java))  
   *Correctness Impact: Medium-High (Data Integrity).*  
   Integration tests execute isolated transactions using `QuarkusTransaction.requiringNew()`, preventing Hibernate dirty session leaks and race conditions. Validates multi-tenant task segregation where tasks bearing identical `taskId` across distinct `ProjectId` coordinates remain completely partitioned without cross-talk.

7. **Context-Aware Secret Redaction Quarantine & Strongly-Typed Sinks** ([`SecretRedactionFilterTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/SecretRedactionFilterTest.java), [`SanitizedOutputTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/SanitizedOutputTest.java))  
   *Correctness Impact: Medium-High (Data Leak Prevention).*  
   Exhaustively tests regex scrubbing across GitHub PATs, OpenAI keys, Gemini tokens, Bearer/Basic auth headers, private PEM blocks, JSON key-values, and shell variables. Guarantees that persistence sinks strictly consume the [`SanitizedOutput`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/workspace/SanitizedOutput.java) type.

8. **Asynchronous Subprocess Lifetime & Timeout Governance** ([`SubprocessExecutorTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/SubprocessExecutorTest.java))  
   *Correctness Impact: Medium (Resource Leak Prevention).*  
   Verifies that subprocess forks execute with explicit timeouts, forcibly killing stubborn process trees (`destroyForcibly()`) well before blocking callers. Validates thread interrupt propagation (`Thread.currentThread().interrupt()`), non-blocking virtual thread stream draining, and standard error capture on command failures.

9. **Dual State Synchronization & Markdown Projection Invariant** ([`StatePersistenceIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/entity/StatePersistenceIT.java), [`StateSyncServiceTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/StateSyncServiceTest.java))  
   *Correctness Impact: Medium (Audit Trail Consistency).*  
   Verifies atomic state projection between embedded H2 database records and human-readable worktree journals (`.agents/runs/<taskId>.md`). Tests Markdown checklist formatting, tool execution duration tracking, exit code status icons, and secret scrubbing within step audit logs.

10. **Interactive vs Non-Interactive CLI UX & Subcommand Routing** ([`ResumeCommandIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/ResumeCommandIT.java), [`ResumeCommandTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/ResumeCommandTest.java), [`DevAgentCommandTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/DevAgentCommandTest.java))  
    *Correctness Impact: Medium (User Experience & Automation).*  
    Simulates terminal interaction via injected `BufferedReader` instances to verify user acceptance prompts ('y'/'n'), out-of-bounds numerical input handling, interactive abort options ('q'), and fallback guidance in non-interactive CI environments.

---

## 3. Test Weaknesses

*Ordered by their impact on poor readability, insufficient boundary tests, or clumsiness:*

1. **Invasive Reflection to Mutate Internal State in Cognitive Tests** ([`HierarchicalMemoryStoreTest.java#L296-L300`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cognitive/HierarchicalMemoryStoreTest.java#L296-L300))  
   *Impact: Severe Clumsiness & Brittleness.*  
   Multiple tests in [`HierarchicalMemoryStoreTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cognitive/HierarchicalMemoryStoreTest.java) bypass the public API using `field.setAccessible(true)` to directly manipulate the private `slidingScratchpad` list. This white-box reflection tests artificially constructed internal states rather than observable behavior driven by `memory.add()`.

2. **Zero Automated Unit Test Coverage for Critical Python Automation Scripts** ([`scripts/check_pr_reviews.py`](file:///home/developer/projects/dev-agent/scripts/check_pr_reviews.py), [`scripts/parse_jacoco.py`](file:///home/developer/projects/dev-agent/scripts/parse_jacoco.py))  
   *Impact: High Risk & Insufficient Boundary Testing.*  
   Over 960 lines of mission-critical Python code governing GitHub PR review pagination, outside-diff markdown blockquote extraction, code-fence isolation, and JaCoCo quality gate thresholds have zero automated unit tests (`pytest` or `unittest`). During Cycle 2, multiple production regressions occurred due to edge-case markdown formatting that could have been caught by lightweight unit test fixtures.

3. **Heavy Mocking of Active Record Static Methods in Persistence Tests** ([`TaskPersistenceServiceTest.java#L140-L215`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/entity/TaskPersistenceServiceTest.java#L140-L215))  
   *Impact: Poor Readability & Coupling to Panache Internals.*  
   [`TaskPersistenceServiceTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/entity/TaskPersistenceServiceTest.java) uses Mockito's `mockStatic(TaskRunEntity.class)` across numerous tests. This tightly couples the unit tests to Panache Active Record static method signatures, creating verbose try-with-resources boilerplate and obscuring the real business logic under test.

4. **Repetitive Test Fixture Boilerplate & Lack of Test Data Builders** ([`ResumeCommandIT.java#L74-L83`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/ResumeCommandIT.java#L74-L83), [`CrashRecoveryDetectorIT.java#L57-L66`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/CrashRecoveryDetectorIT.java#L57-L66))  
   *Impact: Poor Readability & Code Bloat.*  
   Virtually every integration test method in [`ResumeCommandIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/ResumeCommandIT.java), [`CrashRecoveryDetectorIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/CrashRecoveryDetectorIT.java), and [`StatePersistenceIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/entity/StatePersistenceIT.java) manually instantiates identical `TaskRun.builder()` payloads (8–10 fields) and creates physical worktree directory structures manually, resulting in hundreds of lines of duplicate boilerplate.

5. **Complete Absence of Level 3 Native Binary Integration Tests (`*IT` with `@QuarkusIntegrationTest`)**  
   *Impact: Architectural Blind Spot.*  
   Despite the repository SLA of $<50\text{ ms}$ cold boot and the Mandrel AOT compilation target, all 23 integration tests run strictly in JVM mode using `@QuarkusTest`. There is not a single test annotated with `@QuarkusIntegrationTest` verifying that the compiled native image starts up and executes subcommands without GraalVM native reflection crashes.

6. **Subprocess Executor Edge Condition Gaps (Uncovered Failure Branches)** ([`SubprocessExecutor.java#L71-L73`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/workspace/SubprocessExecutor.java#L71-L73))  
   *Impact: Insufficient Boundary Testing.*  
   [`SubprocessExecutor`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/workspace/SubprocessExecutor.java) has an 81.2% branch coverage score. Missing test cases include: buffer allocation `IOException` handling, null message exception fallbacks, stdin stream close exceptions, and secondary thread interrupts during post-kill wait timeouts.

7. **Monolithic Multi-Subcommand Verification in `DevAgentCommandTest`** ([`DevAgentCommandTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/DevAgentCommandTest.java))  
   *Impact: Poor Test Cohesion & Maintenance Overhead.*  
   [`DevAgentCommandTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/DevAgentCommandTest.java) spans 337 lines and tests multiple distinct Picocli subcommands (`status`, `metrics`, `abort`, `plan`, `logs`, `resume`, `feature`) within a single class. Testing subcommands across a single file violates single responsibility and creates brittle test setups.

8. **Fragile Exact-String CLI Stdout Assertions** ([`ResumeCommandTest.java#L98`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/ResumeCommandTest.java#L98), [`DevAgentCommandTest.java#L38-L48`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/DevAgentCommandTest.java#L38-L48))  
   *Impact: Brittle Assertions.*  
   CLI tests heavily assert raw formatted string outputs (`contains("[dev-agent] Resuming task: TASK-102")`) instead of asserting structured outcome states or parsed models. Any minor cosmetic change to JAnsi badges or whitespace breaks multiple unrelated unit tests.

9. **Retrofitted Branch Coverage Test Artifacts (Testing Defensive Helpers Directly)** ([`HierarchicalMemoryStoreTest.java#L320-L370`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cognitive/HierarchicalMemoryStoreTest.java#L320-L370))  
   *Impact: Test Clumsiness.*  
   Following the implementation of the 90% branch coverage gate, internal helper methods (`canTrimOldestToolInteraction()`, `trimOldestToolInteraction()`, `findOldestTrimmableToolInteractionIndex()`) were exposed as package-private and given dedicated unit tests to satisfy coverage metrics, rather than proving these branches naturally via realistic conversational turns.

10. **Lack of Simulated Disk Full / I/O Fault Injection in Buffer Tests** ([`BoundedOverflowBufferTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/BoundedOverflowBufferTest.java))  
    *Impact: Insufficient Boundary Testing.*  
    While memory limits and max capacity truncations are tested, [`BoundedOverflowBufferTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/BoundedOverflowBufferTest.java) does not simulate disk I/O errors (e.g. read-only filesystem or out-of-disk-space `IOException`), leaving the cleanup and error resilience in `close()` unverified under fault conditions.

---

## 4. Test Concept to Be Established as an Agent Rule

### Concept: "Zero-Reflection Behavioral Verification & Canonical Fixture Ergonomics"

To ensure tests serve as resilient living documentation rather than brittle change inhibitors, the following test protocol shall be added to the repository guidelines:

#### Protocol Invariants:
1. **Strict Prohibition of Test Reflection on Production State:**  
   Unit and integration tests must strictly exercise components through public APIs and defined contracts. The use of `java.lang.reflect` (`setAccessible(true)`) to mutate private fields or inspect internal lists in production classes is strictly prohibited. If internal state cannot be established via public methods, it indicates an architectural gap (missing domain factory or unnecessary dead branches).
2. **Canonical Test Data Builders & Object Mother Pattern:**  
   Every domain aggregate and value object with 4 or more fields (e.g. [`TaskRun`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/model/TaskRun.java), [`StepAudit`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/model/StepAudit.java)) must be instantiated in tests using centralized test factories (e.g. `TaskRunMother`, `WorktreeTestFixture`) with standardized defaults (e.g. `TaskRunMother.crashed("TASK-001")`, `TaskRunMother.active("TASK-002", pid)`). Raw, inline builder boilerplate across test files is prohibited.
3. **Multi-Language Automation Script Parity:**  
   Any Python or shell script residing in `scripts/` that executes during local verification loops, git hooks, or CI quality gates must have a corresponding test suite under `tests/scripts/` (e.g., `test_check_pr_reviews.py`, `test_parse_jacoco.py`) executed via standard runners (`python3 -m unittest discover`). No script logic may be merged without unit verification.
4. **Pyramid Level 3 Native Verification Gate:**  
   Every integration test verifying subsystem workflows (`*IT.java`) that does not require mocking JVM internals must provide a companion native verification run via `@QuarkusIntegrationTest` in the `native` Maven profile to guarantee Mandrel AOT compatibility.

---

## 5. Actionable Plan to Tackle the 3 Most Urgent Weaknesses

### Urgent Weakness 1: Eliminate Reflection from Cognitive Memory Tests ([`HierarchicalMemoryStoreTest`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cognitive/HierarchicalMemoryStoreTest.java))
* **Objective:** Remove all `field.setAccessible(true)` calls in `HierarchicalMemoryStoreTest`, driving all eviction and trimming behaviors purely through the public `memory.add()` contract.
* **Concrete Steps:**
  1. Refactor test cases `testScratchpadExceedsBudgetCannotTrim`, `testCanTrimOldestToolInteractionBoundaries`, `testTrimOldestToolInteractionBoundaries`, and `testTurnStructureInvariants` to populate scratchpad messages using realistic sequences of [`UserMessage`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/cognitive/HierarchicalMemoryStore.java), [`AiMessage`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/cognitive/HierarchicalMemoryStore.java), and [`ToolExecutionResultMessage`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/cognitive/HierarchicalMemoryStore.java).
  2. If certain edge cases (such as malformed tool interactions) represent unreachable dead code when using `add()`, refactor the defensive checks inside [`HierarchicalMemoryStore`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/cognitive/HierarchicalMemoryStore.java) to fail fast on `add()` or remove redundant defensive branches.
  3. Verify with `./mvnw test -Dtest=HierarchicalMemoryStoreTest` and `./scripts/parse_jacoco.py --threshold 95.0 --fail-under-gates` to guarantee 100% branch and line coverage without reflection.

### Urgent Weakness 2: Introduce Automated Unit Test Suite for Python Scripts (`scripts/*.py`)
* **Objective:** Establish automated regression testing for [`check_pr_reviews.py`](file:///home/developer/projects/dev-agent/scripts/check_pr_reviews.py) and [`parse_jacoco.py`](file:///home/developer/projects/dev-agent/scripts/parse_jacoco.py) to prevent review thread and coverage parsing regressions.
* **Concrete Steps:**
  1. Create directory `tests/scripts/` with `test_check_pr_reviews.py` and `test_parse_jacoco.py`.
  2. In `test_check_pr_reviews.py`, author isolated unit tests for:
     - `extract_outside_diff_comments()` against multi-line code fences (```java ... ```), inline markdown backticks, horizontal rules (`---`), and alternate headings (`## Outside diff range comments`).
     - Pagination handling (`parse_link_header()` and `fetch_all_pull_request_comments()`).
     - Review thread resolution state checking (`find_unresolved_review_threads()`).
  3. In `test_parse_jacoco.py`, author unit tests verifying threshold gate evaluations, zero-division safeguards, and XML node traversal.
  4. Integrate the test execution into the verification loop via `./scripts/run_script_tests.sh` or `python3 -m unittest discover -s tests/scripts`.

### Urgent Weakness 3: Introduce `TaskRunMother` & `WorktreeTestFixture` to Eliminate Integration Test Boilerplate
* **Objective:** Drastically improve integration test readability and reduce test maintenance overhead across [`ResumeCommandIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/ResumeCommandIT.java), [`CrashRecoveryDetectorIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/CrashRecoveryDetectorIT.java), and [`StatePersistenceIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/entity/StatePersistenceIT.java).
* **Concrete Steps:**
  1. Create `src/test/java/org/quarkus/agent/testutil/TaskRunMother.java`:
     - Provide fluent factory methods: `TaskRunMother.crashed(String taskId, TaskStatus status)`, `TaskRunMother.active(String taskId, long pid)`, `TaskRunMother.completed(String taskId)`.
     - Automatically populate mandatory timestamps, phase names, retry counts, and default project coordinates.
  2. Create `src/test/java/org/quarkus/agent/testutil/WorktreeTestFixture.java`:
     - Provide managed creation and cleanup methods: `createWorktree(Path repoRoot, String taskId)` and `deleteWorktree(...)`.
  3. Refactor [`ResumeCommandIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/ResumeCommandIT.java) and [`CrashRecoveryDetectorIT`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/CrashRecoveryDetectorIT.java) to adopt `TaskRunMother` and `WorktreeTestFixture`, reducing lines of code by over 50% and improving test readability.
  4. Run `./mvnw spotless:apply && ./mvnw test -Dtest=*IT` to confirm full regression safety.

---

## 6. Actionable Plan for Weaknesses 4 through 10

### Weakness 4: Repetitive Test Fixture Boilerplate & Object Mother Expansion
* **Status:** Complete (Phase 1 Commit `b938119`; Phase 2 `StepAuditMother`, `TaskRunMother` expansion, and full test suite migration complete).
* **Objective:** Expand the Object Mother pattern to all remaining domain models and test suites to completely eliminate inline builder boilerplate.
* **Concrete Steps:**
  1. Author [`StepAuditMother.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/testutil/StepAuditMother.java) in `org.quarkus.agent.testutil`:
     - Provide canonical factory methods: `StepAuditMother.toolExecution(int stepIndex, String toolName, String prompt, String output, int exitCode)`, `StepAuditMother.phaseTransition(int stepIndex, String fromPhase, String toPhase)`, and `StepAuditMother.builder()`.
     - Automatically populate mandatory timestamps, UUID execution IDs, and default durations.
  2. Refactor [`StatePersistenceIT.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/entity/StatePersistenceIT.java), [`StateSyncServiceTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/StateSyncServiceTest.java), and [`TaskPersistenceServiceTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/entity/TaskPersistenceServiceTest.java) to adopt [`TaskRunMother`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/testutil/TaskRunMother.java) and [`StepAuditMother`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/testutil/StepAuditMother.java).
* **Verification:** Run `./mvnw spotless:apply && ./mvnw test -Dtest=StatePersistenceIT,StateSyncServiceTest,TaskPersistenceServiceTest`.

### Weakness 5: Level 3 Native Binary Integration Testing (`@QuarkusIntegrationTest`)
* **Objective:** Bridge the Level 3 testing gap by introducing automated native CLI executable tests verifying cold boot SLA ($<50\text{ ms}$) and GraalVM Mandrel Ahead-of-Time (AOT) reflection safety.
* **Concrete Steps:**
  1. Create [`NativeDevAgentIT.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/NativeDevAgentIT.java) annotated with `@QuarkusIntegrationTest`.
  2. Implement tests exercising the compiled native binary:
     - `testNativeColdStartSla()`: Measures process fork-to-exit latency for `dev-agent --version`, asserting wall-clock duration $<50\text{ ms}$.
     - `testNativeCommandHelpAndDispatch()`: Asserts CLI help flag output and subcommand dispatching (`status`, `metrics`, `resume`).
     - `testNativeReflectionSafety()`: Verifies that domain records (`TaskRun`, `StepAudit`, `ProjectId`) and Panache entities serialize and deserialize cleanly without Mandrel native image missing-reflection runtime crashes.
  3. Integrate into CI native profile: `./mvnw verify -Pnative -Dtest=NativeDevAgentIT`.
* **Verification:** Execute `./mvnw verify -Pnative` in the local Mandrel environment or CI pipeline.

### Weakness 6: Subprocess Executor Failure Boundary Verification (Edge Branch Coverage)
* **Status:** Complete (Branch coverage elevated from 81.2% to 100.0% via black-box public API testing, without synthetic seams or encapsulation leaks).
* **Objective:** Elevate [`SubprocessExecutor`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/workspace/SubprocessExecutor.java) branch coverage from 81.2% to $\ge 90.0\%$ by simulating edge failure conditions.
* **Concrete Steps:**
  1. In [`SubprocessExecutorTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/SubprocessExecutorTest.java), author tests for:
     - **Stdin Stream Fault:** Inject write failure on stdin pipe when feeding multi-megabyte payloads to an abruptly terminated child process.
     - **Null Error Message Fallback:** Verify null-safe formatting in exception construction when a failing process produces empty or null standard error streams.
     - **Secondary Thread Interruption:** Simulate a thread interrupt during the post-kill wait timeout (`destroyForcibly()`), asserting that `Thread.currentThread().isInterrupted()` is restored and preserved.
     - **Buffer Allocation Boundary:** Verify graceful handling if [`BoundedOverflowBuffer`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/workspace/BoundedOverflowBuffer.java) cannot allocate temporary disk spillover files.
* **Verification:** Run `./mvnw spotless:apply && ./mvnw test -Dtest=SubprocessExecutorTest && ./scripts/parse_jacoco.py --threshold 90.0`.

### Weakness 7: Decomposition of Monolithic `DevAgentCommandTest`
* **Status:** Complete (Decomposed 337-line monolithic test into dedicated 1-to-1 test classes: `StatusCommandTest`, `MetricsCommandTest`, `AbortCommandTest`, `LogsCommandTest`, `PlanCommandTest`, and enhanced `FeatureCommandTest`; trimmed `DevAgentCommandTest` to focused root routing).
* **Objective:** Decompose the 337-line monolithic [`DevAgentCommandTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/DevAgentCommandTest.java) into cohesive, single-responsibility subcommand test classes under `org.quarkus.agent.cli`.
* **Concrete Steps:**
  1. Author dedicated test classes per Picocli subcommand:
     - [`StatusCommandTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/StatusCommandTest.java): Tests `status` formatting, `--json` flag output, `--all`, `--help`, and directory option.
     - [`MetricsCommandTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/MetricsCommandTest.java): Tests token consumption and spend summary, `--json`, `--help`, and directory option.
     - [`AbortCommandTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/AbortCommandTest.java): Tests valid taskId termination message, missing/blank taskId validation, `--help`, and directory option.
     - [`LogsCommandTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/LogsCommandTest.java): Tests streaming logs, `--follow` option, missing/blank taskId validation, `--help`, and directory option.
     - [`PlanCommandTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/PlanCommandTest.java): Tests phased plan generation, `--yes` / `--offline` flags, missing/blank story validation, `--help`, and directory option.
     - [`FeatureCommandTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/FeatureCommandTest.java): Tests feature execution, flags parsing, instruction validation, and `--help`.
  2. Trim [`DevAgentCommandTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/DevAgentCommandTest.java) to strictly test root CLI routing, `--version`, `--help`, invalid command error handling, and parent `--dir` option propagation.
* **Verification:** Run `./mvnw spotless:apply && ./mvnw test -Dtest=*CommandTest,DevAgentCommandTest`.

### Weakness 8: Robust Semantic CLI Output Assertions
* **Status:** Complete (Implemented `CliOutputAssert` with ANSI code stripping and fluent semantic matchers; verified via `CliOutputAssertTest`; migrated `ResumeCommandTest`, `ResumeCommandIT`, and `DevAgentCommandTest`).
* **Objective:** Eliminate brittle substring assertions on raw terminal stdout in [`ResumeCommandTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cli/ResumeCommandTest.java) and [`DevAgentCommandTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/DevAgentCommandTest.java).
* **Concrete Steps:**
  1. Create [`CliOutputAssert.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/testutil/CliOutputAssert.java) in `org.quarkus.agent.testutil`:
     - Implement regex-based ANSI escape code stripping (`\u001B\[[0-9;?]*[a-zA-Z]`).
     - Provide fluent AssertJ custom assertions: `assertThatCli(output).hasExitCode(0).containsAnsiFree(text).hasTableEntry(taskId, phase)`.
     - Author companion unit test [`CliOutputAssertTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/testutil/CliOutputAssertTest.java) (11 tests).
  2. Refactor CLI tests to assert against ANSI-sanitized text and exit codes rather than fragile JAnsi color codes and exact terminal whitespace.
* **Verification:** Run `./mvnw spotless:apply && ./mvnw test -Dtest=ResumeCommandTest,ResumeCommandIT,DevAgentCommandTest,CliOutputAssertTest`.

### Weakness 9: Encapsulate Defensive Trimming Helpers & Drive via Realistic Conversation Turns
* **Objective:** Re-encapsulate package-private trimming helpers in [`HierarchicalMemoryStore`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/cognitive/HierarchicalMemoryStore.java) to `private`, driving all eviction and trimming paths naturally through `memory.add()`.
* **Concrete Steps:**
  1. Revert `canTrimOldestToolInteraction()`, `trimOldestToolInteraction()`, and `findOldestTrimmableToolInteractionIndex()` visibility from package-private to `private`.
  2. Remove direct unit test invocations of these private helpers in [`HierarchicalMemoryStoreTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/cognitive/HierarchicalMemoryStoreTest.java).
  3. Replace with realistic conversational test scenarios:
     - Multi-turn conversation exceeding token budget where the oldest turn contains a tool execution result message.
     - Multi-turn conversation where tool execution result is already at minimum threshold.
     - Complex interaction sequences where AI message requests multiple concurrent tool calls.
  4. Verify that 100% line and 100% branch coverage for [`HierarchicalMemoryStore`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/cognitive/HierarchicalMemoryStore.java) is retained without violating encapsulation.
* **Verification:** Run `./mvnw spotless:apply && ./mvnw test -Dtest=HierarchicalMemoryStoreTest && ./scripts/parse_jacoco.py --threshold 95.0`.

### Weakness 10: Fault Injection & I/O Error Resilience in `BoundedOverflowBuffer`
* **Objective:** Validate error handling, safe stream termination, and idempotence in [`BoundedOverflowBuffer`](file:///home/developer/projects/dev-agent/src/main/java/org/quarkus/agent/workspace/BoundedOverflowBuffer.java) under disk full or I/O failure conditions.
* **Concrete Steps:**
  1. In [`BoundedOverflowBufferTest.java`](file:///home/developer/projects/dev-agent/src/test/java/org/quarkus/agent/workspace/BoundedOverflowBufferTest.java), introduce simulated filesystem fault tests:
     - **Disk Full Simulation:** Use a test-controlled temporary directory or spy/subclass to inject an `IOException("No space left on device")` on the 10 MB disk overflow spillover boundary.
     - **Resource Cleanup Guarantee:** Assert that when an `IOException` is thrown during writing, the buffer immediately cleans up and deletes any temporary `.overflow` files created on disk.
     - **Idempotent Close Resilience:** Assert that calling `close()` multiple times or calling `close()` following a disk write error does not throw unhandled exceptions and guarantees no file descriptor leaks.
* **Verification:** Run `./mvnw spotless:apply && ./mvnw test -Dtest=BoundedOverflowBufferTest && ./scripts/parse_jacoco.py --threshold 95.0`.

