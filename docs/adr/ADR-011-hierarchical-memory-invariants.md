# ADR-011: 3-Tier Hierarchical Memory Turn Invariants & Atomic Tool Eviction

* **Status:** Accepted
* **Date:** 2026-10-26

## Context
In autonomous LLM agent execution, naive sliding window chat memory models suffer from three catastrophic failure modes:
1. **Asymmetric / Severed Turn Eviction:** Trimming the scratchpad by individual message count rather than conversational turns deletes user prompts while retaining assistant answers, causing context hallucinations and LLM API 400 Bad Request errors on models requiring alternating turns.
2. **Orphaned Tool Execution Results:** Evicting an `AiMessage` containing tool calls without atomically evicting its dependent `ToolExecutionResultMessage`s produces orphaned tool responses, triggering immediate LLM provider validation rejections.
3. **System Prompt Invariant Poisoning:** Allowing runtime system messages to enter the sliding scratchpad causes prompt drifting and role confusion as messages slide across iterations.

## Decision
Implement a 3-Tier Hierarchical Memory architecture in `HierarchicalMemoryStore`:
1. **Tier 1 (Pinned System Invariants):**
   - Strictly reserved for immutable project rules, framework conventions, and architecture invariants.
   - Initialized via `pinInvariants()` and maintained as a dedicated list of `SystemMessage` instances.
   - Any `SystemMessage` passed to `add()` is quarantined into Tier 1 and never allowed into Tier 3 (sliding scratchpad).
2. **Tier 2 (Pinned Task Specification):**
   - Initialized via `pinTaskSpecification()` containing the user story and approved plan JSON.
   - Preserved across all sliding iterations and evicted only on full memory reset.
3. **Tier 3 (Sliding Scratchpad & Atomic Turn Eviction):**
   - Bounded by `MAX_SCRATCHPAD_TURNS`.
   - Eviction operates on **atomic conversational turns**: evicting a turn removes the `UserMessage` prompt AND its answering `AiMessage` and all associated `ToolExecutionResultMessage`s together as a single unit.
   - Never leaves a severed `AiMessage` or orphaned `ToolExecutionResultMessage` at the head of the scratchpad.
4. **Structural Invariants Guaranteed by Construction & Black-Box Test Verification:**
   - Turn structure invariants (pinned system invariants at head, atomic eviction preserving full turns, rejection of orphaned tool results) are guaranteed by construction within `HierarchicalMemoryStore`.
   - Structural integrity is verified black-box through the public `messages()` contract using test assertions (`HierarchicalMemoryAssert`), avoiding synthetic test hooks in production code.
5. **Multi-Tenant Registry & Session Lifecycle Decoupling:**
   - `HierarchicalMemoryStore` is unmanaged and instantiated per conversation / task session, carrying an explicit `memoryId`. It is not `@ApplicationScoped`.
   - An `@ApplicationScoped` `HierarchicalChatMemoryProvider` implements LangChain4j `ChatMemoryProvider`, maintaining a concurrent map of active `HierarchicalMemoryStore` buffers indexed by `memoryId` (e.g. `taskId`).
   - Quarkus-LangChain4j automatically discovers `HierarchicalChatMemoryProvider` via `BeanChatMemoryProviderSupplier` and injects conversation-specific stores into `@RegisterAiService` beans.
6. **Lock-Free Concurrency & Thread Safety:**
   - `HierarchicalMemoryStore` uses unsynchronized methods backed by thread-safe `java.util.concurrent` primitives:
     - `ConcurrentLinkedDeque<ChatMessage>` for the sliding scratchpad (weakly consistent iteration, lock-free insertions and poll operations).
     - `CopyOnWriteArrayList<SystemMessage>` for pinned system invariants.
     - `AtomicReference<UserMessage>` for the pinned task specification.
   - Read operations (`messages()`) operate on point-in-time snapshots, preventing monitor contention while guaranteeing thread safety under concurrent observer access.

## Consequences
### Positive
* Prevents API 400 Bad Request rejections across Claude, OpenAI, and Gemini providers.
* Eliminates hallucinations caused by assistant responses lacking user context.
* Protects prompt invariants from being pushed out by verbose compiler traces.
* Eliminates cross-task memory pollution and race conditions in concurrent multi-agent or telemetry executions.
* Fully compliant with Quarkus-LangChain4j SPI conventions without manual factory wiring.

### Negative
* Turn-based eviction may discard multiple messages in a single trim cycle when a turn contains multiple tool calls.
