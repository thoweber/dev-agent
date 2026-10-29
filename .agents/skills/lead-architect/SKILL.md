---
name: lead-architect
description: Enforces Hexagonal CLI architecture, Two-Axis SPI extensibility, zero-knowledge engine separation, GraalVM AOT native reflection compliance, and ArchUnit invariants for dev-agent.
---

# Lead Architect & Design Principles Skill (`lead-architect`)

This skill governs the high-level architecture, Hexagonal boundaries, Two-Axis SPI extensibility, SOLID design principles, and native GraalVM Mandrel compliance across **dev-agent**. All design and testing must align with [01-architecture-invariants.md](file:///home/developer/projects/dev-agent/.agents/rules/01-architecture-invariants.md) and [05-testing-standards.md](file:///home/developer/projects/dev-agent/.agents/rules/05-testing-standards.md).

> [!IMPORTANT]
> **Mandatory Architecture Governance:** The `lead-architect` skill **MUST** be activated during planning, feature design, SPI definition, and refactorings to ensure alignment with the foundational architectural invariants of `dev-agent`.

---

## 1. Core Architectural Invariants

1. **Engine vs. Knowledge Separation:** The CLI engine binary is a zero-knowledge execution orchestrator. Hardcoding target domain paths, framework syntax, or repository conventions inside core classes is strictly forbidden.
2. **Two-Axis Open-Closed Principle (OCP):**
   * **Axis 1 (Build Tooling):** `BackendBuildSystem` and `FrontendBuildSystem` SPIs decouple build tools (Maven, npm, Gradle, pnpm).
   * **Axis 2 (Framework Idioms):** `BackendFrameworkConvention` and `FrontendFrameworkConvention` SPIs decouple framework rules (Quarkus, Angular, Spring Boot, React).
3. **Canonical Path Sandboxing:** All filesystem operations must pass through `WorkspaceJailValidator.validateAndResolve()`. Directory traversal outside `activeWorkingRoot` throws `SecurityException`.
4. **Zero-JNI Native Safety:** Semantic discovery relies on pure-Java lexical BM25 indexing. Avoid C++ native library JNI bindings that break GraalVM native binary portability.
5. **Non-Locking State Sockets:** Background tasks emit atomic `state.json` heartbeats in their worktree to prevent H2 file lock deadlocks.
6. **3-Tier Testing Pyramid Governance:** L1 fast unit tests ($<10\text{ s}$), L2 programmatic integration & ArchUnit ($<15\text{ s}$), L3 Mandrel native binary verification ($<50\text{ ms}$ cold start).

---

## 2. ArchUnit Native Compliance Rules

Every architectural modification must satisfy the following ArchUnit rules:
1. **Tool CDI Scoping:** Methods annotated with `@Tool` must be declared in classes annotated with `@ApplicationScoped`.
2. **Reflection Registration:** Classes and records residing in `..model..`, `..entity..`, `..remote..`, or `..gui..` must be annotated with `@RegisterForReflection`.
3. **Complex Record Builder Standard:** Records with 4 or more fields must be annotated with `@Builder(toBuilder = true)`.
4. **Entity Encapsulation:** Panache entities (`@Entity`) must strictly reside in `org.quarkus.agent.entity` and never be accessed outside.
5. **Programmatic Test Transaction Scope:** Test and lifecycle methods (`@Test`, `@BeforeEach`, `@AfterEach`) must not be annotated with `@Transactional`. Integration tests use `QuarkusTransaction.requiringNew()`.
