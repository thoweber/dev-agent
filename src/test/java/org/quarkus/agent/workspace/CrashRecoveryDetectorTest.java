package org.quarkus.agent.workspace;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.entity.TaskPersistenceService;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStatus;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CrashRecoveryDetectorTest {

    @Inject
    CrashRecoveryDetector crashDetector;

    @Inject
    TaskPersistenceService persistenceService;

    @BeforeEach
    @Transactional
    void setUp() {
        persistenceService.deleteAll();
    }

    @Test
    @Transactional
    @DisplayName("Given crashed task with dead PID - when findResumableTask called - then identifies task for recovery")
    void testDetectCrashedTask() {
        persistenceService.deleteAll();

        TaskRun crashedTask = TaskRun.builder()
            .projectId("test-project-crash")
            .taskId("TASK-CRASH-01")
            .status(TaskStatus.RED_TEST)
            .currentPhase("RED_TEST")
            .pid(999999999L) // Non-existent dead PID
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        persistenceService.saveTask(crashedTask);

        Optional<TaskRun> result = crashDetector.findResumableTask("test-project-crash");

        assertThat(result).isPresent();
        assertThat(result.get().taskId()).isEqualTo("TASK-CRASH-01");
        assertThat(result.get().status()).isEqualTo(TaskStatus.RED_TEST);
    }

    @Test
    @Transactional
    @DisplayName("Given no tasks exist - when findResumableTask called - returns empty")
    void testNoTasksFound() {
        persistenceService.deleteAll();
        Optional<TaskRun> result = crashDetector.findResumableTask("non-existent-project");
        assertThat(result).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("Given active task with current process PID - when findResumableTask called - returns empty")
    void testActiveTaskWithRunningPid() {
        persistenceService.deleteAll();

        TaskRun activeTask = TaskRun.builder()
            .projectId("active-project")
            .taskId("TASK-ACTIVE-01")
            .status(TaskStatus.GREEN_PATCH)
            .currentPhase("GREEN_PATCH")
            .pid(ProcessHandle.current().pid()) // Active running PID
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        persistenceService.saveTask(activeTask);

        Optional<TaskRun> result = crashDetector.findResumableTask("active-project");
        assertThat(result).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("Given active task and older crashed task - when findResumableTask called - skips active and recovers older crashed task")
    void testActiveTaskDoesNotMaskOlderCrashedTask() {
        persistenceService.deleteAll();

        // Older crashed task
        TaskRun crashedTask = TaskRun.builder()
            .projectId("concurrent-project")
            .taskId("TASK-OLDER-CRASHED")
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .pid(999999999L) // Dead PID
            .createdAt(Instant.now().minusSeconds(120))
            .updatedAt(Instant.now().minusSeconds(60))
            .build();
        persistenceService.saveTask(crashedTask);

        // Newer active running task
        TaskRun activeTask = TaskRun.builder()
            .projectId("concurrent-project")
            .taskId("TASK-NEWER-ACTIVE")
            .status(TaskStatus.GREEN_PATCH)
            .currentPhase("GREEN_PATCH")
            .pid(ProcessHandle.current().pid()) // Active PID
            .createdAt(Instant.now().minusSeconds(30))
            .updatedAt(Instant.now())
            .build();
        persistenceService.saveTask(activeTask);

        Optional<TaskRun> result = crashDetector.findResumableTask("concurrent-project");
        assertThat(result).isPresent();
        assertThat(result.get().taskId()).isEqualTo("TASK-OLDER-CRASHED");
    }

    @Test
    @Transactional
    @DisplayName("Given crashed task with null pid - when findResumableTask called - returns task")
    void testCrashedTaskWithNullPid() {
        persistenceService.deleteAll();

        TaskRun task = TaskRun.builder()
            .projectId("null-pid-project")
            .taskId("TASK-NULL-PID")
            .status(TaskStatus.VERIFYING)
            .currentPhase("VERIFYING")
            .pid(null)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        persistenceService.saveTask(task);

        Optional<TaskRun> result = crashDetector.findResumableTask("null-pid-project");
        assertThat(result).isPresent();
        assertThat(result.get().taskId()).isEqualTo("TASK-NULL-PID");
    }

    @Test
    @Transactional
    @DisplayName("Given default findResumableTask called - resolves via projectResolver")
    void testDefaultFindResumableTask() {
        persistenceService.deleteAll();
        Optional<TaskRun> result = crashDetector.findResumableTask();
        assertThat(result).isEmpty();
    }
}
