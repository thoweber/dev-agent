package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.testutil.WorktreeStateMother;
import guru.thomasweber.devagent.workspace.StateSocketService;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static guru.thomasweber.devagent.testutil.CliOutputAssert.assertThatCli;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusMainTest
class DetachedWorkerIT {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given seeded state.json socket and worker.log - when status, logs, and abort CLI launched - then operate without locking DB")
    void testStatusLogsAndAbortViaMainLauncher(QuarkusMainLauncher launcher) throws IOException {
        StateSocketService socketService = new StateSocketService();
        WorktreeState seeded = WorktreeStateMother.crashed("TASK-IT-CLI")
            .toBuilder()
            .branch("feature/TASK-IT-CLI-demo")
            .build();
        socketService.writeHeartbeat(tempDir, seeded);
        Path logFile = socketService.resolveWorkerLogFile(tempDir, "TASK-IT-CLI");
        Files.writeString(logFile, "[worker] Hello from IT worker log\n");

        LaunchResult textStatusResult = launcher.launch("status", "--dir=" + tempDir);
        assertThatCli(textStatusResult.exitCode(), textStatusResult.getOutput())
            .isSuccess()
            .containsAnsiFree("TASK-IT-CLI", "STALE", "GREEN_PATCH");

        LaunchResult statusResult = launcher.launch("status", "--dir=" + tempDir, "--json");
        assertThatCli(statusResult.exitCode(), statusResult.getOutput())
            .isSuccess()
            .containsAnsiFree("\"TASK-IT-CLI\"", "\"GREEN_PATCH\"", "\"activeTasks\"");

        LaunchResult logsResult = launcher.launch("logs", "--dir=" + tempDir, "TASK-IT-CLI");
        assertThatCli(logsResult.exitCode(), logsResult.getOutput())
            .isSuccess()
            .containsAnsiFree("Hello from IT worker log");

        LaunchResult abortResult = launcher.launch("abort", "--dir=" + tempDir, "TASK-IT-CLI");
        assertThatCli(abortResult.exitCode(), abortResult.getOutput())
            .isSuccess()
            .containsAnsiFree("Aborting background task: TASK-IT-CLI");

        assertThat(tempDir.resolve(".git/dev-agent-worktrees/TASK-IT-CLI")).doesNotExist();
    }

    @Test
    @DisplayName("Given non-Git workspace - when feature CLI launched - then exits 0 with graceful skip message")
    void testFeatureGracefulSkipInNonGitDir(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("feature", "--dir=" + tempDir, "Simple non-git story");
        assertThatCli(result.exitCode(), result.getOutput())
            .isSuccess()
            .containsAnsiFree("Non-Git or non-isolated workspace detected; skipping worktree allocation.");
    }
}
