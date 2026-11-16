package guru.thomasweber.devagent.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import guru.thomasweber.devagent.cognitive.MarkdownParserService;
import guru.thomasweber.devagent.model.StatusPayload;
import guru.thomasweber.devagent.model.WorktreeState;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StatusCommandTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Given status command - when executed with default options - then outputs non-locking socket checking message and exit code 0")
    void testDefaultExecution() {
        StatusCommand command = new StatusCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--dir=" + tempDir);

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains(
            "[dev-agent] Checking active task sockets...",
            "[dev-agent] No active background tasks detected."
        );
        assertThat(command.isJson()).isFalse();
        assertThat(command.isAll()).isFalse();
    }

    @Test
    @DisplayName("Given status command - when executed with --json - then outputs JSON formatted payload with empty active tasks")
    void testJsonExecution() {
        StatusCommand command = new StatusCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--dir=" + tempDir, "--json");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains("{\"activeTasks\":[],\"message\":\"No active background tasks detected.\"}");
        assertThat(command.isJson()).isTrue();
    }

    @Test
    @DisplayName("Given status command - when executed with --all - then flags isAll as true")
    void testAllFlagParsing() {
        StatusCommand command = new StatusCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--dir=" + tempDir, "--all");

        assertThat(exitCode).isZero();
        assertThat(command.isAll()).isTrue();
        assertThat(sw.toString()).contains("[dev-agent] No active background tasks detected.");
    }

    @Test
    @DisplayName("Given status command - when executed with --help - then outputs status subcommand usage and exit code 0")
    void testHelpOption() {
        StatusCommand command = new StatusCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(sw.toString()).contains(
            "Usage: status",
            "--all",
            "--json",
            "-d, --dir, --workspace"
        );
    }

    @Test
    @DisplayName("Given status command - when executed with --dir - then resolves workspace root directory")
    void testDirectoryOption() {
        StatusCommand command = new StatusCommand();
        CommandLine cmd = new CommandLine(command);
        StringWriter sw = new StringWriter();
        cmd.setOut(new PrintWriter(sw));

        int exitCode = cmd.execute("--dir=" + tempDir.toString(), "--all", "--json");

        assertThat(exitCode).isZero();
        assertThat(command.isAll()).isTrue();
        assertThat(command.isJson()).isTrue();
        assertThat(command.getDir()).isEqualTo(tempDir.toString());
    }

    @Test
    @DisplayName("Given active and stale state.json sockets - when status executed - then displays live telemetry and JSON payload without JDBC")
    void testStatusReadsStateSocketsAndJson() throws IOException {
        StateSocketService socketService = new StateSocketService();
        WorktreeState active = WorktreeStateMother.active("TASK-LIVE", ProcessHandle.current().pid());
        WorktreeState stale = WorktreeStateMother.crashed("TASK-STALE");
        socketService.writeHeartbeat(tempDir, active);
        socketService.writeHeartbeat(tempDir, stale);

        StatusCommand command = new StatusCommand(socketService, new MarkdownParserService());
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--dir=" + tempDir);
        assertThat(exitCode).isZero();
        assertThat(out.toString())
            .contains("TASK-LIVE", "RUNNING", "TASK-STALE", "STALE");

        StringWriter jsonOut = new StringWriter();
        cmd.setOut(new PrintWriter(jsonOut));
        int jsonExit = cmd.execute("--dir=" + tempDir, "--json");
        assertThat(jsonExit).isZero();
        assertThat(jsonOut.toString())
            .contains("\"activeTasks\"", "\"TASK-LIVE\"", "\"TASK-STALE\"");
    }

    @Test
    @DisplayName("Given historical .agents/runs markdown journals - when status --all executed - then includes historical runs")
    void testStatusAllIncludesMarkdownJournals() throws IOException {
        Files.createDirectories(tempDir.resolve(".agents/runs"));
        Files.writeString(tempDir.resolve(".agents/runs/TASK-HIST.md"), """
            ---
            task_id: "TASK-HIST"
            project_id: "proj-1"
            status: "COMPLETED"
            current_phase: "COMPLETED"
            branch: "feature/TASK-HIST-old"
            pid: 0
            updated_at: "2026-11-16T18:00:00Z"
            ---
            # Task Execution Journal: TASK-HIST
            """);

        StatusCommand command = new StatusCommand(new StateSocketService(), new MarkdownParserService());
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--dir=" + tempDir, "--all");
        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("TASK-HIST", "COMPLETED");

        Path outsideSecret = Files.writeString(tempDir.resolve("outside.md"), """
            ---
            task_id: "TASK-LEAK"
            status: "COMPLETED"
            ---
            """);
        Files.createSymbolicLink(tempDir.resolve(".agents/runs/TASK-LEAK.md"), outsideSecret);

        StringWriter outAfterSymlink = new StringWriter();
        cmd.setOut(new PrintWriter(outAfterSymlink));
        assertThat(cmd.execute("--dir=" + tempDir, "--all")).isZero();
        assertThat(outAfterSymlink.toString()).doesNotContain("TASK-LEAK");
    }

    @Test
    @DisplayName("Given queued state socket and malformed journals - when status --all executed - then formats QUEUED and handles edge metadata")
    void testStatusAllEdgeCasesAndJsonFailure() throws IOException {
        StateSocketService socketService = new StateSocketService();
        socketService.writeHeartbeat(tempDir, WorktreeStateMother.queued("TASK-Q1").toBuilder()
            .cost(null)
            .branch(null)
            .build());
        Files.createDirectories(tempDir.resolve(".agents/runs"));
        Files.writeString(tempDir.resolve(".agents/runs/ignore-no-id.md"), """
            ---
            status: "COMPLETED"
            ---
            No task id
            """);
        Files.writeString(tempDir.resolve(".agents/runs/unknown-fields.md"), """
            ---
            task_id: "TASK-UNK"
            status: "NOT_A_REAL_STATUS"
            updated_at: "unknown"
            ---
            Unknown values
            """);

        StatusCommand command = new StatusCommand(socketService, new MarkdownParserService(), null);
        CommandLine cmd = new CommandLine(command);
        StringWriter out = new StringWriter();
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--dir=" + tempDir, "--all");

        assertThat(exitCode).isZero();
        assertThat(out.toString())
            .contains("TASK-Q1", "QUEUED", "$0", "unassigned", "TASK-UNK", "COMPLETED");

        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any(StatusPayload.class)))
            .thenThrow(new JsonProcessingException("mock serialization boom") {});
        StatusCommand failingCommand = new StatusCommand(socketService, new MarkdownParserService(), failingMapper);
        CommandLine failingCmd = new CommandLine(failingCommand);
        StringWriter errJsonOut = new StringWriter();
        failingCmd.setOut(new PrintWriter(errJsonOut));

        int jsonExit = failingCmd.execute("--dir=" + tempDir, "--json");
        assertThat(jsonExit).isZero();
        assertThat(errJsonOut.toString()).contains("Serialization error: mock serialization boom");
    }
}
