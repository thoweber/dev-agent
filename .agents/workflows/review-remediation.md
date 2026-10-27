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
3. **GitHub PR Review Threads & CodeRabbit Audit:**
   ```bash
   ./scripts/check_pr_reviews.py
   ./scripts/check_pr_reviews.py --details
   ```
4. **GitHub PR Review Progress Status:**
   ```bash
   ./scripts/check_pr_reviews.py --check-status
   ```

---

## Step 2: Review Comment Classification & Multi-Agent Safety Guardrails

### Autonomous Safety Invariants
* **Human-Override Precedence**: Human developer instructions and review comments take absolute precedence over bot feedback. If a bot recommendation conflicts with a human requirement or architectural guideline, always adhere to the human requirement.
* **Bot-Turn Detection & Loop Breaker**: Limit automated responses to 1 reply per comment thread. Never enter iterative bot-to-bot conversational ping-pong loops.

### 3-Way Comment Classification
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
2. **Coverage Enforcement**: Add 1-to-1 boundary tests for uncovered branches and accessors until JaCoCo line coverage reaches $\ge 80\%$ and condition/branch coverage reaches $\ge 80\%$.
3. **Terminal Output Sanitization**: Route CLI output through `spec.commandLine().getOut()` and annotate CLI classes with `@SuppressWarnings("java:S106")`.

---

## Step 4: Local Verification Gate

Before pushing any commit, verify that all local checks pass in accordance with `AGENTS.md` Rule 2:

```bash
# 1. Fast Unit Tests (< 10s):
./mvnw test -Dtest=*Test

# 2. ArchUnit Native Compliance & Path Jail Verification (< 15s):
./mvnw test -Dtest=AgentArchitectureTest,WorkspaceJailValidatorTest

# 3. JaCoCo Coverage Audit (>= 80% instruction threshold) (< 2s):
./scripts/parse_jacoco.py --threshold 80.0 --fail-under 80.0

# 4. Full Clean Build & Verification (< 30s):
./mvnw clean verify

# 5. Auto-Format Code:
./mvnw spotless:apply
```

---

## Step 5: Remote Delivery, Quality Gate Polling & Review Completion Detection

1. Stage and commit with conventional messages: `fix(cli): ...`, `test(cli): ...`, `docs(adr): ...`.
2. Push to the active feature branch:
   ```bash
   git push origin feature/<TASK-ID>-<slug>
   ```
3. Reply to existing CodeRabbit comments on GitHub and resolve threads:
   ```bash
   ./scripts/check_pr_reviews.py --thread <THREAD_ID> --reply "Resolved. <explanation>" --resolve
   ```
4. Bounded SonarCloud Quality Gate polling:
   ```bash
   # Poll SonarCloud with max 5 attempts (30s sleep) and fail-fast error detection:
   for i in {1..5}; do
     sleep 30
     STATUS=$(curl -s "https://sonarcloud.io/api/qualitygates/project_status?projectKey=thoweber_dev-agent&pullRequest=<PR_ID>" | jq -r '.projectStatus.status')
     echo "SonarCloud status (attempt $i/5): $STATUS"
     if [ "$STATUS" = "OK" ]; then
       echo "Quality Gate PASSED!"
       break
     elif [ "$STATUS" = "ERROR" ] || [ "$STATUS" = "WARN" ]; then
       echo "Quality Gate FAILED with status: $STATUS"
       exit 1
     fi
   done
   ```
5. Bounded CodeRabbit Review Completion Polling:
   After pushing a new commit, CodeRabbit automatically triggers a background review on GitHub. To determine when CodeRabbit has finished its review, use one of the two canonical detection methods:

   **Method A: Commit Status API (Recommended)**  
   CodeRabbit registers a commit status check on `<HEAD_SHA>` with context `"CodeRabbit"`.
   * **In-Progress**: `state: "pending"`, `description: "Review in progress"`
   * **Completed**: `state: "success"`, `description: "Review completed"`

   ```bash
   HEAD_SHA=$(git rev-parse HEAD)
   echo "Polling CodeRabbit commit status on ${HEAD_SHA:0:7}..."
   for i in {1..10}; do
     STATUS_JSON=$(gh api "repos/{owner}/{repo}/commits/${HEAD_SHA}/statuses" \
       --jq '[.[] | select(.context == "CodeRabbit")][0]')
     STATE=$(echo "$STATUS_JSON" | jq -r '.state // empty')
     DESC=$(echo "$STATUS_JSON" | jq -r '.description // empty')

     echo "CodeRabbit status (attempt $i/10): state=${STATE:-pending}, desc='${DESC:-Review in progress}'"

     if [ "$STATE" = "success" ]; then
       echo "CodeRabbit review completed successfully!"
       break
     fi
     sleep 30
   done
   ```

   **Method B: Top-Level PR Comment Marker Inspection**  
   CodeRabbit maintains a top-level summary comment in `/issues/<PR_ID>/comments`.
   * **In-Progress**: The comment body contains `<!-- This is an auto-generated comment: review in progress by coderabbit.ai -->` and `Currently processing new changes in this PR`.
   * **Completed**: The in-progress marker is removed and replaced with `<!-- recent_review_start -->` and the review summary.

   ```bash
   IN_PROGRESS=$(gh api "repos/{owner}/{repo}/issues/<PR_ID>/comments" \
     --jq '[.[] | select(.user.login == "coderabbitai[bot]")][-1].body' | grep -c "review in progress" || true)

   if [ "$IN_PROGRESS" -eq 0 ]; then
     echo "CodeRabbit has finished processing the PR summary comment."
   fi
   ```

6. Autonomous Loop Termination or Re-Ingestion:  
   Once CodeRabbit's review completes:
   * Query `gh api "repos/{owner}/{repo}/pulls/<PR_ID>/comments"` to inspect for any new review comments on the latest commit.
   * If new actionable comments exist, re-enter **Step 2 (Classification)**.
   * If no new actionable comments exist (or the recent review section notes *"No actionable comments were generated in the recent review"*), the remediation workflow terminates with **100% Quality Gate & Review Approval**.
