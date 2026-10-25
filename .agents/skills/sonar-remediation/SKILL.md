---
name: sonar-remediation
description: Audits SonarCloud issues, enforces Quality Gate standards, and applies targeted auto-patches for dev-agent.
---

# SonarCloud Remediation Skill (`sonar-remediation`)

This skill governs static analysis, security hotspot analysis, and Quality Gate remediation.

---

## 1. Quality Gate Standards

* **0 Critical / Blocker Issues:** Zero vulnerabilities or security hotspots.
* **Coverage:** Maintain $\ge 80\%$ line and condition coverage.
* **Duplication:** Keep code duplication $<3\%$.

---

## 2. CI & SonarCloud Polling Strategy

* **Initial Wait Threshold:** Wait 2 minutes (120 seconds) after push before checking initial SonarCloud status.
* **Status Polling Interval:** Wait at least 45 seconds between subsequent status polls.
