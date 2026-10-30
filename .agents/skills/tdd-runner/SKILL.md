---
name: tdd-runner
description: Orchestrates the fast local Red-Green-Refactor execution loop using Maven Surefire memory limits and target-agent output slicing for dev-agent.
---

# TDD Runner Skill (`tdd-runner`)

This skill coordinates the fast local inner execution feedback loop during autonomous feature development. All test practices must adhere to [05-testing-standards.md](file:///home/developer/projects/dev-agent/.agents/rules/05-testing-standards.md).

---

## 1. Fast Local Execution Loop

* **Spotless-First Invariant:**  
  Execute `./mvnw spotless:apply` BEFORE running compiler or test goals.
* **Compile & Test Target ($<10\text{ s}$):**  
  `./mvnw test -Dtest=*Test`
* **Target Build Isolation:**  
  Pass `-Dproject.build.directory=target-agent` to ensure agent test runs never contaminate standard target directories.
* **Surefire Memory Bounding:**  
  `-DargLine="-Xmx512m -XX:+TieredCompilation -XX:TieredStopAtLevel=1"`
* **Coverage Verification & Quality Gates:**  
  `./scripts/parse_jacoco.py --threshold 95.0 --fail-under-gates` (Line $\ge 95\%$, Branch $\ge 90\%$)
* **Python Script Unit Tests:**  
  `python3 -m unittest discover -s tests/scripts`
* **PR Review Thread Audit:**  
  `./scripts/check_pr_reviews.py --check-status`

---

## 2. Self-Correction Cycle

1. **Spotless-First:** Run `./mvnw spotless:apply` whenever touching code.
2. **Failure Diagnosis:** Extract error messages using `SanitizedLog`.
3. **Zero-Reflection & Anti-Synthetic-Seams Policy:** Verify observable behaviors through public APIs; never use reflection (`field.setAccessible(true)`), weaken private encapsulation, or introduce artificial hooks to manipulate private state in tests.
4. **Canonical Fixtures:** Use `TaskRunMother`, `StepAuditMother`, and `WorktreeTestFixture` instead of repeating inline builder code.
5. **Precision Modification:** Apply unified diff patches or targeted edits. Never rewrite whole files.
6. **Re-Test:** Execute targeted test method: `./mvnw test -Dtest=TargetClassTest#testMethod`.
