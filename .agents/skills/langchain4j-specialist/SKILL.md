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
