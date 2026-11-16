package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.entity.TaskPersistenceService;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeAllocation;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.spi.build.SanitizedLog;
import guru.thomasweber.devagent.testutil.TaskRunMother;
import guru.thomasweber.devagent.testutil.WorktreeStateMother;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorktreeTaskExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given worktree with passing build and existing TaskRun - when executeInWorktree called - then merges metadata, notifies, and prunes worktree")
    void givenPassingBuildAndExistingRun_whenExecuted_thenMergesNotifiesAndPrunes() throws IOException {
        Path worktreeDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-EX-1"));
        Files.writeString(worktreeDir.resolve("pom.xml"), "<project/>");
        StateSocketService socketService = new StateSocketService();
        StateSyncService syncService = mock(StateSyncService.class);
        BackendBuildService buildService = mock(BackendBuildService.class);
        DesktopAlertService alertService = mock(DesktopAlertService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        GitWorktreeService worktreeService = mock(GitWorktreeService.class);

        when(buildService.hasApplicableBuildSystem(worktreeDir)).thenReturn(true);
        when(buildService.compile(worktreeDir, null, "target-custom"))
            .thenReturn(SanitizedLog.builder().exitCode(0).errorCount(0).build());
        TaskRun existing = TaskRunMother.builder("TASK-EX-1")
            .branch("feature/TASK-EX-1-demo")
            .baseCommit(null)
            .checkpointStash(null)
            .userStory("Original story")
            .retryCount(3)
            .build();
        when(persistenceService.findByProjectAndTask("proj-1", "TASK-EX-1")).thenReturn(Optional.of(existing));
        when(persistenceService.saveTask(any(TaskRun.class))).thenAnswer(inv -> inv.getArgument(0));

        WorktreeTaskExecutor executor = new WorktreeTaskExecutor(
            socketService,
            syncService,
            buildService,
            alertService,
            persistenceService,
            worktreeService
        );
        WorktreeState initial = WorktreeStateMother.builder("TASK-EX-1")
            .projectId("proj-1")
            .branch("feature/TASK-EX-1-demo")
            .build();
        WorktreeAllocation allocation = WorktreeAllocation.builder()
            .taskId("TASK-EX-1")
            .branch("feature/TASK-EX-1-demo")
            .worktreePath(worktreeDir)
            .baseCommit("commit-1")
            .checkpointStash("stash-1")
            .hydrated(true)
            .build();

        WorktreeState result = executor.executeInWorktree(
            WorktreeTaskExecutor.ExecutionRequest.builder()
                .repoRoot(tempDir)
                .executionRoot(worktreeDir)
                .initialState(initial)
                .allocation(allocation)
                .instruction("  ")
                .targetDir(TargetDir.of("target-custom"))
                .autoPruneOnComplete(true)
                .build()
        );

        assertThat(result.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(socketService.readState(tempDir, "TASK-EX-1"))
            .isPresent()
            .get()
            .extracting(WorktreeState::status)
            .isEqualTo(TaskStatus.COMPLETED);

        ArgumentCaptor<TaskRun> savedCaptor = ArgumentCaptor.forClass(TaskRun.class);
        verify(persistenceService).saveTask(savedCaptor.capture());
        assertThat(savedCaptor.getValue().baseCommit()).isEqualTo("commit-1");
        assertThat(savedCaptor.getValue().checkpointStash()).isEqualTo("stash-1");
        assertThat(savedCaptor.getValue().userStory()).isEqualTo("Original story");
        assertThat(savedCaptor.getValue().retryCount()).isEqualTo(3);

        verify(syncService).projectToMarkdown(savedCaptor.getValue());
        verify(alertService).notifyTaskOutcome(tempDir, result);
        verify(worktreeService).removeWorktree(tempDir, "TASK-EX-1", false, "feature/TASK-EX-1-demo");
    }

    @Test
    @DisplayName("Given failing build or exception - when executeInWorktree called - then marks FAILED, prints error, and retains worktree")
    void givenFailingBuildOrException_whenExecuted_thenMarksFailedAndRetainsWorktree() throws IOException {
        Path worktreeDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-EX-2"));
        Files.writeString(worktreeDir.resolve("build.gradle"), "// gradle");
        StateSocketService socketService = new StateSocketService();
        StateSyncService syncService = mock(StateSyncService.class);
        BackendBuildService buildService = mock(BackendBuildService.class);
        DesktopAlertService alertService = mock(DesktopAlertService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        GitWorktreeService worktreeService = mock(GitWorktreeService.class);

        when(buildService.hasApplicableBuildSystem(worktreeDir)).thenReturn(true);
        when(buildService.compile(worktreeDir, null, "target-agent"))
            .thenReturn(SanitizedLog.builder().exitCode(1).errorCount(2).build())
            .thenThrow(new IllegalStateException("compile exploded token=ghp_1234567890abcdefghijklmnopqrstuvwxyz1234"));
        when(persistenceService.findByProjectAndTask("proj-1", "TASK-EX-2")).thenReturn(Optional.empty());
        when(persistenceService.saveTask(any(TaskRun.class))).thenThrow(new IllegalStateException("db offline"));

        WorktreeTaskExecutor executor = new WorktreeTaskExecutor(
            socketService,
            syncService,
            buildService,
            alertService,
            persistenceService,
            worktreeService
        );
        WorktreeState initial = WorktreeStateMother.builder("TASK-EX-2")
            .projectId("proj-1")
            .branch("feature/TASK-EX-2-fail")
            .build();
        StringWriter errWriter = new StringWriter();
        PrintWriter err = new PrintWriter(errWriter);

        WorktreeState failedCompile = executor.executeInWorktree(
            WorktreeTaskExecutor.ExecutionRequest.builder()
                .repoRoot(tempDir)
                .executionRoot(worktreeDir)
                .initialState(initial)
                .instruction("Build check")
                .autoPruneOnComplete(true)
                .err(err)
                .build()
        );
        assertThat(failedCompile.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(failedCompile.lastMessage()).contains("exit code 1");

        WorktreeState failedException = executor.executeInWorktree(
            WorktreeTaskExecutor.ExecutionRequest.builder()
                .repoRoot(tempDir)
                .executionRoot(worktreeDir)
                .initialState(initial)
                .instruction("Build check")
                .targetDir(TargetDir.DEFAULT)
                .autoPruneOnComplete(true)
                .err(err)
                .build()
        );
        assertThat(failedException.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(failedException.lastMessage()).contains("compile exploded");
        assertThat(errWriter.toString())
            .contains("compile exploded")
            .doesNotContain("ghp_1234567890abcdefghijklmnopqrstuvwxyz1234");
        verifyNoInteractions(worktreeService);
    }

    @Test
    @DisplayName("Given default executor construction - when constructed - then initializes cleanly")
    void givenDefaultConstructor_whenConstructed_thenInitializes() {
        assertThat(new WorktreeTaskExecutor()).isNotNull();
    }

    @Test
    @DisplayName("Given custom SPI build system without pom.xml and error-flagged log with zero exit code - fails verification cleanly")
    void givenCustomSpiBuildSystemWithErrors_whenExecuted_thenFailsVerification() throws IOException {
        Path worktreeDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-SPI-1"));
        StateSocketService socketService = new StateSocketService();
        StateSyncService syncService = mock(StateSyncService.class);
        BackendBuildService buildService = mock(BackendBuildService.class);
        DesktopAlertService alertService = mock(DesktopAlertService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        GitWorktreeService worktreeService = mock(GitWorktreeService.class);

        when(buildService.hasApplicableBuildSystem(worktreeDir)).thenReturn(true);
        when(buildService.compile(worktreeDir, null, TargetDir.DEFAULT_VALUE))
            .thenReturn(SanitizedLog.builder().exitCode(0).errorCount(1).build())
            .thenReturn(null);

        WorktreeTaskExecutor executor = new WorktreeTaskExecutor(
            socketService,
            syncService,
            buildService,
            alertService,
            persistenceService,
            worktreeService
        );
        WorktreeState initial = WorktreeStateMother.builder("TASK-SPI-1")
            .projectId("proj-spi")
            .branch("feature/TASK-SPI-1-custom")
            .build();

        WorktreeState failedByErrors = executor.executeInWorktree(
            WorktreeTaskExecutor.ExecutionRequest.builder()
                .repoRoot(tempDir)
                .executionRoot(worktreeDir)
                .initialState(initial)
                .instruction("Custom SPI compile")
                .targetDir(TargetDir.DEFAULT)
                .autoPruneOnComplete(false)
                .build()
        );
        assertThat(failedByErrors.status()).isEqualTo(TaskStatus.FAILED);

        WorktreeState nullLogPasses = executor.executeInWorktree(
            WorktreeTaskExecutor.ExecutionRequest.builder()
                .repoRoot(tempDir)
                .executionRoot(worktreeDir)
                .initialState(initial)
                .instruction("Custom SPI null log")
                .targetDir(TargetDir.DEFAULT)
                .autoPruneOnComplete(false)
                .build()
        );
        assertThat(nullLogPasses.status()).isEqualTo(TaskStatus.COMPLETED);

        // Merge with existing baseCommit/checkpointStash + explicit instruction, and createNewRun with allocation
        TaskRun populatedExisting = TaskRunMother.builder("TASK-SPI-1")
            .baseCommit("existing-base")
            .checkpointStash("existing-stash")
            .userStory("Old story")
            .build();
        when(persistenceService.findByProjectAndTask("proj-spi", "TASK-SPI-1"))
            .thenReturn(Optional.of(populatedExisting))
            .thenReturn(Optional.empty());
        when(persistenceService.saveTask(any(TaskRun.class))).thenReturn(null);

        WorktreeAllocation spiAllocation = WorktreeAllocation.builder()
            .taskId("TASK-SPI-1")
            .branch("feature/TASK-SPI-1-alloc")
            .worktreePath(worktreeDir)
            .baseCommit("alloc-base")
            .checkpointStash("alloc-stash")
            .hydrated(true)
            .build();
        executor.executeInWorktree(
            WorktreeTaskExecutor.ExecutionRequest.builder()
                .repoRoot(tempDir)
                .executionRoot(worktreeDir)
                .initialState(initial)
                .allocation(spiAllocation)
                .instruction("Override story")
                .targetDir(TargetDir.DEFAULT)
                .autoPruneOnComplete(false)
                .build()
        );
        executor.persistTaskRun(initial, spiAllocation, "New run story");
    }
}
