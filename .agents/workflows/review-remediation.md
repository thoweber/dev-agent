# Workflow: Autonomous SonarCloud & CodeRabbit Remediation Loop

This workflow defines the standardized 5-step loop for autonomous agents to ingest, triage, patch, and verify SonarCloud Quality Gate issues and CodeRabbit AI review comments.

---

## Step 1: Quality Gate & Issue Ingestion

Execute the following commands to inspect remote quality gate status and active findings:

1. **SonarCloud Quality Gate Status:**
   ```bash
   curl -s "https://sonarcloud.io/api/qualitygates/project_status?projectKey=thoweber_dev-agent&pullRequest=<PR_ID>"
   ```
2. **SonarCloud Active Issues:**
   ```bash
   curl -s "https://sonarcloud.io/api/issues/search?projectKeys=thoweber_dev-agent&pullRequest=<PR_ID>&resolved=false"
   ```
3. **GitHub PR Review Comments:**
   ```bash
   gh api "repos/{owner}/{repo}/pulls/<PR_ID>/comments"
   ```

---

## Step 2: 3-Way Review Comment Classification

Triage every CodeRabbit comment into one of three action states:

1. **`ACCEPT_AND_FIX`**:
   * Valid bugs, missed edge cases, security vulnerabilities, or documentation bugs.
   * Action: Apply minimal precision code patch via TDD, run local tests, commit, push, and resolve thread.
2. **`REBUT_WITH_ADR`**:
   * The reviewer suggests a pattern explicitly forbidden by project ADRs (e.g. raw directory symlinks, blocking Vert.x event loops, or slow Testcontainers in micro-TDD).
   * The comment flags stubbed code that is explicitly scheduled for future evolutionary cycles in `ARCHITECTURE.md`.
   * Action: Post an architectural rebuttal citing the specific ADR or Roadmap Phase and resolve thread.
3. **`DISMISS`**:
   * Hallucinations, subjective formatting preferences, or invalid recommendations.
   * Action: Post an explanation and dismiss the finding.

---

## Step 3: Targeted TDD Remediation Loop

1. **Duplication Reduction First**: Replace repetitive tests with `@ParameterizedTest` (`java:S5976`) to keep line duplication $<3\%$.
2. **Coverage Enforcement**: Add 1-to-1 boundary tests for uncovered branches and accessors until JaCoCo line coverage reaches $\ge 80\%$.
3. **Terminal Output Sanitization**: Route CLI output through `spec.commandLine().getOut()` and annotate CLI classes with `@SuppressWarnings("java:S106")`.

---

## Step 4: Local Verification Gate

Before pushing any commit, verify that all local checks pass:

```bash
# 1. Compile & Fast Unit Tests:
./mvnw clean test

# 2. ArchUnit Native Compliance & Path Jail:
./mvnw test -Dtest=AgentArchitectureTest,WorkspaceJailValidatorTest

# 3. Format Verification:
./mvnw spotless:apply
```

---

## Step 5: Remote Delivery & Thread Resolution

1. Stage and commit with conventional messages: `fix(cli): ...`, `test(cli): ...`, `docs(adr): ...`.
2. Push to the active feature branch:
   ```bash
   git push origin feature/<TASK-ID>-<slug>
   ```
3. Reply to CodeRabbit comments on GitHub and resolve threads.
4. Wait 120 seconds, then poll SonarCloud Quality Gate until `status: OK`.
