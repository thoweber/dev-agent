package guru.thomasweber.devagent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Prescribed actions emitted by the normalized error loop circuit breaker.
 */
@RegisterForReflection
public enum LoopAction {
    CONTINUE,
    INJECT_WARNING,
    ROLLBACK_AND_PIVOT,
    TRIP_AND_HALT
}
