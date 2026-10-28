package org.quarkus.agent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;

import java.time.Instant;

/**
 * Domain representation of an individual tool execution step audit.
 */
@RegisterForReflection
@Builder(toBuilder = true)
public record StepAudit(
    Long id,
    String toolName,
    String sanitizedInput,
    String outputSummary,
    long durationMs,
    int exitCode,
    Instant createdAt
) {}
