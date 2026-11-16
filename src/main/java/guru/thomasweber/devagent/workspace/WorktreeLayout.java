package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.model.TaskId;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Authoritative filesystem layout and symlink validator for isolated Git worktrees under
 * {@code <repoRoot>/.git/dev-agent-worktrees/<taskId>/}.
 */
public record WorktreeLayout(Path repoRoot) {

    public static final String GIT_DIR_NAME = ".git";
    public static final String WORKTREES_DIR_NAME = "dev-agent-worktrees";
    public static final String STATE_FILE_NAME = "state.json";
    public static final String WORKER_LOG_FILE_NAME = "worker.log";

    public WorktreeLayout {
        if (isNull(repoRoot)) {
            throw new IllegalArgumentException("repoRoot must not be null");
        }
        repoRoot = Objects.requireNonNull(PathGuard.toRealOrNormalized(repoRoot), "repoRoot must not be null");
    }

    public static WorktreeLayout forRoot(Path repoRoot) {
        return new WorktreeLayout(repoRoot);
    }

    public Path worktreesRoot() {
        return repoRoot.resolve(GIT_DIR_NAME).resolve(WORKTREES_DIR_NAME);
    }

    public Path worktreeDir(TaskId taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Path dir = Objects.requireNonNull(worktreesRoot().resolve(taskId.value()));
        validateNoSymlinks(repoRoot, dir);
        return dir;
    }

    public Path stateFile(TaskId taskId) {
        Path file = worktreeDir(taskId).resolve(STATE_FILE_NAME);
        validateSingleNoSymlink(file);
        return file;
    }

    public Path workerLogFile(TaskId taskId) {
        Path file = worktreeDir(taskId).resolve(WORKER_LOG_FILE_NAME);
        validateSingleNoSymlink(file);
        return file;
    }

    public static void validateNoSymlinks(Path boundaryRoot, Path target) {
        if (isNull(target)) {
            return;
        }
        Path normalizedBoundary = null;
        if (nonNull(boundaryRoot)) {
            normalizedBoundary = boundaryRoot.toAbsolutePath().normalize();
        }
        Path current = target.toAbsolutePath().normalize();
        while (nonNull(current) && !current.equals(normalizedBoundary)) {
            validateSingleNoSymlink(current);
            current = current.getParent();
        }
    }

    public static Optional<Path> detectWorktreeRoot(Path workingDir) {
        if (isNull(workingDir)) {
            return Optional.empty();
        }
        Path normalized = workingDir.toAbsolutePath().normalize();
        for (int i = 0; i < normalized.getNameCount() - 2; i++) {
            if (GIT_DIR_NAME.equals(normalized.getName(i).toString())
                && WORKTREES_DIR_NAME.equals(normalized.getName(i + 1).toString())) {
                Path root = normalized.getRoot();
                Path worktreeRoot = resolvePrefix(root, normalized.subpath(0, i + 3));
                Path boundaryRoot = null;
                if (i > 0) {
                    boundaryRoot = resolvePrefix(root, normalized.subpath(0, i));
                } else {
                    boundaryRoot = root;
                }
                validateNoSymlinks(boundaryRoot, worktreeRoot);
                return Optional.of(worktreeRoot);
            }
        }
        return Optional.empty();
    }

    private static Path resolvePrefix(Path root, Path subpath) {
        if (nonNull(root)) {
            return root.resolve(subpath);
        }
        return subpath;
    }

    private static void validateSingleNoSymlink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new SecurityException("Forbidden symbolic link in worktree path: " + path);
        }
    }
}
