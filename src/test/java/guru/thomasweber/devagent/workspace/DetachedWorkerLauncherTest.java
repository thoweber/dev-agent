package guru.thomasweber.devagent.workspace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DetachedWorkerLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given valid repoRoot and taskId - when buildWorkerCommand called - then constructs hidden worker CLI invocation")
    void givenRepoRootAndTaskId_whenBuildWorkerCommand_thenBuildsWorkerInvocation() {
        DetachedWorkerLauncher launcher = new DetachedWorkerLauncher();
        List<String> command = launcher.buildWorkerCommand(tempDir, "TASK-701", "Add metrics");

        assertThat(command).isNotEmpty();
        assertThat(command).contains("--dir", tempDir.toString(), "worker", "--task-id", "TASK-701", "--instruction=Add metrics");
    }

    @Test
    @DisplayName("Given valid command and worker.log target - when launchCommand executed - then spawns detached process and captures log output")
    void givenCommandAndLogFile_whenLaunchCommand_thenSpawnsProcessAndWritesLog() throws Exception {
        StateSocketService socketService = new StateSocketService();
        DetachedWorkerLauncher launcher = new DetachedWorkerLauncher(socketService);
        Path logFile = socketService.resolveWorkerLogFile(tempDir, "TASK-702");

        List<String> shortLived = List.of("sh", "-c", "echo detached-worker-hello");
        long pid = launcher.launchCommand(shortLived, tempDir, logFile);

        assertThat(pid).isPositive();
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline && (!Files.exists(logFile) || Files.readString(logFile).isBlank())) {
            Thread.sleep(25);
        }
        assertThat(Files.readString(logFile)).contains("detached-worker-hello");
    }

    @Test
    @DisplayName("Given invalid executable or null arguments - when launchCommand or launchWorker called - then throws WorkspaceException or SecurityException")
    void givenInvalidArguments_whenCalled_thenThrowsExpectedException() {
        DetachedWorkerLauncher launcher = new DetachedWorkerLauncher();

        assertThatThrownBy(() -> launcher.launchWorker(tempDir, "../bad", "story"))
            .isInstanceOf(SecurityException.class);

        Path logFile = tempDir.resolve("worker.log");
        assertThatThrownBy(() -> launcher.launchCommand(List.of("/nonexistent/binary/xyz-999"), tempDir, logFile))
            .isInstanceOf(WorkspaceException.class);
        assertThatThrownBy(() -> launcher.launchWorker(null, "TASK-1", "story"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> launcher.launchCommand(List.of(), tempDir, logFile))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> launcher.launchCommand(null, tempDir, logFile))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Given jar classpath or null workingDir/logFile - when buildWorkerCommand and launchCommand called - then resolves cleanly")
    void givenJarClasspathAndNullOptionalPaths_whenInvoked_thenResolvesCleanly() {
        DetachedWorkerLauncher launcher = new DetachedWorkerLauncher();
        String originalClasspath = System.getProperty("java.class.path");
        String originalSunCmd = System.getProperty("sun.java.command");
        try {
            System.setProperty("java.class.path", "/opt/dev-agent/quarkus-run.jar");
            List<String> jarCmd = launcher.buildWorkerCommand(tempDir, "TASK-703", null);
            assertThat(jarCmd).contains("-jar", "/opt/dev-agent/quarkus-run.jar", "worker", "--task-id", "TASK-703", "--instruction=");

            System.setProperty("java.class.path", "/opt/a:/opt/b");
            System.setProperty("sun.java.command", "");
            List<String> fallbackMainCmd = launcher.buildWorkerCommand(tempDir, "TASK-704", "inst");
            assertThat(fallbackMainCmd).contains("-cp", "/opt/a:/opt/b", "io.quarkus.runner.GeneratedMain");
        } finally {
            if (originalClasspath != null) {
                System.setProperty("java.class.path", originalClasspath);
            }
            if (originalSunCmd != null) {
                System.setProperty("sun.java.command", originalSunCmd);
            }
        }

        long pid = launcher.launchCommand(List.of("sh", "-c", "true"), null, null);
        assertThat(pid).isPositive();
    }

    @Test
    @DisplayName("Given dash-prefixed instruction - when buildWorkerCommand called - then encodes as --instruction=value")
    void givenDashPrefixedInstruction_whenBuildWorkerCommand_thenUsesEqualsOptionForm() {
        DetachedWorkerLauncher launcher = new DetachedWorkerLauncher();
        List<String> command = launcher.buildWorkerCommand(tempDir, "TASK-DASH", "- fix login bug");
        assertThat(command).contains("--instruction=- fix login bug");
    }

    @Test
    @DisplayName("Given existing worktree directory - when launchWorker invoked - then sets working directory to worktreeDir")
    void givenExistingWorktreeDir_whenLaunchWorker_thenUsesWorktreeAsWorkingDir() throws Exception {
        Path worktreeDir = Files.createDirectories(tempDir.resolve(".git/dev-agent-worktrees/TASK-CWD"));
        DetachedWorkerLauncher spyLauncher = new DetachedWorkerLauncher() {
            @Override
            public List<String> buildWorkerCommand(Path repoRoot, String taskId, String instruction) {
                return List.of("sh", "-c", "pwd");
            }
        };

        long pid = spyLauncher.launchWorker(tempDir, "TASK-CWD", "cwd test");
        assertThat(pid).isPositive();
        Path logFile = new StateSocketService().resolveWorkerLogFile(tempDir, "TASK-CWD");
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline && (!Files.exists(logFile) || Files.readString(logFile).isBlank())) {
            Thread.sleep(25);
        }
        assertThat(Files.readString(logFile).trim()).isEqualTo(worktreeDir.toRealPath().toString());
    }
}
