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
5. **Multi-Tenant Registry & Constructor-Enforced Session Tying:**
   - `HierarchicalMemoryStore` is unmanaged and tied strictly to an explicit, strongly-typed `SessionId` at construction time. The legacy no-arg constructor is eliminated, prohibiting untied or session-less memory stores.
   - An `@ApplicationScoped` `HierarchicalChatMemoryProvider` implements LangChain4j `ChatMemoryProvider`, maintaining a concurrent map of active `HierarchicalMemoryStore` buffers indexed by `SessionId` (adapted transparently from LangChain4j's `Object memoryId` via `SessionId.from(memoryId)`).
   - Quarkus-LangChain4j automatically discovers `HierarchicalChatMemoryProvider` via `BeanChatMemoryProviderSupplier` and injects conversation-specific stores into `@RegisterAiService` beans.
6. **Lock-Free Concurrency & Thread-Safety Scope:**
   - `HierarchicalMemoryStore` uses unsynchronized methods backed by `java.util.concurrent` primitives:
     - `ConcurrentLinkedDeque<ChatMessage>` for the sliding scratchpad (weakly consistent iteration, lock-free insertion and poll operations).
     - `CopyOnWriteArrayList<SystemMessage>` for pinned system invariants.
     - `AtomicReference<UserMessage>` for the pinned task specification.
   - **Guarantees & Boundaries:**
     - Individual collection operations and field accesses are thread-safe, ensuring safe publication and memory visibility across worker thread handoffs and non-blocking observer reads (`messages()`).
     - **Compound operations are intentionally not atomic:** Multi-step turn operations (such as validating preceding tool requests during `add()` or turn trimming) are unsynchronized and not serialized. Each `HierarchicalMemoryStore` is designed to be operated sequentially by its session execution thread.
     - Concurrency across tasks is achieved by physical instance isolation per `SessionId` via `HierarchicalChatMemoryProvider`, rather than concurrent mutation of a single shared memory store.

## Consequences
### Positive
* Prevents API 400 Bad Request rejections across Claude, OpenAI, and Gemini providers.
* Eliminates hallucinations caused by assistant responses lacking user context.
* Protects prompt invariants from being pushed out by verbose compiler traces.
* Eliminates cross-task memory pollution by isolating memory store instances per session in `HierarchicalChatMemoryProvider`.
* Fully compliant with Quarkus-LangChain4j SPI conventions without manual factory wiring.

### Negative
* Turn-based eviction may discard multiple messages in a single trim cycle when a turn contains multiple tool calls.
* Single `HierarchicalMemoryStore` instances do not synchronize compound operations across concurrent threads; concurrent mutations on the same store instance can interleave.
