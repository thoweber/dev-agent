---
name: test-engineer
description: Authors 1-to-1 unit tests, ArchUnit native compliance rules, Picocli command tests, and boundary assertions using AssertJ for dev-agent.
---

# Test Engineer Skill (`test-engineer`)

This skill governs test engineering, ArchUnit architecture rule enforcement, Picocli CLI integration tests, and AssertJ test standards in **dev-agent**.

---

## 1. 1-to-1 Test Mapping Standard

* Every production class MUST have its own dedicated 1-to-1 unit test class (`<TargetClassName>Test.java`).
* All `@Test` methods must use `@DisplayName("Given [precondition] - when [action/trigger] - then [expected outcome]")`.
* Use AssertJ (`assertThat()`, `assertThatThrownBy()`) exclusively. JUnit assertions are strictly forbidden.

---

## 2. ArchUnit Native Compliance Rules

All tests in `AgentArchitectureTest` verify native GraalVM safety:
1. All methods annotated with `@Tool` must be declared in classes annotated with `@ApplicationScoped`.
2. All non-interface classes in packages `..model..`, `..entity..`, `..remote..`, and `..gui..` must be annotated with `@RegisterForReflection`.

---

## 3. Coverage SLA

* Every class and module MUST maintain AT LEAST 80% line coverage and 80% branch/condition coverage across JaCoCo reports.
