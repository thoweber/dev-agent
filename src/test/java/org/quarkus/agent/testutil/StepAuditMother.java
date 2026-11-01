package org.quarkus.agent.testutil;

import org.quarkus.agent.model.StepAudit;

import java.time.Instant;

/**
 * Object Mother factory providing canonical, behavior-focused fixtures for StepAudit domain models.
 */
public final class StepAuditMother {

    public static final String DEFAULT_TOOL = "mvn_test";
    public static final long DEFAULT_DURATION_MS = 150L;

    private StepAuditMother() {}

    /**
     * Pre-configured builder with standard defaults (default tool name, 150ms duration, exit code 0, current timestamp).
     */
    public static StepAudit.StepAuditBuilder builder() {
        return StepAudit.builder()
            .toolName(DEFAULT_TOOL)
            .durationMs(DEFAULT_DURATION_MS)
            .exitCode(0)
            .createdAt(Instant.now());
    }

    /**
     * Pre-configured builder with specified tool name and standard defaults.
     */
    public static StepAudit.StepAuditBuilder builder(String toolName) {
        return builder().toolName(toolName);
    }

    /**
     * Canonical tool execution step with explicit step index mapped to ID.
     */
    public static StepAudit toolExecution(int stepIndex, String toolName, String prompt, String output, int exitCode) {
        return builder(toolName)
            .id((long) stepIndex)
            .sanitizedInput(prompt)
            .outputSummary(output)
            .exitCode(exitCode)
            .build();
    }

    /**
     * Canonical tool execution step with tool name, output, and explicit exit code.
     */
    public static StepAudit toolExecution(String toolName, String output, int exitCode) {
        return builder(toolName)
            .outputSummary(output)
            .exitCode(exitCode)
            .build();
    }

    /**
     * Canonical successful tool execution step with default exit code 0.
     */
    public static StepAudit toolExecution(String toolName, String output) {
        return toolExecution(toolName, output, 0);
    }

    /**
     * Canonical phase transition step with explicit step index mapped to ID and 0ms duration.
     */
    public static StepAudit phaseTransition(int stepIndex, String fromPhase, String toPhase) {
        return builder(toPhase)
            .id((long) stepIndex)
            .sanitizedInput("transition: " + fromPhase + " -> " + toPhase)
            .outputSummary("Phase transition: " + fromPhase + " -> " + toPhase)
            .durationMs(0L)
            .exitCode(0)
            .build();
    }

    /**
     * Canonical phase transition step without explicit step index and 0ms duration.
     */
    public static StepAudit phaseTransition(String fromPhase, String toPhase) {
        return builder(toPhase)
            .sanitizedInput("transition: " + fromPhase + " -> " + toPhase)
            .outputSummary("Phase transition: " + fromPhase + " -> " + toPhase)
            .durationMs(0L)
            .exitCode(0)
            .build();
    }
}
