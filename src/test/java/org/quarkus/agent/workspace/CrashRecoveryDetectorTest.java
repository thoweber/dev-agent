package org.quarkus.agent.workspace;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.entity.TaskRunEntity;
import org.quarkus.agent.entity.TaskStatus;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CrashRecoveryDetectorTest {

    @Inject
    CrashRecoveryDetector crashDetector;

    @Test
    @Transactional
    @DisplayName("Given crashed task with dead PID - when findResumableTask called - then identifies task for recovery")
    void testDetectCrashedTask() {
        TaskRunEntity.deleteAll();

        TaskRunEntity crashedTask = new TaskRunEntity();
        crashedTask.projectId = "test-project-crash";
        crashedTask.taskId = "TASK-CRASH-01";
        crashedTask.status = TaskStatus.RED_TEST;
        crashedTask.currentPhase = "RED_TEST";
        crashedTask.pid = 999999999L; // Non-existent dead PID
        crashedTask.createdAt = Instant.now();
        crashedTask.updatedAt = Instant.now();
        crashedTask.persist();

        Optional<TaskRunEntity> result = crashDetector.findResumableTask("test-project-crash");

        assertThat(result).isPresent();
        assertThat(result.get().taskId).isEqualTo("TASK-CRASH-01");
        assertThat(result.get().status).isEqualTo(TaskStatus.RED_TEST);
    }
}
