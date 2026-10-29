package org.quarkus.agent.testutil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.model.StepAudit;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StepAuditMotherTest {

    @Test
    @DisplayName("Given default builder - when invoked without customization - populates standard defaults")
    void testDefaultBuilder() {
        StepAudit step = StepAuditMother.builder().build();

        assertThat(step.toolName()).isEqualTo(StepAuditMother.DEFAULT_TOOL);
        assertThat(step.durationMs()).isEqualTo(StepAuditMother.DEFAULT_DURATION_MS);
        assertThat(step.exitCode()).isZero();
        assertThat(step.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Given custom toolName - when builder with toolName invoked - sets toolName and standard defaults")
    void testBuilderWithToolName() {
        StepAudit step = StepAuditMother.builder("custom_tool").build();

        assertThat(step.toolName()).isEqualTo("custom_tool");
        assertThat(step.durationMs()).isEqualTo(StepAuditMother.DEFAULT_DURATION_MS);
        assertThat(step.exitCode()).isZero();
        assertThat(step.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Given tool execution parameters with stepIndex - when toolExecution invoked - maps stepIndex to id and populates fields")
    void testToolExecutionWithStepIndex() {
        StepAudit step = StepAuditMother.toolExecution(3, "mvn_test", "mvn test -Dtest=Foo", "BUILD SUCCESS", 0);

        assertThat(step.id()).isEqualTo(3L);
        assertThat(step.toolName()).isEqualTo("mvn_test");
        assertThat(step.sanitizedInput()).isEqualTo("mvn test -Dtest=Foo");
        assertThat(step.outputSummary()).isEqualTo("BUILD SUCCESS");
        assertThat(step.durationMs()).isEqualTo(StepAuditMother.DEFAULT_DURATION_MS);
        assertThat(step.exitCode()).isZero();
        assertThat(step.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Given tool execution parameters without stepIndex - when toolExecution invoked - populates fields with default duration and exit code")
    void testToolExecutionOverloads() {
        StepAudit stepWithExit = StepAuditMother.toolExecution("git_commit", "Committed change", 1);
        assertThat(stepWithExit.toolName()).isEqualTo("git_commit");
        assertThat(stepWithExit.outputSummary()).isEqualTo("Committed change");
        assertThat(stepWithExit.exitCode()).isEqualTo(1);
        assertThat(stepWithExit.durationMs()).isEqualTo(StepAuditMother.DEFAULT_DURATION_MS);

        StepAudit stepSuccess = StepAuditMother.toolExecution("mvn_clean", "Cleaned target");
        assertThat(stepSuccess.toolName()).isEqualTo("mvn_clean");
        assertThat(stepSuccess.outputSummary()).isEqualTo("Cleaned target");
        assertThat(stepSuccess.exitCode()).isZero();
    }

    @Test
    @DisplayName("Given phase transition parameters with stepIndex - when phaseTransition invoked - formats phase transition fields and zero duration")
    void testPhaseTransitionWithStepIndex() {
        StepAudit step = StepAuditMother.phaseTransition(5, "PLANNING", "RED_TEST");

        assertThat(step.id()).isEqualTo(5L);
        assertThat(step.toolName()).isEqualTo("RED_TEST");
        assertThat(step.sanitizedInput()).isEqualTo("transition: PLANNING -> RED_TEST");
        assertThat(step.outputSummary()).isEqualTo("Phase transition: PLANNING -> RED_TEST");
        assertThat(step.durationMs()).isZero();
        assertThat(step.exitCode()).isZero();
        assertThat(step.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Given phase transition parameters without stepIndex - when phaseTransition invoked - formats transition without explicit id")
    void testPhaseTransitionWithoutStepIndex() {
        StepAudit step = StepAuditMother.phaseTransition("RED_TEST", "GREEN_PATCH");

        assertThat(step.id()).isNull();
        assertThat(step.toolName()).isEqualTo("GREEN_PATCH");
        assertThat(step.sanitizedInput()).isEqualTo("transition: RED_TEST -> GREEN_PATCH");
        assertThat(step.outputSummary()).isEqualTo("Phase transition: RED_TEST -> GREEN_PATCH");
        assertThat(step.durationMs()).isZero();
        assertThat(step.exitCode()).isZero();
        assertThat(step.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("Given custom overrides - when builder customized - allows overriding fields cleanly")
    void testBuilderOverrides() {
        Instant fixedInstant = Instant.parse("2026-10-25T12:00:00Z");
        StepAudit step = StepAuditMother.builder("custom_tool")
            .id(42L)
            .sanitizedInput("input text")
            .outputSummary("output summary")
            .durationMs(5000L)
            .exitCode(2)
            .createdAt(fixedInstant)
            .build();

        assertThat(step.id()).isEqualTo(42L);
        assertThat(step.toolName()).isEqualTo("custom_tool");
        assertThat(step.sanitizedInput()).isEqualTo("input text");
        assertThat(step.outputSummary()).isEqualTo("output summary");
        assertThat(step.durationMs()).isEqualTo(5000L);
        assertThat(step.exitCode()).isEqualTo(2);
        assertThat(step.createdAt()).isEqualTo(fixedInstant);
    }
}
