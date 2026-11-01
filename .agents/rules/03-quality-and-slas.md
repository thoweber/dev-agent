# dev-agent Quality Goals & SLA Enforcement

> All numerical SLA thresholds are configured strictly in `.agents/config.json` under `qualityGoalsAndSLA`.

## 1. Boot Timing & Resource Guardrails
* **Sub-50ms Native Cold Boot:** The GraalVM native binary (`dev-agent`) MUST achieve $<50\text{ ms}$ cold start latency when executing `--help` or `status`.
* **JVM Cold Boot:** Development JVM runs must initialize in $<800\text{ ms}$.
* **Surefire Memory Limits:** Inner Maven test forks are bounded with `-Xmx512m -XX:+TieredCompilation -XX:TieredStopAtLevel=1` to prevent workstation memory exhaustion.
* **Worker Concurrency Limit:** Bounded to a maximum of 2 concurrent JVM background worker processes (`maxConcurrentWorkers = 2`). Additional tasks enter a `QUEUED` state.

## 2. Normalized Loop Circuit Breaker & Budget Guardrails
* **Consecutive Repeat Warning ($A \rightarrow A$):** Injects a warning into memory when identical errors repeat.
* **Oscillating Regression ($A \rightarrow B \rightarrow A \rightarrow B$):** Automatically triggers `git reset --hard` to rollback workspace modifications and forces a strategy pivot.
* **3-Retry Trip:** Halts execution and trips the circuit breaker if self-healing fails 3 times after a strategy pivot.
* **Budget Ceilings:** Automatically stops and rolls back changes if task spend exceeds $\$2.00$ or $250,000$ tokens.

## 3. Remote CI & SonarCloud Waiting Strategy
* **Initial Wait Threshold:** When waiting for GitHub Actions workflow runs or SonarCloud analysis after a push or trigger, assume an initial wait time of 2 minutes (120 seconds) before checking status for the first time.
* **Status Polling Interval (`gh run view`):** Recheck status only after waiting at least 45 seconds between subsequent status polls.

## 4. Code Coverage Quality Gates & Spotless Protocol
* **Line Coverage Gate:** Repository overall line coverage MUST satisfy $\ge 95.0\%$.
* **Condition / Branch Coverage Gate:** Repository overall branch/condition coverage MUST satisfy $\ge 90.0\%$.
* **Gate Enforcement Command:** Verified via `./scripts/parse_jacoco.py --fail-under-gates`.
* **Spotless-First Invariant:** Whenever writing or modifying code, run `./mvnw spotless:apply` before executing any other Maven goal.

## 5. Autonomous PR Merge Prohibition Invariant
* **Strict Human Authority:** Autonomous agents, subagents, and scripts must NEVER execute `gh pr merge`, fast-forward `main`, or merge pull request branches into `main` under any circumstances.
* **Hand-Off Protocol:** When all quality gates, tests, and PR reviews are completely green, the agent must yield execution to the human maintainer by submitting a clear hand-off report and requesting manual review and merge.
