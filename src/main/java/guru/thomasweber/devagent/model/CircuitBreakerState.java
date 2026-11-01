package guru.thomasweber.devagent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * State machine states for the normalized error loop circuit breaker.
 */
@RegisterForReflection
public enum CircuitBreakerState {
    NORMAL,
    WARNING,
    PIVOT_ACTIVE,
    TRIPPED
}
