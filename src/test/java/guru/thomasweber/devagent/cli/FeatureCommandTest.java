package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.entity.TaskPersistenceService;
import guru.thomasweber.devagent.model.TaskRun;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeAllocation;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.spi.build.SanitizedLog;
import guru.thomasweber.devagent.workspace.BackendBuildService;
import guru.thomasweber.devagent.workspace.DesktopAlertService;
import guru.thomasweber.devagent.workspace.DetachedWorkerLauncher;
import guru.thomasweber.devagent.workspace.GitWorktreeService;
import guru.thomasweber.devagent.workspace.LocalWorkerLimiter;
import guru.thomasweber.devagent.workspace.ProjectIdResolver;
import guru.thomasweber.devagent.workspace.StateSocketService;
import guru.thomasweber.devagent.workspace.StateSyncService;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import guru.thomasweber.devagent.workspace.WorktreeTaskExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeatureCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given feature command with instruction - when executed - then outputs initiating execution message")
    void testFeatureCommandExecution() {
        FeatureCommand command = new FeatureCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--dir=" + tempDir, "Build authentication");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Initiating full-stack TDD execution: Build authentication");
        assertThat(command.getInstruction()).isEqualTo("Build authentication");
    }

    @Test
    @DisplayName("Given feature command with instruction and flags - when parsed - then sets properties correctly")
    void testFeatureCommandFlags() {
        FeatureCommand command = new FeatureCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute(
            "--dir=" + tempDir.toString(),
            "--gui",
            "--detach",
            "--yes",
            "--offline",
            "Add User Metrics API"
        );

        assertThat(exitCode).isZero();
        assertThat(command.isGui()).isTrue();
        assertThat(command.isDetach()).isTrue();
        assertThat(command.isYes()).isTrue();
        assertThat(command.isOffline()).isTrue();
        assertThat(command.getInstruction()).isEqualTo("Add User Metrics API");
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given feature command without instruction - when executed - then returns warning message")
    void testFeatureCommandWithoutInstruction() {
        FeatureCommand command = new FeatureCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("Please provide a feature specification");

        StringWriter swBlank = new StringWriter();
        cmd.setOut(new PrintWriter(swBlank));
        int exitCodeBlank = cmd.execute("   ");
        assertThat(exitCodeBlank).isZero();
        assertThat(swBlank.toString()).contains("Please provide a feature specification");
    }

    @Test
    @DisplayName("Given feature command - when executed with --help - then outputs feature subcommand usage and exit code 0")
    void testHelpOption() {
        FeatureCommand command = new FeatureCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains(
            "Usage: feature",
            "[<instruction>]",
            "--gui",
            "--detach",
            "-y, --yes",
            "--offline",
            "-d, --dir, --workspace"
        );
    }

    @Test
    @DisplayName("Given git repository and --detach - when feature executed - then allocates worktree, launches detached worker, and writes PID to state.json")
    void testFeatureDetachedLaunchesWorkerInWorktree() throws IOException {
        Path worktreeDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-DETACH"));
        StateSocketService socketService = new StateSocketService();
        GitWorktreeService worktreeService = mock(GitWorktreeService.class);
        DetachedWorkerLauncher launcher = mock(DetachedWorkerLauncher.class);
        DesktopAlertService alertService = mock(DesktopAlertService.class);
        BackendBuildService buildService = mock(BackendBuildService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        StateSyncService syncService = mock(StateSyncService.class);

        when(worktreeService.isGitRepository(tempDir.toAbsolutePath().normalize())).thenReturn(true);
        when(worktreeService.allocateWorktree(eq(tempDir.toAbsolutePath().normalize()), anyString(), eq("Add health check"), any(), eq("target-agent")))
            .thenAnswer(inv -> WorktreeAllocation.builder()
                .taskId(inv.getArgument(1))
                .branch("feature/" + inv.getArgument(1) + "-add-health-check")
                .worktreePath(worktreeDir)
                .baseCommit("abc123")
                .hydrated(false)
                .build());
        when(launcher.launchWorker(eq(tempDir.toAbsolutePath().normalize()), anyString(), eq("Add health check")))
            .thenReturn(77777L);
        when(persistenceService.saveTask(any(TaskRun.class))).thenAnswer(inv -> inv.getArgument(0));

        FeatureCommand command = new FeatureCommand(
            worktreeService,
            socketService,
            new LocalWorkerLimiter(socketService, new DevAgentConfigLoader()),
            launcher,
            new ProjectIdResolver(new WorkspaceContextResolver(tempDir)),
            new DevAgentConfigLoader(),
            new WorktreeTaskExecutor(socketService, syncService, buildService, alertService, persistenceService, worktreeService)
        );
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--dir=" + tempDir, "--detach", "Add health check");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Detached worker started", "PID 77777");
        verify(launcher).launchWorker(eq(tempDir.toAbsolutePath().normalize()), anyString(), eq("Add health check"));
        assertThat(socketService.readAllStates(tempDir))
            .singleElement()
            .satisfies(state -> {
                assertThat(state.pid()).isEqualTo(77777L);
                assertThat(state.status()).isEqualTo(TaskStatus.PLANNING);
            });
    }

    @Test
    @DisplayName("Given git repository without --detach - when feature executed - then executes foreground inside worktree and sends desktop alert")
    void testFeatureForegroundExecutesInWorktreeAndNotifies() throws IOException {
        Path worktreeDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-FG"));
        StateSocketService socketService = new StateSocketService();
        GitWorktreeService worktreeService = mock(GitWorktreeService.class);
        DetachedWorkerLauncher launcher = mock(DetachedWorkerLauncher.class);
        DesktopAlertService alertService = mock(DesktopAlertService.class);
        BackendBuildService buildService = mock(BackendBuildService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        StateSyncService syncService = mock(StateSyncService.class);

        when(worktreeService.isGitRepository(tempDir.toAbsolutePath().normalize())).thenReturn(true);
        when(worktreeService.allocateWorktree(eq(tempDir.toAbsolutePath().normalize()), anyString(), eq("Foreground feature"), any(), eq("target-agent")))
            .thenAnswer(inv -> WorktreeAllocation.builder()
                .taskId(inv.getArgument(1))
                .branch("feature/" + inv.getArgument(1) + "-foreground-feature")
                .worktreePath(worktreeDir)
                .baseCommit("abc123")
                .hydrated(true)
                .build());
        when(persistenceService.saveTask(any(TaskRun.class))).thenAnswer(inv -> inv.getArgument(0));

        FeatureCommand command = new FeatureCommand(
            worktreeService,
            socketService,
            new LocalWorkerLimiter(socketService, new DevAgentConfigLoader()),
            launcher,
            new ProjectIdResolver(new WorkspaceContextResolver(tempDir)),
            new DevAgentConfigLoader(),
            new WorktreeTaskExecutor(socketService, syncService, buildService, alertService, persistenceService, worktreeService)
        );
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--dir=" + tempDir, "Foreground feature");

        assertThat(exitCode).isZero();
        verify(alertService).notifyTaskOutcome(eq(tempDir.toAbsolutePath().normalize()), any(WorktreeState.class));
        assertThat(socketService.readAllStates(tempDir))
            .singleElement()
            .extracting(WorktreeState::status)
            .isEqualTo(TaskStatus.COMPLETED);
    }

    @Test
    @DisplayName("Given worktree with pom.xml or build.gradle - when buildService succeeds, fails, or throws - then updates terminal status and exit code")
    void testFeatureForegroundBuildOutcomes() throws IOException {
        Path wtOk = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-OK"));
        Files.writeString(wtOk.resolve("pom.xml"), "<project/>");
        StateSocketService socketService = new StateSocketService();
        GitWorktreeService worktreeService = mock(GitWorktreeService.class);
        DetachedWorkerLauncher launcher = mock(DetachedWorkerLauncher.class);
        DesktopAlertService alertService = mock(DesktopAlertService.class);
        BackendBuildService buildService = mock(BackendBuildService.class);
        TaskPersistenceService persistenceService = mock(TaskPersistenceService.class);
        StateSyncService syncService = mock(StateSyncService.class);

        when(worktreeService.isGitRepository(tempDir.toAbsolutePath().normalize())).thenReturn(true);
        when(worktreeService.allocateWorktree(any(), anyString(), anyString(), any(), anyString()))
            .thenAnswer(inv -> WorktreeAllocation.builder()
                .taskId(inv.getArgument(1))
                .branch("feature/" + inv.getArgument(1) + "-test")
                .worktreePath(wtOk)
                .baseCommit("abc123")
                .hydrated(false)
                .build());
        when(buildService.compile(eq(wtOk), eq(null), eq("target-agent")))
            .thenReturn(SanitizedLog.builder().exitCode(0).errorCount(0).build())
            .thenReturn(SanitizedLog.builder().exitCode(1).errorCount(2).build())
            .thenThrow(new IllegalStateException("build crash"));
        when(persistenceService.saveTask(any(TaskRun.class))).thenAnswer(inv -> inv.getArgument(0));

        FeatureCommand command = new FeatureCommand(
            worktreeService,
            socketService,
            new LocalWorkerLimiter(socketService, new DevAgentConfigLoader()),
            launcher,
            new ProjectIdResolver(new WorkspaceContextResolver(tempDir)),
            new DevAgentConfigLoader(),
            new WorktreeTaskExecutor(socketService, syncService, buildService, alertService, persistenceService, worktreeService)
        );
        CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(new StringWriter()));

        assertThat(cmd.execute("--dir=" + tempDir, "Compile succeeds")).isZero();
        assertThat(cmd.execute("--dir=" + tempDir, "Compile fails")).isEqualTo(1);

        Files.deleteIfExists(wtOk.resolve("pom.xml"));
        Files.writeString(wtOk.resolve("build.gradle"), "// gradle");
        assertThat(cmd.execute("--dir=" + tempDir, "Compile throws")).isEqualTo(1);
    }

    @Test
    @DisplayName("Given dev-agent.yaml with use-worktrees=false - when feature executed without --detach - then skips worktree allocation")
    void testFeatureRespectsUseWorktreesFalseConfig() throws IOException {
        Files.createDirectories(tempDir.resolve(".agents"));
        Files.writeString(tempDir.resolve(".agents/dev-agent.yaml"), """
            agent:
              isolation:
                use-worktrees: false
            """);
        GitWorktreeService worktreeService = mock(GitWorktreeService.class);
        when(worktreeService.isGitRepository(tempDir.toAbsolutePath().normalize())).thenReturn(true);

        FeatureCommand command = new FeatureCommand(
            worktreeService,
            new StateSocketService(),
            new LocalWorkerLimiter(),
            mock(DetachedWorkerLauncher.class),
            new ProjectIdResolver(new WorkspaceContextResolver(tempDir)),
            new DevAgentConfigLoader(),
            new WorktreeTaskExecutor(
                new StateSocketService(),
                mock(StateSyncService.class),
                mock(BackendBuildService.class),
                mock(DesktopAlertService.class),
                mock(TaskPersistenceService.class),
                worktreeService
            )
        );
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--dir=" + tempDir, "No isolation story");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("skipping worktree allocation");
    }
}
