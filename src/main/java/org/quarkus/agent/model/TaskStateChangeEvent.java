package org.quarkus.agent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;

import java.time.Instant;

/**
 * Event published upon task lifecycle transitions to trigger state synchronization and Markdown projections.
 */
@RegisterForReflection
@Builder(toBuilder = true)
public record TaskStateChangeEvent(
    String projectId,
    String taskId,
    TaskStatus newStatus,
    String phaseName,
    String stepSummary,
    Instant timestamp
) {}
