package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.testutil.WorktreeStateMother;
import guru.thomasweber.devagent.workspace.StateSocketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogsCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given logs command - when executed with valid taskId - then outputs streaming logs message and exit code 0")
    void testLogsWithTaskId() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--dir=" + tempDir, "TASK-101");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Streaming logs for task: TASK-101");
        assertThat(command.getTaskId()).isEqualTo("TASK-101");
        assertThat(command.isFollow()).isFalse();
    }

    @Test
    @DisplayName("Given logs command - when executed with --follow flag - then parses follow flag as true")
    void testLogsWithFollowFlag() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("-f", "TASK-101");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Streaming logs for task: TASK-101");
        assertThat(command.isFollow()).isTrue();
    }

    @Test
    @DisplayName("Given logs command - when executed without taskId - then outputs warning to specify taskId")
    void testLogsWithoutTaskId() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute();

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Please specify a task ID to display logs.");
    }

    @Test
    @DisplayName("Given logs command - when executed with blank taskId - then outputs warning to specify taskId")
    void testLogsWithBlankTaskId() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("   ");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("[dev-agent] Please specify a task ID to display logs.");
    }

    @Test
    @DisplayName("Given logs command - when executed with --help - then outputs logs subcommand usage and exit code 0")
    void testHelpOption() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains(
            "Usage: logs",
            "-f, --follow",
            "[<taskId>]",
            "-d, --dir, --workspace"
        );
    }

    @Test
    @DisplayName("Given logs command - when executed with --dir - then resolves workspace directory")
    void testDirectoryOption() {
        LogsCommand command = new LogsCommand();
        CommandLine cmd = new CommandLine(command);

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "-f", "TASK-303");

        assertThat(exitCode).isZero();
        assertThat(command.getTaskId()).isEqualTo("TASK-303");
        assertThat(command.isFollow()).isTrue();
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given worker.log and terminal state.json - when logs --follow executed - then streams log lines and exits")
    void testLogsReadsWorkerLogAndFollowsUntilTerminal() throws IOException {
        StateSocketService socketService = new StateSocketService();
        socketService.writeHeartbeat(tempDir, WorktreeStateMother.completed("TASK-LOG-1"));
        Path logFile = socketService.resolveWorkerLogFile(tempDir, "TASK-LOG-1");
        Files.writeString(logFile, "[worker] Compiling...\n[worker] Done\n");

        LogsCommand command = new LogsCommand(socketService);
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--dir=" + tempDir, "--follow", "TASK-LOG-1");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("[worker] Compiling...", "[worker] Done");
    }

    @Test
    @DisplayName("Given pruned worktree with markdown journal - when logs executed - then falls back to .agents/runs journal")
    void testLogsFallsBackToMarkdownJournal() throws IOException {
        Files.createDirectories(tempDir.resolve(".agents/runs"));
        Files.writeString(tempDir.resolve(".agents/runs/TASK-HIST-2.md"), "# Journal\n- [x] **compile** (100ms): OK\n");

        LogsCommand command = new LogsCommand(new StateSocketService());
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--dir=" + tempDir, "TASK-HIST-2");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("# Journal", "**compile**");
    }

    @Test
    @DisplayName("Given traversal taskId - when logs executed - then throws SecurityException and fails CLI execution")
    void testLogsRejectsTraversalTaskId() {
        LogsCommand command = new LogsCommand(new StateSocketService());
        CommandLine cmd = new CommandLine(command);
        StringWriter err = new StringWriter();
        cmd.setErr(new PrintWriter(err));

        cmd.parseArgs("--dir=" + tempDir, "../secret");
        assertThatThrownBy(command::run)
            .isInstanceOf(SecurityException.class);

        int exitCode = cmd.execute("--dir=" + tempDir, "../secret");
        assertThat(exitCode).isNotZero();
        assertThat(err.toString()).contains("SecurityException");
    }

    @Test
    @DisplayName("Given worker.log without --follow or with live worker finishing asynchronously - when logs executed - then prints complete output")
    void testLogsWithoutFollowAndLiveFollowTransition() throws IOException {
        StateSocketService socketService = new StateSocketService();
        socketService.writeHeartbeat(tempDir, WorktreeStateMother.active("TASK-LOG-ONCE", ProcessHandle.current().pid()));
        Path onceLog = socketService.resolveWorkerLogFile(tempDir, "TASK-LOG-ONCE");
        Files.writeString(onceLog, "[worker] Single pass\n");

        LogsCommand singlePassCmd = new LogsCommand(socketService);
        CommandLine clOnce = new CommandLine(singlePassCmd);
        StringWriter outOnce = new StringWriter();
        clOnce.setOut(new PrintWriter(outOnce));
        assertThat(clOnce.execute("--dir=" + tempDir, "TASK-LOG-ONCE")).isZero();
        assertThat(outOnce.toString()).contains("[worker] Single pass");

        socketService.writeHeartbeat(tempDir, WorktreeStateMother.active("TASK-LOG-LIVE", ProcessHandle.current().pid()));
        Path liveLog = socketService.resolveWorkerLogFile(tempDir, "TASK-LOG-LIVE");
        Files.writeString(liveLog, "[worker] Step 1\n");

        LogsCommand followCmd = new LogsCommand(socketService);
        CommandLine clFollow = new CommandLine(followCmd);
        StringWriter outFollow = new StringWriter();
        clFollow.setOut(new PrintWriter(outFollow));
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            scheduler.schedule(() -> {
                try {
                    Files.writeString(liveLog, "[worker] Step 1\n[worker] Step 2\n");
                    socketService.writeHeartbeat(tempDir, WorktreeStateMother.completed("TASK-LOG-LIVE"));
                } catch (Exception _) {
                    // ignore
                }
            }, 80L, TimeUnit.MILLISECONDS);
            assertThat(clFollow.execute("--dir=" + tempDir, "--follow", "TASK-LOG-LIVE")).isZero();
        }

        assertThat(outFollow.toString()).contains("[worker] Step 1", "[worker] Step 2");
    }

    @Test
    @DisplayName("Given symlinked journal or secret tokens in worker.log - when logs executed - then blocks symlink and redacts secrets")
    void testLogsRejectsSymlinkedJournalAndRedactsSecrets() throws IOException {
        Path secretTarget = Files.writeString(tempDir.resolve("outside.txt"), "top secret");
        Path runsDir = Files.createDirectories(tempDir.resolve(".agents/runs"));
        Files.createSymbolicLink(runsDir.resolve("TASK-SYM-J.md"), secretTarget);

        LogsCommand symCommand = new LogsCommand(new StateSocketService());
        CommandLine clSym = new CommandLine(symCommand);
        clSym.parseArgs("--dir=" + tempDir, "TASK-SYM-J");
        assertThatThrownBy(symCommand::run)
            .isInstanceOf(SecurityException.class);

        StateSocketService socketService = new StateSocketService();
        socketService.writeHeartbeat(tempDir, WorktreeStateMother.completed("TASK-SECRET-LOG"));
        Path logFile = socketService.resolveWorkerLogFile(tempDir, "TASK-SECRET-LOG");
        Files.writeString(logFile, "Using token ghp_1234567890abcdef1234567890abcdef1234 for build\n");

        LogsCommand redactCmd = new LogsCommand(socketService);
        CommandLine clRedact = new CommandLine(redactCmd);
        StringWriter out = new StringWriter();
        clRedact.setOut(new PrintWriter(out));
        assertThat(clRedact.execute("--dir=" + tempDir, "TASK-SECRET-LOG")).isZero();
        assertThat(out.toString())
            .doesNotContain("ghp_1234567890abcdef1234567890abcdef1234")
            .contains("***REDACTED_CREDENTIAL***");

        // Verify multi-chunk streaming (> 8KB buffer) and log truncation reset while following
        String largeBlock = "line-chunk-0123456789\n".repeat(500);
        socketService.writeHeartbeat(tempDir, WorktreeStateMother.active("TASK-CHUNK", ProcessHandle.current().pid()));
        Path chunkLog = socketService.resolveWorkerLogFile(tempDir, "TASK-CHUNK");
        Files.writeString(chunkLog, largeBlock);

        LogsCommand chunkCmd = new LogsCommand(socketService);
        CommandLine clChunk = new CommandLine(chunkCmd);
        StringWriter chunkOut = new StringWriter();
        clChunk.setOut(new PrintWriter(chunkOut));
        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            scheduler.schedule(() -> {
                try {
                    Files.writeString(chunkLog, "[worker] truncated tail\n");
                    socketService.writeHeartbeat(tempDir, WorktreeStateMother.completed("TASK-CHUNK"));
                } catch (Exception _) {
                    // ignore
                }
            }, 80L, TimeUnit.MILLISECONDS);
            assertThat(clChunk.execute("--dir=" + tempDir, "--follow", "TASK-CHUNK")).isZero();
        }
        assertThat(chunkOut.toString()).contains("line-chunk-0123456789", "[worker] truncated tail");
    }

    @Test
    @DisplayName("Given secret and multibyte UTF-8 crossing 8KB buffer boundary - when logs executed - then redacts secret and decodes UTF-8 intact")
    void testLogsRedactsSecretAndDecodesUtf8AcrossBufferBoundary() throws IOException {
        StateSocketService socketService = new StateSocketService();
        socketService.writeHeartbeat(tempDir, WorktreeStateMother.completed("TASK-BOUNDARY"));
        Path boundaryLog = socketService.resolveWorkerLogFile(tempDir, "TASK-BOUNDARY");

        String prefix = "A".repeat(8184) + " ";
        String line = prefix + "ghp_1234567890abcdef1234567890abcdef1234 🚀 end\n";
        Files.writeString(boundaryLog, line);

        LogsCommand cmdObj = new LogsCommand(socketService);
        CommandLine cmd = new CommandLine(cmdObj);
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        assertThat(cmd.execute("--dir=" + tempDir, "TASK-BOUNDARY")).isZero();
        assertThat(out.toString())
            .contains("🚀", "***REDACTED_CREDENTIAL***")
            .doesNotContain("ghp_1234567890abcdef1234567890abcdef1234")
            .doesNotContain("\uFFFD");
    }

    @Test
    @DisplayName("Given partial line without trailing newline while active - when logs --follow runs - then flushes cleanly on completion and handles interrupts")
    void testLogsFollowPartialLineAndInterruptHandling() throws IOException {
        StateSocketService socketService = new StateSocketService();
        socketService.writeHeartbeat(tempDir, WorktreeStateMother.active("TASK-PARTIAL", ProcessHandle.current().pid()));
        Path partialLog = socketService.resolveWorkerLogFile(tempDir, "TASK-PARTIAL");
        Files.writeString(partialLog, "partial-without-newline");

        LogsCommand cmdObj = new LogsCommand(socketService);
        CommandLine cmd = new CommandLine(cmdObj);
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        try (ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor()) {
            scheduler.schedule(() -> {
                try {
                    Files.writeString(partialLog, "partial-without-newline-finished\n");
                    socketService.writeHeartbeat(tempDir, WorktreeStateMother.completed("TASK-PARTIAL"));
                } catch (Exception _) {
                    // ignore
                }
            }, 60L, TimeUnit.MILLISECONDS);
            assertThat(cmd.execute("--dir=" + tempDir, "--follow", "TASK-PARTIAL")).isZero();
        }
        assertThat(out.toString()).contains("partial-without-newline");

        // Interrupted before loop poll
        socketService.writeHeartbeat(tempDir, WorktreeStateMother.active("TASK-INT", ProcessHandle.current().pid()));
        Files.writeString(socketService.resolveWorkerLogFile(tempDir, "TASK-INT"), "first-line\n");
        Thread.currentThread().interrupt();
        try {
            assertThat(new CommandLine(new LogsCommand(socketService)).execute("--dir=" + tempDir, "--follow", "TASK-INT")).isZero();
        } finally {
            Thread.interrupted();
        }

        // Regular journal fallback without worker.log
        Path runsDir = Files.createDirectories(tempDir.resolve(".agents/runs"));
        Files.writeString(runsDir.resolve("TASK-JOURNAL-FALLBACK.md"), "# Journal\nAll steps passed with token ghp_1234567890abcdef1234567890abcdef1234\n");
        StringWriter journalOut = new StringWriter();
        CommandLine journalCmd = new CommandLine(new LogsCommand(socketService));
        journalCmd.setOut(new PrintWriter(journalOut));
        assertThat(journalCmd.execute("--dir=" + tempDir, "TASK-JOURNAL-FALLBACK")).isZero();
        assertThat(journalOut.toString())
            .contains("# Journal", "***REDACTED_CREDENTIAL***")
            .doesNotContain("ghp_1234567890abcdef1234567890abcdef1234");
    }
}
