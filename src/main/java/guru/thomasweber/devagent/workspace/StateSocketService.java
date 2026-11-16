package guru.thomasweber.devagent.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Objects.isNull;

/**
 * Non-locking ephemeral state socket manager reading and atomically writing
 * {@code .git/dev-agent-worktrees/<task-id>/state.json} and resolving {@code worker.log}.
 * Intentionally decoupled from JPA/Panache persistence to guarantee 0ms lock-free CLI telemetry (ADR-006).
 */
@ApplicationScoped
public class StateSocketService {

    private static final Logger LOG = Logger.getLogger(StateSocketService.class);
    private static final String STATE_FILE_NAME = "state.json";
    private static final String STATE_TMP_FILE_NAME = "state.json.tmp";
    private static final String WORKER_LOG_FILE_NAME = "worker.log";
    private static final Pattern SAFE_TASK_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
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
        this.objectMapper = !isNull(objectMapper) ? objectMapper : createDefaultObjectMapper();
        this.secretFilter = !isNull(secretFilter) ? secretFilter : new SecretRedactionFilter();
        this.workspaceResolver = !isNull(workspaceResolver) ? workspaceResolver : new WorkspaceContextResolver();
    }

    public StateSocketService(SecretRedactionFilter secretFilter, WorkspaceContextResolver workspaceResolver) {
        this(createDefaultObjectMapper(), secretFilter, workspaceResolver);
    }

    public StateSocketService() {
        this(createDefaultObjectMapper(), new SecretRedactionFilter(), new WorkspaceContextResolver());
    }

    public Path resolveStateFile(Path repoRoot, String taskId) {
        return resolveWorktreeFile(repoRoot, taskId, STATE_FILE_NAME);
    }

    public Path resolveWorkerLogFile(Path repoRoot, String taskId) {
        return resolveWorktreeFile(repoRoot, taskId, WORKER_LOG_FILE_NAME);
    }

    public void writeHeartbeat(Path repoRoot, WorktreeState state) {
        if (isNull(state)) {
            throw new IllegalArgumentException("WorktreeState must not be null");
        }
        Path stateFile = resolveStateFile(repoRoot, state.taskId());
        Path worktreeDir = stateFile.getParent();
        Path tempFile = worktreeDir.resolve(STATE_TMP_FILE_NAME);

        WorktreeState sanitizedState = sanitizeState(state);
        try {
            Files.createDirectories(worktreeDir);
            validateNoSymlink(tempFile);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), sanitizedState);
            moveAtomically(tempFile, stateFile);
        } catch (IOException e) {
            throw new WorkspaceException("Failed to write state.json heartbeat for task " + state.taskId(), e);
        }
    }

    public Optional<WorktreeState> readState(Path repoRoot, String taskId) {
        Path stateFile = resolveStateFile(repoRoot, taskId);
        return readStateFile(stateFile);
    }

    public List<WorktreeState> readAllStates(Path repoRoot) {
        Path effectiveRoot = resolveEffectiveRoot(repoRoot);
        Path worktreesRoot = effectiveRoot.resolve(".git").resolve("dev-agent-worktrees");
        if (!Files.isDirectory(worktreesRoot, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(worktreesRoot)) {
            return dirs
                .filter(dir -> Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
                .map(dir -> dir.resolve(STATE_FILE_NAME))
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
        validateNoSymlink(stateFile);
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

    private Path resolveWorktreeFile(Path repoRoot, String taskId, String fileName) {
        String safeTaskId = validateTaskId(taskId);
        Path effectiveRoot = resolveEffectiveRoot(repoRoot);
        Path worktreesRoot = effectiveRoot.resolve(".git").resolve("dev-agent-worktrees");
        Path worktreeDir = worktreesRoot.resolve(safeTaskId);
        Path targetFile = worktreeDir.resolve(fileName);

        validateWorktreePathSymlinks(effectiveRoot, targetFile);
        return targetFile;
    }

    public static void validateWorktreePathSymlinks(Path boundaryRoot, Path target) {
        Path normalizedBoundary = boundaryRoot.toAbsolutePath().normalize();
        Path current = target.toAbsolutePath().normalize();
        while (!isNull(current) && !current.equals(normalizedBoundary)) {
            validateNoSymlink(current);
            current = current.getParent();
        }
    }

    private Path resolveEffectiveRoot(Path repoRoot) {
        Path base = !isNull(repoRoot) ? repoRoot : workspaceResolver.resolveRepoRoot();
        try {
            return base.toRealPath();
        } catch (IOException _) {
            return base.toAbsolutePath().normalize();
        }
    }

    private WorktreeState sanitizeState(WorktreeState state) {
        String cleanMessage = (!isNull(state.lastMessage()) && !state.lastMessage().isBlank())
            ? secretFilter.sanitize(state.lastMessage()).value()
            : state.lastMessage();
        String cleanBranch = (!isNull(state.branch()) && !state.branch().isBlank())
            ? secretFilter.sanitize(state.branch()).value()
            : state.branch();
        Instant now = !isNull(state.updatedAt()) ? state.updatedAt() : Instant.now();
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
        if (isNull(taskId)
            || taskId.isBlank()
            || taskId.contains("..")
            || !SAFE_TASK_ID_PATTERN.matcher(taskId).matches()) {
            throw new SecurityException("Jailbreak attempt: invalid task ID: " + taskId);
        }
        return taskId;
    }

    private static void validateNoSymlink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new SecurityException("Jailbreak attempt: symbolic links are forbidden in state sockets: " + path);
        }
    }

    public static ObjectMapper createDefaultObjectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
