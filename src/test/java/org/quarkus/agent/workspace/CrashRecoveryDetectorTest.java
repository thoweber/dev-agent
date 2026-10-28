package org.quarkus.agent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.quarkus.agent.entity.TaskPersistenceService;
import org.quarkus.agent.model.ProjectId;
import org.quarkus.agent.model.TaskRun;
import org.quarkus.agent.model.TaskStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CrashRecoveryDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given null or terminal tasks - isTaskResumable returns false")
    void testNonResumableTerminalOrNull() {
        CrashRecoveryDetector detector = new CrashRecoveryDetector();

        assertThat(detector.isTaskResumable(null)).isFalse();

        TaskRun nullStatus = TaskRun.builder().taskId("TASK-1").build();
        assertThat(detector.isTaskResumable(nullStatus)).isFalse();

        for (TaskStatus terminalStatus : List.of(TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.ABORTED)) {
            TaskRun terminalTask = TaskRun.builder().taskId("TASK-1").status(terminalStatus).build();
            assertThat(detector.isTaskResumable(terminalTask)).isFalse();
        }
    }

    @Test
    @DisplayName("Given task with alive process - isTaskResumable returns false")
    void testTaskWithActivePidNotResumable() {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        CrashRecoveryDetector detector = new CrashRecoveryDetector(new ProjectIdResolver(workspace), new TaskPersistenceService(), workspace);

        long currentPid = ProcessHandle.current().pid();
        TaskRun task = TaskRun.builder()
            .taskId("TASK-ALIVE")
            .status(TaskStatus.GREEN_PATCH)
            .pid(currentPid)
            .build();

        assertThat(detector.isTaskResumable(task)).isFalse();
    }

    @Test
    @DisplayName("Given dead process with existing worktree - isTaskResumable returns true")
    void testDeadProcessWithWorktreeIsResumable() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        CrashRecoveryDetector detector = new CrashRecoveryDetector(new ProjectIdResolver(workspace), new TaskPersistenceService(), workspace);

        Path worktree = workspace.resolveWorktreeDir("TASK-DEAD");
        Files.createDirectories(worktree);

        TaskRun task = TaskRun.builder()
            .taskId("TASK-DEAD")
            .status(TaskStatus.GREEN_PATCH)
            .pid(999999999L) // Highly unlikely to exist
            .build();

        assertThat(detector.isTaskResumable(task)).isTrue();
    }

    @Test
    @DisplayName("Given dead process without worktree - isTaskResumable returns false")
    void testDeadProcessWithoutWorktreeNotResumable() {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        CrashRecoveryDetector detector = new CrashRecoveryDetector(new ProjectIdResolver(workspace), new TaskPersistenceService(), workspace);

        TaskRun task = TaskRun.builder()
            .taskId("TASK-NO-WORKTREE")
            .status(TaskStatus.GREEN_PATCH)
            .pid(999999999L)
            .build();

        assertThat(detector.isTaskResumable(task)).isFalse();
    }

    @Test
    @DisplayName("Given candidate list from persistence - filters strictly resumable tasks")
    void testFindAllResumableTasksFiltering() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        Path worktree = workspace.resolveWorktreeDir("TASK-RESUMABLE");
        Files.createDirectories(worktree);

        TaskRun resumable = TaskRun.builder()
            .taskId("TASK-RESUMABLE")
            .status(TaskStatus.GREEN_PATCH)
            .pid(999999999L)
            .build();

        TaskRun terminal = TaskRun.builder()
            .taskId("TASK-COMPLETED")
            .status(TaskStatus.COMPLETED)
            .build();

        TaskRun noWorktree = TaskRun.builder()
            .taskId("TASK-GHOST")
            .status(TaskStatus.RED_TEST)
            .pid(999999999L)
            .build();

        TaskPersistenceService mockPersistence = new TaskPersistenceService() {
            @Override
            public List<TaskRun> findActiveCandidates(String projectId) {
                return List.of(resumable, terminal, noWorktree);
            }

            @Override
            public Optional<TaskRun> findByProjectAndTask(String projectId, String taskId) {
                if ("TASK-RESUMABLE".equals(taskId)) {
                    return Optional.of(resumable);
                }
                return Optional.empty();
            }
        };

        ProjectIdResolver projectResolver = new ProjectIdResolver(workspace);
        CrashRecoveryDetector detector = new CrashRecoveryDetector(projectResolver, mockPersistence, workspace);

        ProjectId projectId = ProjectId.fromCoordinate("github.com/org/repo");
        List<TaskRun> results = detector.findAllResumableTasks(projectId);

        assertThat(results).hasSize(1).containsExactly(resumable);
        assertThat(detector.findAllResumableTasks()).hasSize(1).containsExactly(resumable);

        Optional<TaskRun> found = detector.findResumableTaskById(projectId, "TASK-RESUMABLE");
        assertThat(found).isPresent().contains(resumable);

        Optional<TaskRun> notFound = detector.findResumableTaskById(projectId, "TASK-UNKNOWN");
        assertThat(notFound).isEmpty();

        assertThat(detector.resolveWorktreeDir("TASK-RESUMABLE")).isEqualTo(worktree);

        // Test findFirst overloads
        assertThat(detector.findResumableTask()).isPresent().contains(resumable);
        assertThat(detector.findResumableTask(projectId)).isPresent().contains(resumable);
        assertThat(detector.findResumableTask(projectId.idString())).isPresent().contains(resumable);
    }

    @Test
    @DisplayName("Given task with null pid but existing worktree - isTaskResumable returns true")
    void testTaskWithNullPidAndWorktreeIsResumable() throws IOException {
        WorkspaceContextResolver workspace = new WorkspaceContextResolver(tempDir);
        CrashRecoveryDetector detector = new CrashRecoveryDetector(new ProjectIdResolver(workspace), new TaskPersistenceService(), workspace);

        Path worktree = workspace.resolveWorktreeDir("TASK-NULL-PID");
        Files.createDirectories(worktree);

        TaskRun task = TaskRun.builder()
            .taskId("TASK-NULL-PID")
            .status(TaskStatus.PLANNING)
            .pid(null)
            .build();

        assertThat(detector.isTaskResumable(task)).isTrue();
    }

    @Test
    @DisplayName("Given default constructor - initializes dependencies")
    void testDefaultConstructor() {
        CrashRecoveryDetector detector = new CrashRecoveryDetector();
        assertThat(detector).isNotNull();
    }
}
