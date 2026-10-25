# ADR-002: Two-Axis Build & Framework SPI Extensibility

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
A generalized engineering agent must work across diverse full-stack ecosystems. Hardcoding Maven flags or Quarkus paths directly into orchestrators or AI services creates tight coupling and makes supporting Gradle, pnpm, Spring Boot, or React impossible without risky rewrites.

## Decision
Decouple execution capabilities into two orthogonal Service Provider Interface (SPI) axes:
1. **Axis 1 (Build Tooling Execution):** `BackendBuildSystem` and `FrontendBuildSystem` SPIs.
2. **Axis 2 (Framework Conventions & Idioms):** `BackendFrameworkConvention` and `FrontendFrameworkConvention` SPIs.

## Consequences
### Positive
* Perfect adherence to Open-Closed Principle (OCP): new tools/frameworks added as isolated CDI beans.
* Active initial implementations: `MavenBuildSystem`, `NpmBuildSystem`, `QuarkusConvention`, `AngularConvention`.
* Zero domain or framework coupling in CLI commands or LLM personas.

### Negative
* Additional interface abstractions required for command synthesis and log sanitization.

## Non-Negotiable Invariants
* Zero hardcoded build commands or framework paths in core orchestrator services.
