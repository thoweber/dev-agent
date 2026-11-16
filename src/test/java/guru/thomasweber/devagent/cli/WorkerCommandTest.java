package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.entity.TaskPersistenceService;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.spi.build.SanitizedLog;
import guru.thomasweber.devagent.testutil.TaskRunMother;
import guru.thomasweber.devagent.testutil.WorktreeStateMother;
import guru.thomasweber.devagent.workspace.BackendBuildService;
import guru.thomasweber.devagent.workspace.DesktopAlertService;
import guru.thomasweber.devagent.workspace.GitWorktreeService;
import guru.thomasweber.devagent.workspace.ProjectIdResolver;
import guru.thomasweber.devagent.workspace.StateSocketService;
import guru.thomasweber.devagent.workspace.StateSyncService;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import guru.thomasweber.devagent.workspace.WorktreeTaskExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given worktree without build system - when WorkerCommand executed - then emits COMPLETED heartbeat and desktop alert")
    void givenWorktreeWithoutBuildSystem_whenWorkerExecuted_thenCompletesAndNotifies() throws IOException {
        Path worktreeDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-801"));
        StateSocketService socketService = new StateSocketService();
        DesktopAlertService alertService = mock(DesktopAlertService.class);
        StateSyncService syncService = mock(StateSyncService.class);
        BackendBuildService buildService = mock(BackendBuildService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        when(persistenceService.saveTask(any(TaskRun.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkerCommand command = new WorkerCommand(
            socketService,
            new ProjectIdResolver(new WorkspaceContextResolver(tempDir)),
            new DevAgentConfigLoader(),
            new WorktreeTaskExecutor(socketService, syncService, buildService, alertService, persistenceService, mock(GitWorktreeService.class))
        );
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--dir=" + tempDir, "--task-id=TASK-801", "--instruction=Verify worker");

        assertThat(exitCode).isZero();
        Optional<WorktreeState> state = socketService.readState(tempDir, "TASK-801");
        assertThat(state).isPresent();
        assertThat(state.get().status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(state.get().worktreePath()).isEqualTo(worktreeDir.toString());
        verify(alertService).notifyTaskOutcome(eq(tempDir.toAbsolutePath().normalize()), any(WorktreeState.class));
    }

    @Test
    @DisplayName("Given worktree with failing Maven build - when WorkerCommand executed - then transitions to FAILED and returns exit code 1")
    void givenWorktreeWithFailingBuild_whenWorkerExecuted_thenMarksFailedAndReturnsOne() throws IOException {
        Path worktreeDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-802"));
        Files.writeString(worktreeDir.resolve("pom.xml"), "<project/>");

        StateSocketService socketService = new StateSocketService();
        DesktopAlertService alertService = mock(DesktopAlertService.class);
        StateSyncService syncService = mock(StateSyncService.class);
        BackendBuildService buildService = mock(BackendBuildService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        when(persistenceService.saveTask(any(TaskRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(buildService.hasApplicableBuildSystem(worktreeDir)).thenReturn(true);
        when(buildService.compile(worktreeDir, null, "target-agent"))
            .thenReturn(SanitizedLog.builder().exitCode(1).errorCount(1).build());

        WorkerCommand command = new WorkerCommand(
            socketService,
            new ProjectIdResolver(new WorkspaceContextResolver(tempDir)),
            new DevAgentConfigLoader(),
            new WorktreeTaskExecutor(socketService, syncService, buildService, alertService, persistenceService, mock(GitWorktreeService.class))
        );
        CommandLine cmd = new CommandLine(command);
        StringWriter err = new StringWriter();
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("--dir=" + tempDir, "--task-id=TASK-802", "--instruction=Failing compile");

        assertThat(exitCode).isEqualTo(1);
        Optional<WorktreeState> state = socketService.readState(tempDir, "TASK-802");
        assertThat(state).isPresent();
        assertThat(state.get().status()).isEqualTo(TaskStatus.FAILED);
        verify(alertService).notifyTaskOutcome(eq(tempDir.toAbsolutePath().normalize()), any(WorktreeState.class));
    }

    @Test
    @DisplayName("Given existing state, dev-agent.yaml, and passing Maven build - when WorkerCommand run() invoked - then transitions to COMPLETED")
    void givenExistingStateAndPassingBuild_whenRunInvoked_thenCompletesSuccessfully() throws IOException {
        Path worktreeDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-803"));
        Files.writeString(worktreeDir.resolve("pom.xml"), "<project/>");
        Files.createDirectories(tempDir.resolve(".agents"));
        Files.writeString(tempDir.resolve(".agents/dev-agent.yaml"), """
            agent:
              build:
                backend:
                  target-dir: "target-custom"
            """);

        StateSocketService socketService = new StateSocketService();
        socketService.writeHeartbeat(tempDir, WorktreeStateMother.queued("TASK-803"));
        DesktopAlertService alertService = mock(DesktopAlertService.class);
        StateSyncService syncService = mock(StateSyncService.class);
        BackendBuildService buildService = mock(BackendBuildService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        when(persistenceService.saveTask(any(TaskRun.class))).thenAnswer(inv -> inv.getArgument(0));
        when(buildService.hasApplicableBuildSystem(worktreeDir)).thenReturn(true);
        when(buildService.compile(worktreeDir, null, "target-custom"))
            .thenReturn(SanitizedLog.builder().exitCode(0).errorCount(0).build());

        WorkerCommand command = new WorkerCommand(
            socketService,
            new ProjectIdResolver(new WorkspaceContextResolver(tempDir)),
            new DevAgentConfigLoader(),
            new WorktreeTaskExecutor(socketService, syncService, buildService, alertService, persistenceService, mock(GitWorktreeService.class))
        );
        CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.parseArgs("--dir=" + tempDir, "--task-id=TASK-803", "--instruction=Compile ok");
        command.run();

        assertThat(command.getTaskId()).isEqualTo("TASK-803");
        assertThat(command.getInstruction()).isEqualTo("Compile ok");
        assertThat(socketService.readState(tempDir, "TASK-803"))
            .isPresent()
            .get()
            .extracting(WorktreeState::status)
            .isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("Given buildService throwing exception - when WorkerCommand executed - then records FAILED and returns 1")
    void givenBuildThrowsException_whenWorkerExecuted_thenRecordsFailedAndReturnsOne() throws IOException {
        Path worktreeDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-804"));
        Files.writeString(worktreeDir.resolve("pom.xml"), "<project/>");

        StateSocketService socketService = new StateSocketService();
        DesktopAlertService alertService = mock(DesktopAlertService.class);
        StateSyncService syncService = mock(StateSyncService.class);
        BackendBuildService buildService = mock(BackendBuildService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        when(buildService.hasApplicableBuildSystem(worktreeDir)).thenReturn(true);
        when(buildService.compile(worktreeDir, null, "target-agent"))
            .thenThrow(new IllegalStateException("build crash"));

        WorkerCommand command = new WorkerCommand(
            socketService,
            new ProjectIdResolver(new WorkspaceContextResolver(tempDir)),
            new DevAgentConfigLoader(),
            new WorktreeTaskExecutor(socketService, syncService, buildService, alertService, persistenceService, mock(GitWorktreeService.class))
        );
        CommandLine cmd = new CommandLine(command);
        StringWriter err = new StringWriter();
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("--dir=" + tempDir, "--task-id=TASK-804");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("build crash");
        assertThat(socketService.readState(tempDir, "TASK-804"))
            .isPresent()
            .get()
            .extracting(WorktreeState::status)
            .isEqualTo(TaskStatus.FAILED);
    }

    @Test
    @DisplayName("Given existing TaskRun in H2 and auto-prune enabled - when WorkerCommand completes - then preserves baseCommit and checkpointStash and prunes worktree")
    void givenExistingTaskRunAndAutoPrune_whenWorkerCompletes_thenPreservesMetadataAndPrunesWorktree() throws IOException {
        Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-805"));
        StateSocketService socketService = new StateSocketService();
        socketService.writeHeartbeat(tempDir, WorktreeStateMother.builder("TASK-805")
            .branch("feature/TASK-805-keep")
            .build());
        TaskRun existing = TaskRunMother.builder("TASK-805")
            .branch("feature/TASK-805-keep")
            .baseCommit("deadbeef123")
            .checkpointStash("stash123")
            .userStory("Existing story")
            .retryCount(2)
            .build();

        DesktopAlertService alertService = mock(DesktopAlertService.class);
        StateSyncService syncService = mock(StateSyncService.class);
        BackendBuildService buildService = mock(BackendBuildService.class);
        GitWorktreeService worktreeService = mock(GitWorktreeService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        when(persistenceService.findByProjectAndTask(any(String.class), eq("TASK-805"))).thenReturn(Optional.of(existing));
        when(persistenceService.saveTask(any(TaskRun.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkerCommand command = new WorkerCommand(
            socketService,
            new ProjectIdResolver(new WorkspaceContextResolver(tempDir)),
            new DevAgentConfigLoader(),
            new WorktreeTaskExecutor(socketService, syncService, buildService, alertService, persistenceService, worktreeService)
        );
        CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(new StringWriter()));

        int exitCode = cmd.execute("--dir=" + tempDir, "--task-id=TASK-805");

        assertThat(exitCode).isZero();
        ArgumentCaptor<TaskRun> savedCaptor = ArgumentCaptor.forClass(TaskRun.class);
        verify(persistenceService).saveTask(savedCaptor.capture());
        assertThat(savedCaptor.getValue().baseCommit()).isEqualTo("deadbeef123");
        assertThat(savedCaptor.getValue().checkpointStash()).isEqualTo("stash123");
        assertThat(savedCaptor.getValue().userStory()).isEqualTo("Existing story");
        assertThat(savedCaptor.getValue().retryCount()).isEqualTo(2);
        assertThat(savedCaptor.getValue().status()).isEqualTo(TaskStatus.COMPLETED);
        verify(worktreeService).removeWorktree(tempDir.toRealPath(), "TASK-805", false, "feature/TASK-805-keep");

        // Now verify auto-prune-on-complete=false preserves worktree and non-blank instruction updates userStory
        Files.createDirectories(tempDir.resolve(".agents"));
        Files.writeString(tempDir.resolve(".agents/dev-agent.yaml"), """
            agent:
              isolation:
                auto-prune-on-complete: false
            """);
        assertThat(cmd.execute("--dir=" + tempDir, "--task-id=TASK-805", "--instruction=Updated story")).isZero();
        verify(worktreeService).removeWorktree(tempDir.toRealPath(), "TASK-805", false, "feature/TASK-805-keep");
    }

    @Test
    @DisplayName("Given missing worktree directory - when WorkerCommand executed - then refuses execution on primary repo and returns exit code 1")
    void givenMissingWorktree_whenWorkerExecuted_thenReturnsFailure() {
        StateSocketService socketService = new StateSocketService();
        WorkerCommand command = new WorkerCommand(
            socketService,
            new ProjectIdResolver(new WorkspaceContextResolver(tempDir)),
            new DevAgentConfigLoader(),
            new WorktreeTaskExecutor(
                socketService,
                mock(StateSyncService.class),
                mock(BackendBuildService.class),
                mock(DesktopAlertService.class),
                mock(TaskPersistenceService.class),
                mock(GitWorktreeService.class)
            )
        );
        CommandLine cmd = new CommandLine(command);
        StringWriter err = new StringWriter();
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("--dir=" + tempDir, "--task-id=TASK-MISSING");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("Worktree directory does not exist");
        assertThat(socketService.readState(tempDir, "TASK-MISSING")).isEmpty();
    }
}
