package org.quarkus.agent.workspace;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.entity.TaskPersistenceService;
import org.quarkus.agent.model.ProjectId;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class CrashRecoveryDetectorTest {

    @Inject
    CrashRecoveryDetector crashDetector;

    @Inject
    TaskPersistenceService persistenceService;

    @Inject
    WorkspaceContextResolver workspaceResolver;

    private void createWorktree(String taskId) throws IOException {
        Path worktreeDir = workspaceResolver.resolveRepoRoot().resolve(".git").resolve("dev-agent-worktrees").resolve(taskId);
        Files.createDirectories(worktreeDir);
    }

    private void deleteWorktree(String taskId) throws IOException {
        Path worktreeDir = workspaceResolver.resolveRepoRoot().resolve(".git").resolve("dev-agent-worktrees").resolve(taskId);
        if (Files.exists(worktreeDir)) {
            Files.delete(worktreeDir);
        }
    }

    @BeforeEach
    @Transactional
    void setUp() {
        persistenceService.deleteAll();
    }

    @Test
    @Transactional
    @DisplayName("Given crashed task with dead PID and intact worktree - when findResumableTask called - then identifies task for recovery")
    void testDetectCrashedTask() throws IOException {
        persistenceService.deleteAll();
        createWorktree("TASK-CRASH-01");

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
    @DisplayName("Given crashed task with dead PID but missing worktree - when findResumableTask called - returns empty")
    void testIgnoreCrashedTaskWithMissingWorktree() throws IOException {
        persistenceService.deleteAll();
        deleteWorktree("TASK-NO-WORKTREE");

        TaskRun task = TaskRun.builder()
            .projectId("test-project-crash")
            .taskId("TASK-NO-WORKTREE")
            .status(TaskStatus.RED_TEST)
            .currentPhase("RED_TEST")
            .pid(999999999L)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        persistenceService.saveTask(task);

        Optional<TaskRun> result = crashDetector.findResumableTask("test-project-crash");
        assertThat(result).isEmpty();
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
    void testActiveTaskWithRunningPid() throws IOException {
        persistenceService.deleteAll();
        createWorktree("TASK-ACTIVE-01");

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
    @DisplayName("Given multiple tasks - when findAllResumableTasks called - returns all crashed tasks with intact worktrees in descending order")
    void testFindAllResumableTasks() throws IOException {
        persistenceService.deleteAll();
        createWorktree("TASK-CRASH-A");
        createWorktree("TASK-CRASH-B");
        createWorktree("TASK-RUNNING");
        deleteWorktree("TASK-PRUNED");

        Instant now = Instant.now();

        // Older crashed task
        TaskRun taskA = TaskRun.builder()
            .projectId("multi-project")
            .taskId("TASK-CRASH-A")
            .status(TaskStatus.PLANNING)
            .currentPhase("PLANNING")
            .pid(999999998L)
            .createdAt(now.minusSeconds(100))
            .updatedAt(now.minusSeconds(80))
            .build();
        persistenceService.saveTask(taskA);

        // Newer crashed task
        TaskRun taskB = TaskRun.builder()
            .projectId("multi-project")
            .taskId("TASK-CRASH-B")
            .status(TaskStatus.RED_TEST)
            .currentPhase("RED_TEST")
            .pid(999999999L)
            .createdAt(now.minusSeconds(50))
            .updatedAt(now.minusSeconds(20))
            .build();
        persistenceService.saveTask(taskB);

        // Running task
        TaskRun taskRunning = TaskRun.builder()
            .projectId("multi-project")
            .taskId("TASK-RUNNING")
            .status(TaskStatus.GREEN_PATCH)
            .currentPhase("GREEN_PATCH")
            .pid(ProcessHandle.current().pid())
            .createdAt(now.minusSeconds(10))
            .updatedAt(now)
            .build();
        persistenceService.saveTask(taskRunning);

        // Task with missing worktree
        TaskRun taskPruned = TaskRun.builder()
            .projectId("multi-project")
            .taskId("TASK-PRUNED")
            .status(TaskStatus.VERIFYING)
            .currentPhase("VERIFYING")
            .pid(999999997L)
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
    @Transactional
    @DisplayName("Given target taskId - when findResumableTaskById called - evaluates specific task eligibility")
    void testFindResumableTaskById() throws IOException {
        persistenceService.deleteAll();
        createWorktree("TASK-TARGET");
        deleteWorktree("TASK-NO-DIR");

        ProjectId projectId = ProjectId.fromCoordinate("target-project");

        TaskRun target = TaskRun.builder()
            .projectId(projectId.idString())
            .taskId("TASK-TARGET")
            .status(TaskStatus.GREEN_PATCH)
            .currentPhase("GREEN_PATCH")
            .pid(999999999L)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        persistenceService.saveTask(target);

        TaskRun noDir = TaskRun.builder()
            .projectId(projectId.idString())
            .taskId("TASK-NO-DIR")
            .status(TaskStatus.GREEN_PATCH)
            .currentPhase("GREEN_PATCH")
            .pid(999999999L)
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
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
    @Transactional
    @DisplayName("Given active task and older crashed task - when findResumableTask called - skips active and recovers older crashed task")
    void testActiveTaskDoesNotMaskOlderCrashedTask() throws IOException {
        persistenceService.deleteAll();
        createWorktree("TASK-OLDER-CRASHED");
        createWorktree("TASK-NEWER-ACTIVE");

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
    void testCrashedTaskWithNullPid() throws IOException {
        persistenceService.deleteAll();
        createWorktree("TASK-NULL-PID");

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
