# ADR-007: Contract-Driven OpenAPI to TypeScript Client Sync

* **Status:** Accepted
* **Date:** 2026-10-25

## Context
Full-stack development frequently suffers from contract drift: backend DTOs or endpoints change, but frontend TypeScript models and HTTP services fall out of sync, causing runtime errors.

## Decision
Automate OpenAPI contract synchronization:
1. Export the OpenAPI schema during backend compilation via SmallRye OpenAPI flags (`-Dquarkus.smallrye-openapi.store-schema-file=true`).
2. Regenerate TypeScript client models using `@openapitools/openapi-generator-cli` into an isolated folder (`.../api/generated/`).
3. Preserve manually authored HTTP interceptors and wrap client Observables using Angular Signals (`toSignal()`).

## Consequences
### Positive
* Backend and frontend models remain in strict synchronization.
* Eliminates boilerplate manual TypeScript API client authoring.

### Negative
* Requires OpenAPI generator tooling in the frontend build chain.

## Non-Negotiable Invariants
* Generated API code must reside strictly in an isolated `generated/` subfolder.
