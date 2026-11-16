package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.workspace.PathGuard;
import guru.thomasweber.devagent.workspace.SecretRedactionFilter;
import guru.thomasweber.devagent.workspace.StateSocketService;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import guru.thomasweber.devagent.workspace.WorktreeLayout;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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

        if (Files.isRegularFile(workerLog, LinkOption.NOFOLLOW_LINKS)) {
            streamWorkerLog(out, repoRoot, workerLog);
            return;
        }

        Path canonicalRoot = WorktreeLayout.forRoot(repoRoot).repoRoot();
        Path journalFile = canonicalRoot.resolve(".agents").resolve("runs").resolve(taskId.trim() + ".md");
        WorktreeLayout.validateNoSymlinks(canonicalRoot, journalFile);
        if (Files.isRegularFile(journalFile, LinkOption.NOFOLLOW_LINKS)) {
            printFileOnce(out, journalFile);
            return;
        }

        out.println("[dev-agent] No logs found for task: " + taskId);
    }

    private void streamWorkerLog(PrintWriter out, Path repoRoot, Path workerLog) {
        long offset = readAndPrintChunk(out, workerLog, 0L, !follow);
        if (!follow) {
            return;
        }

        while (!Thread.currentThread().isInterrupted()) {
            Optional<WorktreeState> stateOpt = socketService.readState(repoRoot, taskId.trim());
            boolean done = stateOpt.isEmpty()
                || (nonNull(stateOpt.get().status()) && stateOpt.get().status().isTerminal())
                || !socketService.isProcessAlive(stateOpt.get());

            offset = readAndPrintChunk(out, workerLog, offset, done);
            if (done || !sleepPollInterval()) {
                break;
            }
        }
    }

    private boolean sleepPollInterval() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
            return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private long readAndPrintChunk(PrintWriter out, Path file, long offset, boolean flushPartial) {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long effectiveOffset = offset;
            if (size < offset) {
                effectiveOffset = 0L;
            }
            if (size <= effectiveOffset) {
                return effectiveOffset;
            }
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
            ByteBuffer byteBuffer = ByteBuffer.allocate(READ_BUFFER_SIZE);
            CharBuffer charBuffer = CharBuffer.allocate(READ_BUFFER_SIZE);
            StringBuilder pending = new StringBuilder();

            channel.position(effectiveOffset);
            while (channel.read(byteBuffer) > 0) {
                byteBuffer.flip();
                decoder.decode(byteBuffer, charBuffer, false);
                byteBuffer.compact();
                charBuffer.flip();
                pending.append(charBuffer);
                charBuffer.clear();

                int newlineIdx = pending.lastIndexOf("\n");
                if (newlineIdx >= 0) {
                    out.print(secretFilter.sanitize(pending.substring(0, newlineIdx + 1)).value());
                    pending.delete(0, newlineIdx + 1);
                }
            }

            if (flushPartial) {
                byteBuffer.flip();
                decoder.decode(byteBuffer, charBuffer, true);
                decoder.flush(charBuffer);
                charBuffer.flip();
                pending.append(charBuffer);
                if (!pending.isEmpty()) {
                    out.print(secretFilter.sanitize(pending.toString()).value());
                }
                out.flush();
                return channel.position();
            }
            out.flush();
            int leftoverBytes = byteBuffer.position() + pending.toString().getBytes(StandardCharsets.UTF_8).length;
            return channel.position() - leftoverBytes;
        } catch (IOException _) {
            return offset;
        }
    }

    private void printFileOnce(PrintWriter out, Path file) {
        try {
            String raw = PathGuard.readRegularFileBounded(file, PathGuard.DEFAULT_MAX_TEXT_FILE_BYTES);
            out.print(secretFilter.sanitize(raw).value());
            out.flush();
        } catch (IOException _) {
            out.println("[dev-agent] Failed to read journal for task: " + taskId);
        }
    }

    public boolean isFollow() {
        return follow;
    }
}
