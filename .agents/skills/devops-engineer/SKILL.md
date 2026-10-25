---
name: devops-engineer
description: Manages GraalVM Mandrel native compilation, GitHub Actions CI pipelines, multi-platform releases, and CLI installation packaging for dev-agent.
---

# DevOps & Release Engineering Skill (`devops-engineer`)

This skill governs native compilation, CI automation, artifact packaging, and installation scripting for **dev-agent**.

---

## 1. GraalVM Native Compilation

* **Build Command:**  
  `./mvnw clean package -Dnative -Dquarkus.native.container-build=false`
* **Performance Target:**  
  Binary size $<60\text{ MB}$, cold boot $<50\text{ ms}$, idle memory $<40\text{ MB}$.
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
  4. Build and verify: `./mvnw clean verify`.
  5. Upload JaCoCo test coverage report.
