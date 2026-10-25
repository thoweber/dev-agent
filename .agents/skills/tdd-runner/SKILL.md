---
name: tdd-runner
description: Orchestrates the fast local Red-Green-Refactor execution loop using Maven Surefire memory limits and target-agent output slicing for dev-agent.
---

# TDD Runner Skill (`tdd-runner`)

This skill coordinates the fast local inner execution feedback loop during autonomous feature development.

---

## 1. Fast Local Execution Loop

* **Compile & Test Target:**  
  `./mvnw test -Dtest=*Test`
* **Target Build Isolation:**  
  Pass `-Dproject.build.directory=target-agent` to ensure agent test runs never contaminate standard target directories.
* **Surefire Memory Bounding:**  
  `-DargLine="-Xmx512m -XX:+TieredCompilation -XX:TieredStopAtLevel=1"`

---

## 2. Self-Correction Cycle

1. **Failure Diagnosis:** Extract error messages using `SanitizedLog`.
2. **Precision Modification:** Apply unified diff patches or targeted edits. Never rewrite whole files.
3. **Re-Test:** Execute targeted test method: `./mvnw test -Dtest=TargetClassTest#testMethod`.
4. **Formatting:** Execute `./mvnw spotless:apply`.
