---
name: devops-engineer
description: Manages GraalVM Mandrel native compilation, GitHub Actions CI pipelines, multi-platform releases, and CLI installation packaging for dev-agent.
---

# DevOps & Release Engineering Skill (`devops-engineer`)

This skill governs native compilation, CI automation, artifact packaging, and installation scripting for **dev-agent**. All test lifecycle and quality gate enforcement must adhere to [05-testing-standards.md](file:///home/developer/projects/dev-agent/.agents/rules/05-testing-standards.md) and [03-quality-and-slas.md](file:///home/developer/projects/dev-agent/.agents/rules/03-quality-and-slas.md).

---

## 1. GraalVM Native Compilation

* **Build Command:**  
  `./mvnw clean package -Dnative -Dquarkus.native.container-build=true`
* **Performance Target:**  
  Binary size $<60\text{ MB}$, cold boot $<50\text{ ms}$, idle memory $<40\text{ MB}$.
* **Level 3 Native Verification:**  
  Verify native binary cold start and subcommand execution via `@QuarkusMainIntegrationTest` (`NativeDevAgentIT`) in the `native` Maven profile.
* **Global Installation Path:**  
  Installs binary to `~/.local/bin/dev-agent`.

---

## 2. GitHub Actions CI Pipeline

* Workflows under `.github/workflows/ci.yml`.
* Runs on `ubuntu-latest` with Java 25 LTS (Temurin).
* Steps:
  1. Checkout with full history.
  2. Setup JDK 25.
  3. Validate code formatting: `./mvnw spotless:check`.
  4. Run Python automation unit tests: `python3 -m unittest discover -s tests/scripts`.
  5. Build and verify: `./mvnw clean verify`.
  6. Enforce coverage quality gates: `./scripts/parse_jacoco.py --threshold 95.0 --fail-under-gates`.
  7. Upload JaCoCo test coverage report.

---

## 3. Two-Phase Test Execution & JaCoCo Isolation

To guarantee honest test coverage metrics and prevent the "happy-path coverage illusion":
* **L1 Unit Tests (`*Test.java` via Surefire):**
  - Execute fast POJO tests without container overhead ($<3\text{ s}$).
  - Standard `jacoco-maven-plugin:prepare-agent` instruments unit test bytecode and exports coverage to `target/site/jacoco/jacoco.xml`.
  - SonarCloud Quality Gate strictly consumes this pure unit test report.
* **L2 Integration Tests (`*IT.java` via Failsafe):**
  - Execute container-dependent tests (`@QuarkusTest`) during `integration-test` / `verify` phases.
  - Failsafe explicitly disables Quarkus JaCoCo via `<quarkus.jacoco.enabled>false</quarkus.jacoco.enabled>` to prevent bytecode conflicts, coverage inflation, and 10-second agent shutdown timeouts.
