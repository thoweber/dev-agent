package org.quarkus.agent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Strongly-typed domain value object representing a persistent, multi-tenant isolated project identifier.
 * Backed by a deterministic RFC 4122 UUIDv3 derived from the normalized project coordinate.
 */
@RegisterForReflection
public record ProjectId(UUID value) {

    public ProjectId {
        Objects.requireNonNull(value, "Project UUID must not be null");
    }

    /**
     * Derives a deterministic RFC 4122 UUIDv3 from a normalized project coordinate (e.g. "github.com/org/repo").
     */
    public static ProjectId fromCoordinate(String coordinate) {
        Objects.requireNonNull(coordinate, "Coordinate must not be null");
        String trimmed = coordinate.trim();
        if (trimmed.isBlank()) {
            throw new IllegalArgumentException("Coordinate must not be blank");
        }
        UUID uuid = UUID.nameUUIDFromBytes(trimmed.getBytes(StandardCharsets.UTF_8));
        return new ProjectId(uuid);
    }

    /**
     * Parses an existing 36-character UUID string (e.g. from database persistence or CLI arguments).
     */
    public static ProjectId fromString(String uuidString) {
        Objects.requireNonNull(uuidString, "UUID string must not be null");
        return new ProjectId(UUID.fromString(uuidString.trim()));
    }

    /**
     * Wraps an existing raw UUID into a ProjectId.
     */
    public static ProjectId of(UUID uuid) {
        return new ProjectId(uuid);
    }

    /**
     * Returns the 36-character standard string representation of the project UUID.
     */
    public String idString() {
        return value.toString();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
