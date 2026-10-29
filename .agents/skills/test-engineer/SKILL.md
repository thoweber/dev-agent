---
name: test-engineer
description: Authors 1-to-1 unit tests, ArchUnit native compliance rules, Picocli command tests, and boundary assertions using AssertJ for dev-agent.
---

# Test Engineer Skill (`test-engineer`)

This skill governs test engineering, ArchUnit architecture rule enforcement, Picocli CLI integration tests, and AssertJ test standards in **dev-agent**. Consult [05-testing-standards.md](file:///home/developer/projects/dev-agent/.agents/rules/05-testing-standards.md) for full test architecture and engineering invariants.

---

## 1. 1-to-1 Test Mapping Standard

* Every production class MUST have its own dedicated 1-to-1 unit test class (`<TargetClassName>Test.java`).
* All `@Test` methods must use `@DisplayName("Given [precondition] - when [action/trigger] - then [expected outcome]")`.
* Use AssertJ (`assertThat()`, `assertThatThrownBy()`) exclusively. JUnit assertions are strictly forbidden.

---

## 2. ArchUnit Native Compliance Rules

All tests in `AgentArchitectureTest` verify native GraalVM safety and architecture invariants:
1. All methods annotated with `@Tool` must be declared in classes annotated with `@ApplicationScoped`.
2. All non-interface classes in packages `..model..`, `..entity..`, `..remote..`, and `..gui..` must be annotated with `@RegisterForReflection`.
3. Panache entities annotated with `@Entity` must only be accessed by classes in `org.quarkus.agent.entity..`.
4. Test methods (`@Test`, `@BeforeEach`, `@AfterEach`) must not be annotated with `@Transactional`.

---

## 3. Test Transaction Scope Protocol

* Never annotate `@Test` or lifecycle fixtures with `@Transactional`.
* Use `QuarkusTransaction.requiringNew().run(...)` or `.call(...)` for explicit transaction scoping in integration tests.
* Production service methods (`TaskPersistenceService`) manage their own transactional boundaries.

---

## 4. Coverage SLA & Quality Gates

* Repository overall code coverage MUST satisfy Line Coverage $\ge 95.0\%$ and Condition/Branch Coverage $\ge 90.0\%$, verified via `./scripts/parse_jacoco.py --fail-under-gates`. Consult [03-quality-and-slas.md](file:///home/developer/projects/dev-agent/.agents/rules/03-quality-and-slas.md).

---

## 5. Behavioral Testing & Ergonomics

* **Zero-Reflection Policy:** Tests must verify observable behaviors through public APIs; using reflection (`field.setAccessible(true)`) to manipulate or inspect private state is strictly prohibited.
* **Canonical Fixtures:** Complex records (4+ fields) must use `TaskRunMother` and `WorktreeTestFixture` instead of repeating inline builder code.
* **Script Test Parity:** Python automation scripts in `scripts/` must have companion unit test suites under `tests/scripts/`.
