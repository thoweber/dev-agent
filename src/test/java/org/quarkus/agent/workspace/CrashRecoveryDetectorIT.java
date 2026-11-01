package org.quarkus.agent.workspace;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.entity.TaskPersistenceService;
import org.quarkus.agent.model.ProjectId;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStatus;
import org.quarkus.agent.testutil.TaskRunMother;
import org.quarkus.agent.testutil.WorktreeTestFixture;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CrashRecoveryDetectorIT {

    @Inject
    CrashRecoveryDetector crashDetector;

    @Inject
    TaskPersistenceService persistenceService;

    @Inject
    WorkspaceContextResolver workspaceResolver;

    private Path repoRoot() {
        return workspaceResolver.resolveRepoRoot();
    }

    private void createWorktree(String taskId) throws IOException {
        WorktreeTestFixture.createWorktree(repoRoot(), taskId);
    }

    private void deleteWorktree(String taskId) throws IOException {
        WorktreeTestFixture.deleteWorktree(repoRoot(), taskId);
    }

    @BeforeEach
    void setUp() throws IOException {
        persistenceService.deleteAll();
        WorktreeTestFixture.cleanAllWorktrees(repoRoot());
    }

    @AfterEach
    void tearDown() throws IOException {
        WorktreeTestFixture.cleanAllWorktrees(repoRoot());
    }

    @Test
    @DisplayName("Given crashed task with dead PID and intact worktree - when findResumableTask called - then identifies task for recovery")
    void testDetectCrashedTask() throws IOException {
        createWorktree("TASK-CRASH-01");

        TaskRun crashedTask = TaskRunMother.crashed("test-project-crash", "TASK-CRASH-01", TaskStatus.RED_TEST);
        persistenceService.saveTask(crashedTask);

        Optional<TaskRun> result = crashDetector.findResumableTask("test-project-crash");

        assertThat(result).isPresent();
        assertThat(result.get().taskId()).isEqualTo("TASK-CRASH-01");
        assertThat(result.get().status()).isEqualTo(TaskStatus.RED_TEST);
    }

    @Test
    @DisplayName("Given crashed task with dead PID but missing worktree - when findResumableTask called - returns empty")
    void testIgnoreCrashedTaskWithMissingWorktree() throws IOException {
        deleteWorktree("TASK-NO-WORKTREE");

        TaskRun task = TaskRunMother.crashed("test-project-crash", "TASK-NO-WORKTREE", TaskStatus.RED_TEST);
        persistenceService.saveTask(task);

        Optional<TaskRun> result = crashDetector.findResumableTask("test-project-crash");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Given no tasks exist - when findResumableTask called - returns empty")
    void testNoTasksFound() {
        Optional<TaskRun> result = crashDetector.findResumableTask("non-existent-project");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Given active task with current process PID - when findResumableTask called - returns empty")
    void testActiveTaskWithRunningPid() throws IOException {
        createWorktree("TASK-ACTIVE-01");

        TaskRun activeTask = TaskRunMother.active("TASK-ACTIVE-01")
            .toBuilder()
            .projectId("active-project")
            .build();
        persistenceService.saveTask(activeTask);

        Optional<TaskRun> result = crashDetector.findResumableTask("active-project");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Given multiple tasks - when findAllResumableTasks called - returns all crashed tasks with intact worktrees in descending order")
    void testFindAllResumableTasks() throws IOException {
        createWorktree("TASK-CRASH-A");
        createWorktree("TASK-CRASH-B");
        createWorktree("TASK-RUNNING");
        deleteWorktree("TASK-PRUNED");

        Instant now = Instant.now();

        // Older crashed task
        TaskRun taskA = TaskRunMother.builder("TASK-CRASH-A")
            .projectId("multi-project")
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .pid(TaskRunMother.DEAD_PID - 1)
            .createdAt(now.minusSeconds(100))
            .updatedAt(now.minusSeconds(80))
            .build();
        persistenceService.saveTask(taskA);

        // Newer crashed task
        TaskRun taskB = TaskRunMother.builder("TASK-CRASH-B")
            .projectId("multi-project")
            .status(TaskStatus.RED_TEST)
            .currentPhase("RED_TEST")
            .pid(TaskRunMother.DEAD_PID)
            .createdAt(now.minusSeconds(50))
            .updatedAt(now.minusSeconds(20))
            .build();
        persistenceService.saveTask(taskB);

        // Running task
        TaskRun taskRunning = TaskRunMother.active("TASK-RUNNING")
            .toBuilder()
            .projectId("multi-project")
            .createdAt(now.minusSeconds(10))
            .updatedAt(now)
            .build();
        persistenceService.saveTask(taskRunning);

        // Task with missing worktree
        TaskRun taskPruned = TaskRunMother.builder("TASK-PRUNED")
            .projectId("multi-project")
            .status(TaskStatus.VERIFYING)
            .currentPhase("VERIFYING")
            .pid(TaskRunMother.DEAD_PID - 2)
            .createdAt(now.minusSeconds(30))
            .updatedAt(now.minusSeconds(10))
            .build();
        persistenceService.saveTask(taskPruned);

        List<TaskRun> resumable = crashDetector.findAllResumableTasks("multi-project");

        assertThat(resumable)
            .hasSize(2)
            .extracting(TaskRun::taskId)
            .containsExactly("TASK-CRASH-B", "TASK-CRASH-A"); // Ordered by updatedAt desc
    }

    @Test
    @DisplayName("Given target taskId - when findResumableTaskById called - evaluates specific task eligibility")
    void testFindResumableTaskById() throws IOException {
        createWorktree("TASK-TARGET");
        deleteWorktree("TASK-NO-DIR");

        ProjectId projectId = ProjectId.fromCoordinate("target-project");

        TaskRun target = TaskRunMother.crashed(projectId.idString(), "TASK-TARGET", TaskStatus.GREEN_PATCH);
        persistenceService.saveTask(target);

        TaskRun noDir = TaskRunMother.crashed(projectId.idString(), "TASK-NO-DIR", TaskStatus.GREEN_PATCH);
        persistenceService.saveTask(noDir);

        Optional<TaskRun> found = crashDetector.findResumableTaskById(projectId, "TASK-TARGET");
        assertThat(found).isPresent();
        assertThat(found.get().taskId()).isEqualTo("TASK-TARGET");

        Optional<TaskRun> missingDir = crashDetector.findResumableTaskById(projectId, "TASK-NO-DIR");
        assertThat(missingDir).isEmpty();

        Optional<TaskRun> nonExistent = crashDetector.findResumableTaskById(projectId, "TASK-UNKNOWN");
        assertThat(nonExistent).isEmpty();
    }

    @Test
    @DisplayName("Given active task and older crashed task - when findResumableTask called - skips active and recovers older crashed task")
    void testActiveTaskDoesNotMaskOlderCrashedTask() throws IOException {
        createWorktree("TASK-OLDER-CRASHED");
        createWorktree("TASK-NEWER-ACTIVE");

        // Older crashed task
        TaskRun crashedTask = TaskRunMother.builder("TASK-OLDER-CRASHED")
            .projectId("concurrent-project")
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .pid(TaskRunMother.DEAD_PID)
            .createdAt(Instant.now().minusSeconds(120))
            .updatedAt(Instant.now().minusSeconds(60))
            .build();
        persistenceService.saveTask(crashedTask);

        // Newer active running task
        TaskRun activeTask = TaskRunMother.active("TASK-NEWER-ACTIVE")
            .toBuilder()
            .projectId("concurrent-project")
            .createdAt(Instant.now().minusSeconds(30))
            .updatedAt(Instant.now())
            .build();
        persistenceService.saveTask(activeTask);

        Optional<TaskRun> result = crashDetector.findResumableTask("concurrent-project");
        assertThat(result).isPresent();
        assertThat(result.get().taskId()).isEqualTo("TASK-OLDER-CRASHED");
    }

    @Test
    @DisplayName("Given crashed task with null pid - when findResumableTask called - returns task")
    void testCrashedTaskWithNullPid() throws IOException {
        createWorktree("TASK-NULL-PID");

        TaskRun task = TaskRunMother.builder("TASK-NULL-PID")
            .projectId("null-pid-project")
            .status(TaskStatus.VERIFYING)
            .currentPhase("VERIFYING")
            .pid(null)
            .build();
        persistenceService.saveTask(task);

        Optional<TaskRun> result = crashDetector.findResumableTask("null-pid-project");
        assertThat(result).isPresent();
        assertThat(result.get().taskId()).isEqualTo("TASK-NULL-PID");
    }

    @Test
    @DisplayName("Given default findResumableTask called - resolves via projectResolver")
    void testDefaultFindResumableTask() {
        Optional<TaskRun> result = crashDetector.findResumableTask();
        assertThat(result).isEmpty();
    }
}
