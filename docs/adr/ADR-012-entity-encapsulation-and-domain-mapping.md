# ADR-012: Panache Entity Encapsulation & Domain Model Decoupling

* **Status:** Accepted
* **Date:** 2026-10-26

## Context
Exposing Hibernate/Panache entities (`TaskRunEntity`, `StepAuditEntity`) across package boundaries creates severe architectural and operational defects:
1. **JPA Session Leakage & `LazyInitializationException`:** Post-commit synchronization hooks and background tasks access lazy entity associations after the Hibernate session and JTA transaction have closed.
2. **Persistence Infiltration into Domain Logic:** Domain services (e.g. `CrashRecoveryDetector`, `StateSyncService`) directly invoke Panache active-record static methods (`TaskRunEntity.list()`, `step.persist()`), tightly coupling business workflows to SQL relational schemas.

## Decision
Enforce strict package encapsulation of Panache entities and mandate decoupled domain models:
1. **Entity Confinement:**
   - Panache entities (`TaskRunEntity`, `StepAuditEntity`, `MetricLogEntity`) reside exclusively in `org.quarkus.agent.entity` and must never be accessed by any class outside this package.
   - Guarded at build and CI time via an automated ArchUnit rule:
     ```java
     classes()
         .that().areAnnotatedWith(jakarta.persistence.Entity.class)
         .should().onlyBeAccessed().byClassesThat().resideInAPackage("org.quarkus.agent.entity..");
     ```
2. **Domain Models (`org.quarkus.agent.model`):**
   - Domain records (`TaskRun`, `StepAudit`) and domain enums (`TaskStatus`) reside in `org.quarkus.agent.model`.
   - Domain records are immutable, reflection-registered (`@RegisterForReflection`), and equipped with fluent builders (`@Builder(toBuilder = true)`).
3. **Dedicated Mapping & Persistence Service (`TaskPersistenceService`):**
   - `TaskPersistenceService` in `org.quarkus.agent.entity` serves as the sole data access gateway and sink.
   - Manages transactional boundaries, persistence operations, and bidirectional entity-to-domain mapping (`toDomain()`).
   - Consumer services (`StateSyncService`, `CrashRecoveryDetector`) operate exclusively on detached domain records (`TaskRun`), completely eliminating `LazyInitializationException` risks.

## Consequences
### Positive
* 100% elimination of `LazyInitializationException` across background threads and post-commit hooks.
* Pure Hexagonal decoupling: database schema changes do not impact CLI commands, markdown projection, or crash recovery logic.
* Architectural integrity strictly enforced via ArchUnit.

### Negative
* Requires explicit mapping between JPA entities and domain records in `TaskPersistenceService`.
