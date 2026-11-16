package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.workspace.SecretRedactionFilter;
import guru.thomasweber.devagent.workspace.StateSocketService;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Dependent
@Unremovable
@Command(
    name = "logs",
    description = "Streams execution logs and audit records for an active or completed task",
    mixinStandardHelpOptions = true
)
public class LogsCommand extends BaseTaskCommand {

    private static final long POLL_INTERVAL_MS = 200L;
    private static final int READ_BUFFER_SIZE = 8192;

    @Option(names = {"-f", "--follow"}, description = "Follow live log output")
    private boolean follow;

    private final StateSocketService socketService;
    private final SecretRedactionFilter secretFilter;

    @Inject
    public LogsCommand(StateSocketService socketService, SecretRedactionFilter secretFilter) {
        this.socketService = socketService;
        this.secretFilter = secretFilter;
    }

    public LogsCommand(StateSocketService socketService) {
        this(socketService, new SecretRedactionFilter());
    }

    public LogsCommand() {
        this(new StateSocketService(), new SecretRedactionFilter());
    }

    @Override
    public void run() {
        PrintWriter out = getOut();
        if (isNull(taskId) || taskId.isBlank()) {
            out.println("[dev-agent] Please specify a task ID to display logs.");
            return;
        }

        WorkspaceContextResolver resolver = getWorkspaceContextResolver();
        Path repoRoot = resolver.resolveRepoRoot();
        Path workerLog = socketService.resolveWorkerLogFile(repoRoot, taskId.trim());

        out.println("[dev-agent] Streaming logs for task: " + taskId);

        if (Files.isRegularFile(workerLog)) {
            streamWorkerLog(out, repoRoot, workerLog);
            return;
        }

        Path journalFile = repoRoot.resolve(".agents").resolve("runs").resolve(taskId.trim() + ".md");
        StateSocketService.validateWorktreePathSymlinks(repoRoot, journalFile);
        if (Files.isRegularFile(journalFile)) {
            printFileOnce(out, journalFile);
            return;
        }

        out.println("[dev-agent] No logs found for task: " + taskId);
    }

    private void streamWorkerLog(PrintWriter out, Path repoRoot, Path workerLog) {
        long offset = readAndPrintChunk(out, workerLog, 0L);
        if (!follow) {
            return;
        }

        while (!Thread.currentThread().isInterrupted()) {
            Optional<WorktreeState> stateOpt = socketService.readState(repoRoot, taskId.trim());
            boolean done = stateOpt.isEmpty()
                || (nonNull(stateOpt.get().status()) && stateOpt.get().status().isTerminal())
                || !socketService.isProcessAlive(stateOpt.get());

            offset = readAndPrintChunk(out, workerLog, offset);
            if (done) {
                break;
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private long readAndPrintChunk(PrintWriter out, Path file, long offset) {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long effectiveOffset = offset;
            if (size < offset) {
                effectiveOffset = 0L;
            }
            if (size <= effectiveOffset) {
                return effectiveOffset;
            }
            channel.position(effectiveOffset);
            ByteBuffer buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
            while (channel.read(buffer) > 0) {
                buffer.flip();
                String text = StandardCharsets.UTF_8.decode(buffer).toString();
                out.print(secretFilter.sanitize(text).value());
                buffer.clear();
            }
            out.flush();
            return channel.position();
        } catch (IOException _) {
            return offset;
        }
    }

    private void printFileOnce(PrintWriter out, Path file) {
        try {
            out.print(secretFilter.sanitize(Files.readString(file)).value());
            out.flush();
        } catch (IOException _) {
            out.println("[dev-agent] Failed to read journal for task: " + taskId);
        }
    }

    public boolean isFollow() {
        return follow;
    }
}
