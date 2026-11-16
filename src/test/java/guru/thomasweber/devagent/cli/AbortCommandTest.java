package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.entity.TaskPersistenceService;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.testutil.WorktreeStateMother;
import guru.thomasweber.devagent.workspace.GitWorktreeService;
import guru.thomasweber.devagent.workspace.ProjectIdResolver;
import guru.thomasweber.devagent.workspace.StateSocketService;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AbortCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given abort command - when executed with valid taskId - then outputs aborting message and exit code 0")
    void testAbortWithTaskId() {
        AbortCommand command = new AbortCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--dir=" + tempDir, "TASK-101");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Aborting background task: TASK-101");
        assertThat(command.getTaskId()).isEqualTo("TASK-101");
    }

    @Test
    @DisplayName("Given abort command - when executed without taskId - then outputs warning to specify taskId")
    void testAbortWithoutTaskId() {
        AbortCommand command = new AbortCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Please specify a Task ID to abort.");
    }

    @Test
    @DisplayName("Given abort command - when executed with blank taskId - then outputs warning to specify taskId")
    void testAbortWithBlankTaskId() {
        AbortCommand command = new AbortCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("   ");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Please specify a Task ID to abort.");
    }

    @Test
    @DisplayName("Given abort command - when executed with --help - then outputs abort subcommand usage and exit code 0")
    void testHelpOption() {
        AbortCommand command = new AbortCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains(
            "Usage: abort",
            "[<taskId>]",
            "-d, --dir, --workspace"
        );
    }

    @Test
    @DisplayName("Given abort command - when executed with --dir - then resolves workspace directory")
    void testDirectoryOption() {
        AbortCommand command = new AbortCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "TASK-202");

        assertThat(exitCode).isZero();
        assertThat(command.getTaskId()).isEqualTo("TASK-202");
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given active background process and state socket - when abort executed - then terminates process, removes worktree, and preserves branch by default")
    void testAbortTerminatesLiveProcessAndPreservesBranchByDefault() throws IOException {
        StateSocketService socketService = new StateSocketService();
        Process sleeper = new ProcessBuilder("sleep", "30").start();
        try {
            WorktreeState active = WorktreeStateMother.active("TASK-KILL-1", sleeper.pid())
                .toBuilder()
                .branch("feature/TASK-KILL-1-demo")
                .build();
            socketService.writeHeartbeat(tempDir, active);

            GitWorktreeService worktreeService = mock(GitWorktreeService.class);
            TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
            when(persistenceService.findByProjectAndTask(any(String.class), eq("TASK-KILL-1"))).thenReturn(Optional.empty());
            when(persistenceService.saveTask(any(TaskRun.class))).thenAnswer(inv -> inv.getArgument(0));

            AbortCommand command = new AbortCommand(
                socketService,
                worktreeService,
                persistenceService,
                new ProjectIdResolver(new WorkspaceContextResolver(tempDir))
            );
            CommandLine cmd = new CommandLine(command);
            StringWriter out = new StringWriter();
            cmd.setOut(new PrintWriter(out));

            int exitCode = cmd.execute("--dir=" + tempDir, "TASK-KILL-1");

            assertThat(exitCode).isZero();
            assertThat(command.isDeleteBranch()).isFalse();
            assertThat(sleeper.isAlive()).isFalse();
            verify(worktreeService).removeWorktree(tempDir.toRealPath(), "TASK-KILL-1", false, "feature/TASK-KILL-1-demo");
            assertThat(socketService.readState(tempDir, "TASK-KILL-1"))
                .isPresent()
                .get()
                .extracting(WorktreeState::status)
                .isEqualTo(TaskStatus.ABORTED);
        } finally {
            sleeper.destroyForcibly();
        }
    }

    @Test
    @DisplayName("Given --delete-branch flag - when abort executed - then requests branch deletion from GitWorktreeService")
    void testAbortWithDeleteBranchFlag() throws IOException {
        StateSocketService socketService = new StateSocketService();
        socketService.writeHeartbeat(tempDir, WorktreeStateMother.crashed("TASK-DEL-1").toBuilder()
            .branch("feature/TASK-DEL-1-old")
            .build());
        GitWorktreeService worktreeService = mock(GitWorktreeService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        when(persistenceService.findByProjectAndTask(any(String.class), eq("TASK-DEL-1"))).thenReturn(Optional.empty());

        AbortCommand command = new AbortCommand(
            socketService,
            worktreeService,
            persistenceService,
            new ProjectIdResolver(new WorkspaceContextResolver(tempDir))
        );
        CommandLine cmd = new CommandLine(command);
        int exitCode = cmd.execute("--dir=" + tempDir, "--delete-branch", "TASK-DEL-1");

        assertThat(exitCode).isZero();
        assertThat(command.isDeleteBranch()).isTrue();
        verify(worktreeService).removeWorktree(tempDir.toRealPath(), "TASK-DEL-1", true, "feature/TASK-DEL-1-old");
    }
}
