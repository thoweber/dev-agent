package org.quarkus.agent.workspace;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.entity.StepAuditEntity;
import org.quarkus.agent.entity.TaskRunEntity;
import org.quarkus.agent.entity.TaskStatus;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CrashRecoveryDetectorTest {

    @Inject
    CrashRecoveryDetector crashDetector;

    @BeforeEach
    @Transactional
    void setUp() {
        StepAuditEntity.deleteAll();
        StepAuditEntity.deleteAll();
        TaskRunEntity.deleteAll();
    }

    @Test
    @Transactional
    @DisplayName("Given crashed task with dead PID - when findResumableTask called - then identifies task for recovery")
    void testDetectCrashedTask() {
        StepAuditEntity.deleteAll();
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

    @Test
    @Transactional
    @DisplayName("Given no tasks exist - when findResumableTask called - returns empty")
    void testNoTasksFound() {
        StepAuditEntity.deleteAll();
        TaskRunEntity.deleteAll();
        Optional<TaskRunEntity> result = crashDetector.findResumableTask("non-existent-project");
        assertThat(result).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("Given active task with current process PID - when findResumableTask called - returns empty")
    void testActiveTaskWithRunningPid() {
        StepAuditEntity.deleteAll();
        TaskRunEntity.deleteAll();

        TaskRunEntity activeTask = new TaskRunEntity();
        activeTask.projectId = "active-project";
        activeTask.taskId = "TASK-ACTIVE-01";
        activeTask.status = TaskStatus.GREEN_PATCH;
        activeTask.currentPhase = "GREEN_PATCH";
        activeTask.pid = ProcessHandle.current().pid(); // Active running PID
        activeTask.createdAt = Instant.now();
        activeTask.updatedAt = Instant.now();
        activeTask.persist();

        Optional<TaskRunEntity> result = crashDetector.findResumableTask("active-project");
        assertThat(result).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("Given crashed task with null pid - when findResumableTask called - returns task")
    void testCrashedTaskWithNullPid() {
        StepAuditEntity.deleteAll();
        TaskRunEntity.deleteAll();

        TaskRunEntity task = new TaskRunEntity();
        task.projectId = "null-pid-project";
        task.taskId = "TASK-NULL-PID";
        task.status = TaskStatus.VERIFYING;
        task.currentPhase = "VERIFYING";
        task.pid = null;
        task.createdAt = Instant.now();
        task.updatedAt = Instant.now();
        task.persist();

        Optional<TaskRunEntity> result = crashDetector.findResumableTask("null-pid-project");
        assertThat(result).isPresent();
        assertThat(result.get().taskId).isEqualTo("TASK-NULL-PID");
    }

    @Test
    @Transactional
    @DisplayName("Given default findResumableTask called - resolves via projectResolver")
    void testDefaultFindResumableTask() {
        StepAuditEntity.deleteAll();
        TaskRunEntity.deleteAll();
        Optional<TaskRunEntity> result = crashDetector.findResumableTask();
        assertThat(result).isEmpty();
    }
}
