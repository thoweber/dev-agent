package org.quarkus.agent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.quarkus.agent.entity.TaskStatus;

import java.time.Instant;

/**
 * Event published upon task lifecycle transitions to trigger state synchronization and Markdown projections.
 */
@RegisterForReflection
public record TaskStateChangeEvent(
    String projectId,
    String taskId,
    TaskStatus newStatus,
    String phaseName,
    String stepSummary,
    Instant timestamp
) {}
