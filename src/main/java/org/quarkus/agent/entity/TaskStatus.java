package org.quarkus.agent.entity;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Task lifecycle status representing the active state of an engineering task.
 */
@RegisterForReflection
public enum TaskStatus {
    PLANNING,
    RED_TEST,
    GREEN_PATCH,
    VERIFYING,
    CI_POLLING,
    COMPLETED,
    FAILED,
    ABORTED
}
