---
name: test-manager
description: Governs test strategy, L1 unit, L2 integration, and L3 native CLI test planning, and TDD protocol enforcement for dev-agent.
---

# Test Manager & Quality Strategy Skill (`test-manager`)

This skill governs test tiering, testing pyramid compliance, and Test-Driven Development (TDD) execution strategy across **dev-agent**.

---

## 1. Testing Pyramid & Tiering

* **Level 1 (Unit & In-Memory):** Target fast unit tests ($<10\text{ s}$) using AssertJ and Mockito. Inner loop feedback for CLI commands, validators, and SPIs.
* **Level 2 (Integration & ArchUnit):** Architecture verification via `AgentArchitectureTest`, filesystem sandbox tests via `WorkspaceJailValidatorTest`, and in-memory H2 repository tests.
* **Level 3 (Native Executable & CLI Sub-Process):** Verifies the compiled GraalVM native binary (`~/.local/bin/dev-agent`) against test projects. SLA: $<50\text{ ms}$ cold start.

---

## 2. Mandatory TDD Verification

* Never commit production code without a pre-existing failing test (`RED`).
* Ensure code reaches `GREEN` with minimal viable changes.
* Complete `REFACTOR` with `./mvnw spotless:apply` and coverage checks.
