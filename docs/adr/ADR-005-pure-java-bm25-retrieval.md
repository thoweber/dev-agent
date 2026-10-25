# ADR-005: Zero-JNI Pure-Java Lexical BM25 Inverted Index RAG

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
Standard RAG frameworks rely on ONNX runtimes and vector databases that require native C++ JNI bindings. These native binaries frequently fail or crash in GraalVM AOT native compilation and across diverse OS distributions.

## Decision
Implement a pure-Java lexical BM25 inverted index for local rule and ADR semantic discovery. The index parses Markdown headers, summaries, and code blocks in-memory.

## Consequences
### Positive
* Zero JNI C++ dependencies; 100% native GraalVM AOT safe.
* Zero network latency or external embedding model costs.
* Reduces Gatekeeper prompt context token payloads by over 70% (from ~15k to <3k tokens).

### Negative
* Lexical matching requires descriptive titles, keywords, and rule summaries rather than dense vector similarity.

## Non-Negotiable Invariants
* Zero native C/C++ library dependencies in local search pipelines.
