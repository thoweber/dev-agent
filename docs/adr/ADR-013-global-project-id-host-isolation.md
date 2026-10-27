# ADR-013: Global Project ID Host Isolation and Deterministic UUIDv3 Derivation

* **Status:** Accepted
* **Date:** 2026-10-27

## Context
Persistent task execution state and recovery candidate queues require unambiguous project scoping within embedded H2 database storage and filesystem paths. Previous coordinate implementations exhibited several architectural risks:
1. **Multi-Tenant Coordinate Collision:** Repositories hosted across different Git providers (e.g., `github.com/acme/backend` vs `gitlab.com/acme/backend` vs internal GitLab/Gitea instances) share identical path segments. Omitting or stripping host domains caused cross-tenant database row sharing.
2. **Credential Leakage into Identity Sinks:** Authenticated HTTPS remote URLs (e.g., `https://oauth2:token@github.com/org/repo.git`) matched naive regex parsers, persisting raw access tokens into `project_id` database columns, state events, and logs.
3. **Escaping, Injection, and Path Vulnerabilities:** Using arbitrary human-readable strings with slashes, colons, or percent-encoding as database keys, socket tokens, or workspace paths created escaping fragility and security boundary risks.

## Decision
Establish deterministic RFC 4122 UUIDv3 project identifiers derived from canonical, credential-scrubbed repository coordinates:

1. **Canonical Coordinate Normalization (`ProjectIdResolver`):**
   - Parses Git remotes across all standard transport protocols:
     - SCP syntax (`git@host:owner/repo.git`) $\rightarrow$ `host/owner/repo`
     - HTTPS/HTTP with authentication (`https://user:pass@host/owner/repo.git`) $\rightarrow$ `host/owner/repo`
     - SSH with explicit protocol and optional port (`ssh://git@host:22/owner/repo.git`) $\rightarrow$ `host/owner/repo`
     - Git protocol (`git://host/owner/repo.git`) $\rightarrow$ `host/owner/repo`
   - Strips `userInfo` authentication, transport ports, and `.git` extensions.
   - Lowers host casing per RFC 3986 host case-insensitivity.
   - Offline or non-git workspaces resolve to `local:<canonicalRealPath>`.

2. **Deterministic UUIDv3 Derivation & Domain Value Object (`ProjectId`):**
   - Domain value object `ProjectId` in `org.quarkus.agent.model` wraps an immutable `java.util.UUID`.
   - Projects derive a deterministic RFC 4122 Version 3 UUID via standard JDK `UUID.nameUUIDFromBytes(coordinate.getBytes(StandardCharsets.UTF_8))`.
   - Factory methods are strictly decoupled:
     - `ProjectId.fromCoordinate(String coordinate)`: Hashes coordinate to UUIDv3.
     - `ProjectId.fromString(String uuidString)`: Parses an existing 36-character UUID string.
   - Database storage (`TaskRunEntity.projectId`) stores the uniform 36-character UUID string.

3. **Separation of Human Coordinate vs Machine Identity:**
   - Coordinate extraction remains in `ProjectIdResolver.resolveCoordinate()`, keeping preimage parsing separate from the immutable `ProjectId` value object.
   - High-level orchestrators (`CrashRecoveryDetector`, `TaskPersistenceService`) consume `ProjectId` directly.

## Consequences
### Positive
* **Deterministic Multi-Tenant Isolation:** Different Git hosts with identical repository names map to completely distinct UUIDv3 values.
* **Zero Credential Leakage:** Authentication tokens and passwords never enter the UUIDv3 preimage or storage sinks.
* **Character-Safe Uniform Format:** Standard 36-character lowercase hex format eliminates SQL escaping, URL encoding, and path traversal defects.
* **Pure Standard Java 25 & Zero-JNI Native Safety:** Uses JDK `java.util.UUID` without external C++ or third-party libraries, fully compliant with Ahead-of-Time GraalVM Mandrel compilation.

### Negative
* Coordinate names cannot be derived backwards from stored UUIDs alone (one-way MD5 hash), requiring `ProjectIdResolver.resolveCoordinate()` when human-readable labels are requested in active workspaces.
