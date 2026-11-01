---
name: sonar-remediation
description: Audits SonarCloud issues, enforces Quality Gate standards, and applies targeted auto-patches for dev-agent.
---

# SonarCloud Remediation Skill (`sonar-remediation`)

This skill governs static analysis, security hotspot analysis, and Quality Gate remediation. All testing and quality gates must align with [05-testing-standards.md](file:///home/developer/projects/dev-agent/.agents/rules/05-testing-standards.md) and [03-quality-and-slas.md](file:///home/developer/projects/dev-agent/.agents/rules/03-quality-and-slas.md).

---

## 1. Quality Gate Standards

* **0 Critical / Blocker Issues:** Zero vulnerabilities or security hotspots.
* **Coverage Quality Gates:** Maintain Line Coverage $\ge 95.0\%$ and Condition/Branch Coverage $\ge 90.0\%$, verified locally via `./scripts/parse_jacoco.py --threshold 95.0 --fail-under-gates`.
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
