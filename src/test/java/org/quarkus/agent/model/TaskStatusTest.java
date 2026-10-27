package org.quarkus.agent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStatusTest {

    @Test
    @DisplayName("Given task statuses - identifies terminal and non-terminal correctly")
    void testTaskStatusTerminality() {
        assertThat(TaskStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(TaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(TaskStatus.ABORTED.isTerminal()).isTrue();

        assertThat(TaskStatus.PLANNING.isTerminal()).isFalse();
        assertThat(TaskStatus.RED_TEST.isTerminal()).isFalse();
        assertThat(TaskStatus.GREEN_PATCH.isTerminal()).isFalse();
        assertThat(TaskStatus.VERIFYING.isTerminal()).isFalse();
        assertThat(TaskStatus.CI_POLLING.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("Given TaskStateChangeEvent builder - builds and toBuilder functions correctly")
    void testTaskStateChangeEventBuilder() {
        Instant now = Instant.now();
        TaskStateChangeEvent event = TaskStateChangeEvent.builder()
            .projectId("org/repo")
            .taskId("TASK-100")
            .newStatus(TaskStatus.RED_TEST)
            .phaseName("RED_TEST")
            .stepSummary("Running red test")
            .timestamp(now)
            .build();

        assertThat(event.projectId()).isEqualTo("org/repo");
        assertThat(event.taskId()).isEqualTo("TASK-100");
        assertThat(event.newStatus()).isEqualTo(TaskStatus.RED_TEST);
        assertThat(event.phaseName()).isEqualTo("RED_TEST");
        assertThat(event.stepSummary()).isEqualTo("Running red test");
        assertThat(event.timestamp()).isEqualTo(now);

        TaskStateChangeEvent modified = event.toBuilder()
            .newStatus(TaskStatus.GREEN_PATCH)
            .build();

        assertThat(modified.newStatus()).isEqualTo(TaskStatus.GREEN_PATCH);
        assertThat(modified.taskId()).isEqualTo("TASK-100");
    }
}
