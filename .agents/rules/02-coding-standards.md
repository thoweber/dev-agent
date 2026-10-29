# dev-agent Coding Standards & Patterns

## 1. Java 25 & Quarkus 3.40 Conventions
* Use modern Java 25 constructs: immutable `record` for Value Objects, DTOs, and verdicts; sealed interfaces for domain events; pattern matching in `switch`.
* **Constructor Injection:** Always pass dependencies as constructor arguments rather than annotating fields with `@Inject`. Declare all injected dependency fields as `final` and use `@RequiredArgsConstructor`.
* Use `@ApplicationScoped` for CDI singletons and tools.
* **ArchUnit Native Compliance:**
  * All tools (`@Tool`) MUST be declared in `@ApplicationScoped` classes.
  * All classes and records residing in `..model..`, `..entity..`, `..remote..`, or `..gui..` MUST be annotated with `@RegisterForReflection`.

## 2. Strongly-Typed Value Objects & Complex Record Builder Standard
* **Complex Record Builder Standard:** Any `record` or Value Object containing 4 or more fields (e.g., `GateVerdict`, `AgentState`, `StepAuditEntity`) MUST be annotated with `@Builder(toBuilder = true)`. Positional `new Record(...)` calls for complex records are forbidden — instantiation MUST use `Record.builder()...build()` or `record.toBuilder()`.
* **Manual Null Checks:** Use `isNull(val)` or `nonNull(val)` with static imports (`import static java.util.Objects.isNull; import static java.util.Objects.nonNull;`).
* **Optional Return Types:** Use `Optional<T>` for query methods that may not produce a value.
* **Empty Collections:** Never return `null` for collection return types — always return an immutable empty collection (`List.of()`, `Set.of()`, `Map.of()`).

## 3. Strongly-Typed Domain Exceptions
* Production code MUST NEVER throw raw `RuntimeException` or `Exception`. Throw specific domain exceptions (e.g., `SecurityException`, `WorkspaceException`, `BuildExecutionException`).
* Catch low-level technical exceptions at boundaries and wrap them with rich diagnostic context.

## 4. Test Engineering & Testing Standards
* **Detailed Testing Standards:** Refer to [05-testing-standards.md](file:///home/developer/projects/dev-agent/.agents/rules/05-testing-standards.md) for full testing pyramid tiers, fixture standards, and invariants.
* **Dedicated 1-to-1 Unit Test Files:** Every production class MUST have its own dedicated 1-to-1 unit test class (`<TargetClassName>Test.java`).
* **Given-When-Then Display Names:** All `@Test` methods MUST use `@DisplayName("Given [precondition] - when [action/trigger] - then [expected outcome]")`.
* **AssertJ Assertions:** Use AssertJ (`assertThat()`, `assertThatThrownBy()`) exclusively. `org.junit.jupiter.api.Assertions` is strictly forbidden.
* **Mandatory Quality Gates:** Overall repository code coverage MUST satisfy Line $\ge 95.0\%$ and Branch $\ge 90.0\%$ (see [03-quality-and-slas.md](file:///home/developer/projects/dev-agent/.agents/rules/03-quality-and-slas.md) and [05-testing-standards.md](file:///home/developer/projects/dev-agent/.agents/rules/05-testing-standards.md)).
