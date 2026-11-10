---
name: sonar-remediation
description: Audits SonarCloud issues, enforces Quality Gate standards, and applies targeted auto-patches for dev-agent.
---

# SonarCloud Remediation Skill (`sonar-remediation`)

This skill governs static analysis, security hotspot analysis, and Quality Gate remediation. All testing and quality gates must align with [05-testing-standards.md](../../rules/05-testing-standards.md) and [03-quality-and-slas.md](../../rules/03-quality-and-slas.md).

---

## 1. Quality Gate Standards

* **0 Critical / Blocker Issues:** Zero vulnerabilities or security hotspots.
* **Coverage Quality Gates & Local JaCoCo Audit:** Maintain Line Coverage $\ge 95.0\%$ and Condition/Branch Coverage $\ge 90.0\%$, verified locally via `./scripts/parse_jacoco.py --fail-under-gates`. Prior to pushing, analyze modified classes using `./scripts/parse_jacoco.py --class <ClassName>` to eliminate uncovered branches.
* **Duplication:** Keep code duplication $<3\%$.
* **Zero Reflection:** Remediation tests must adhere to black-box behavioral verification without `field.setAccessible(true)`.

---

## 2. CI & SonarCloud Polling Strategy

* **Initial Wait Threshold:** Wait 2 minutes (120 seconds) after push before checking initial SonarCloud status.
* **Status Polling Interval:** Wait at least 45 seconds between subsequent status polls.

---

## 3. Review Remediation & Audit Tooling

* **PR Review Thread Audit:** `./scripts/check_pr_reviews.py`
* **Detailed Finding Inspection:** `./scripts/check_pr_reviews.py --details`
* **Resolve Thread with Reply:** `./scripts/check_pr_reviews.py --thread <THREAD_ID> --reply "Resolved..." --resolve`
* **SonarCloud Quality Gate Audit:** `./scripts/check_sonarcloud.py --pr <PR_NUMBER>`

---

## 4. Standard Tool Chaining & Anti-Ad-Hoc Scripting Invariant

* **Prefer Standard Tools:** Agents MUST prefer standard tool chaining (`gh` with `jq`, `curl | jq`) or existing repository scripts in `scripts/`.
* **Inspect Review Thread Comments:**
  ```bash
  gh api graphql -f query='query { node(id: "<THREAD_ID>") { ... on PullRequestReviewThread { comments(first: 10) { nodes { body } } } } }' --jq '.data.node.comments.nodes[].body'
  ```
* **Inspect SonarCloud Status via Standard Tools:**
  ```bash
  curl -s "https://sonarcloud.io/api/qualitygates/project_status?projectKey=thoweber_dev-agent&pullRequest=<PR>" | jq '.projectStatus.status'
  ```
* **Prohibition of Ad-Hoc Scripts:** Executing ad-hoc `python3 -c "..."` commands or creating scratch scripts to query APIs or parse JSON is strictly prohibited. If a script is needed, develop it in `scripts/` with tests in `tests/scripts/`.
