---
name: langchain4j-specialist
description: Designs and implements Quarkus-LangChain4j AiServices, prompt templates, pure-Java lexical BM25 retrieval, and Caffeine deterministic gatekeeper caching for dev-agent.
---

# LangChain4j & Cognitive Specialist Skill (`langchain4j-specialist`)

This skill governs the cognitive orchestration layer, model routing, system prompt templating, pure-Java lexical retrieval, and deterministic decision caching in **dev-agent**.

---

## 1. Universal Personas & AiServices

Declare AiServices using Quarkus-LangChain4j annotations:
* **`FullStackPlannerAiService`:** Model `planner` (Gemini Pro / Claude 3.5). System prompt analyzes stories and outputs implementation DAGs.
* **`UniversalArchitectGatekeeperAiService`:** Model `architect-gate` (Flash / Claude / Ollama at `temperature = 0.0`). Returns JSON matching `GateVerdict`.
* **`FullStackCodeSynthesizerAiService`:** Model `coder` (Claude 3.5 Sonnet / Ollama). Configured with `tools = { CodeWorkspaceTools.class, ExecutionToolProxy.class }`.

---

## 2. Zero-Knowledge Dynamic Prompt Ingestion

* System prompts MUST contain zero hardcoded project names, framework keywords, or fixed file paths.
* Ingest domain guidelines and architectural rules dynamically via template parameters (`{rulesContext}`, `{retrievedAdrs}`).

---

## 3. Pure-Java BM25 Retrieval & Deterministic Caching

* Local semantic search uses pure-Java inverted indexing (BM25) over rule summaries and ADRs. Zero C++ ONNX JNI libraries permitted.
* Gatekeeper audits at `temperature: 0.0` are cached using Caffeine:
  `@CacheResult(cacheName = "architect-verdicts")`
  Key: $\text{SHA-256}(\text{UnifiedDiff} + \text{RetrievedAdrsDigest})$.

---

## 4. 3-Tier Hierarchical Memory Invariants (ADR-011)

* **Tier 1 (Pinned System Invariants):** Immutable project guidelines and architecture rules (`SystemMessage`). Quarantined strictly to Tier 1; never placed in sliding scratchpad.
* **Tier 2 (Pinned Task Spec):** User story and approved implementation plan JSON (`UserMessage`).
* **Tier 3 (Sliding Scratchpad & Atomic Eviction):** Bounded by `MAX_SCRATCHPAD_TURNS`. Eviction MUST operate on complete atomic turns (purging the user prompt and all its answering assistant messages and tool execution results together) to prevent severed context and API 400 Bad Request errors.
