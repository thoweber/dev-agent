package guru.thomasweber.devagent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import guru.thomasweber.devagent.model.TaskId;
import guru.thomasweber.devagent.model.WorktreeState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Non-locking ephemeral state socket manager reading and atomically writing
 * {@code .git/dev-agent-worktrees/<task-id>/state.json} and resolving {@code worker.log}.
 * Intentionally decoupled from JPA/Panache persistence to guarantee 0ms lock-free CLI telemetry (ADR-006).
 */
@ApplicationScoped
public class StateSocketService {

    private static final Logger LOG = Logger.getLogger(StateSocketService.class);
    private static final long PID_START_TOLERANCE_SECONDS = 2L;

    private final ObjectMapper objectMapper;
    private final SecretRedactionFilter secretFilter;
    private final WorkspaceContextResolver workspaceResolver;

    @Inject
    public StateSocketService(
        ObjectMapper objectMapper,
        SecretRedactionFilter secretFilter,
        WorkspaceContextResolver workspaceResolver
    ) {
        this.objectMapper = Objects.requireNonNullElseGet(objectMapper, StateSocketService::createDefaultObjectMapper);
        this.secretFilter = Objects.requireNonNullElseGet(secretFilter, SecretRedactionFilter::new);
        this.workspaceResolver = Objects.requireNonNullElseGet(workspaceResolver, WorkspaceContextResolver::new);
    }

    public StateSocketService(SecretRedactionFilter secretFilter, WorkspaceContextResolver workspaceResolver) {
        this(createDefaultObjectMapper(), secretFilter, workspaceResolver);
    }

    public StateSocketService() {
        this(createDefaultObjectMapper(), new SecretRedactionFilter(), new WorkspaceContextResolver());
    }

    public Path resolveStateFile(Path repoRoot, TaskId taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return layoutFor(repoRoot).stateFile(taskId);
    }

    public Path resolveStateFile(Path repoRoot, String taskId) {
        return resolveStateFile(repoRoot, TaskId.of(taskId));
    }

    public Path resolveWorkerLogFile(Path repoRoot, TaskId taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return layoutFor(repoRoot).workerLogFile(taskId);
    }

    public Path resolveWorkerLogFile(Path repoRoot, String taskId) {
        return resolveWorkerLogFile(repoRoot, TaskId.of(taskId));
    }

    public void writeHeartbeat(Path repoRoot, WorktreeState state) {
        if (isNull(state)) {
            throw new IllegalArgumentException("WorktreeState must not be null");
        }
        TaskId safeTaskId = TaskId.of(state.taskId());
        WorktreeLayout layout = layoutFor(repoRoot);
        Path stateFile = layout.stateFile(safeTaskId);
        Path worktreeDir = stateFile.getParent();
        Path tempFile = layout.stateTempFile(safeTaskId);

        WorktreeState sanitizedState = sanitizeState(state);
        try {
            Files.createDirectories(worktreeDir);
            WorktreeLayout.validateNoSymlinks(layout.repoRoot(), tempFile);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), sanitizedState);
            moveAtomically(tempFile, stateFile);
        } catch (IOException e) {
            throw new WorkspaceException("Failed to write state.json heartbeat for task " + state.taskId(), e);
        }
    }

    public Optional<WorktreeState> readState(Path repoRoot, TaskId taskId) {
        Path stateFile = resolveStateFile(repoRoot, taskId);
        return readStateFile(stateFile);
    }

    public Optional<WorktreeState> readState(Path repoRoot, String taskId) {
        return readState(repoRoot, TaskId.of(taskId));
    }

    public List<WorktreeState> readAllStates(Path repoRoot) {
        Path worktreesRoot = layoutFor(repoRoot).worktreesRoot();
        if (!Files.isDirectory(worktreesRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(worktreesRoot)) {
            return dirs
                .filter(dir -> Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
                .map(dir -> dir.resolve(WorktreeLayout.STATE_FILE_NAME))
                .map(this::readStateFile)
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(
                    WorktreeState::updatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();
        } catch (IOException e) {
            LOG.warnf(e, "Failed to scan worktree state sockets under %s: %s", worktreesRoot, e.getMessage());
            return List.of();
        }
    }

    public boolean isProcessAlive(WorktreeState state) {
        if (isNull(state) || isNull(state.pid()) || state.pid() <= 0) {
            return false;
        }
        return ProcessHandle.of(state.pid())
            .filter(handle -> matchesPidStart(handle, state.pidStartedAt()))
            .isPresent();
    }

    public static Instant resolvePidStartInstant(Long pid) {
        if (isNull(pid) || pid <= 0) {
            return null;
        }
        return ProcessHandle.of(pid)
            .flatMap(handle -> handle.info().startInstant())
            .orElse(null);
    }

    public static boolean matchesPidStart(ProcessHandle handle, Instant expectedStart) {
        if (isNull(handle) || !handle.isAlive()) {
            return false;
        }
        if (isNull(expectedStart)) {
            return true;
        }
        Optional<Instant> actualStart = handle.info().startInstant();
        if (actualStart.isEmpty()) {
            return true;
        }
        return Duration.between(expectedStart, actualStart.get()).abs().toSeconds() <= PID_START_TOLERANCE_SECONDS;
    }

    private Optional<WorktreeState> readStateFile(Path stateFile) {
        if (!Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        WorktreeLayout.validateNoSymlinks(stateFile.getParent(), stateFile);
        if (!Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(stateFile.toFile(), WorktreeState.class));
        } catch (IOException e) {
            LOG.warnf(e, "Ignoring unreadable or malformed state socket %s: %s", stateFile, e.getMessage());
            return Optional.empty();
        }
    }

    private WorktreeLayout layoutFor(Path repoRoot) {
        Path base = Objects.requireNonNullElseGet(repoRoot, workspaceResolver::resolveRepoRoot);
        return WorktreeLayout.forRoot(base);
    }

    private WorktreeState sanitizeState(WorktreeState state) {
        String cleanMessage = state.lastMessage();
        if (nonNull(cleanMessage) && !cleanMessage.isBlank()) {
            cleanMessage = secretFilter.sanitize(cleanMessage).value();
        }
        String cleanBranch = state.branch();
        if (nonNull(cleanBranch) && !cleanBranch.isBlank()) {
            cleanBranch = secretFilter.sanitize(cleanBranch).value();
        }
        Instant now = Objects.requireNonNullElseGet(state.updatedAt(), Instant::now);
        return state.toBuilder()
            .branch(cleanBranch)
            .lastMessage(cleanMessage)
            .updatedAt(now)
            .build();
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException _) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static String validateTaskId(String taskId) {
        return TaskId.of(taskId).value();
    }

    public static ObjectMapper createDefaultObjectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
