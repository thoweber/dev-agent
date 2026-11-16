package guru.thomasweber.devagent.workspace;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

import static java.util.Objects.isNull;

/**
 * Java 25 {@link ScopedValue}-backed context binding the active isolated worktree execution root
 * separately from the primary repository root.
 */
public final class WorktreeExecutionContext {

    private static final ScopedValue<Path> ACTIVE_WORKTREE_ROOT = ScopedValue.newInstance();

    private WorktreeExecutionContext() {
        // Utility class
    }

    public static Optional<Path> currentWorktreeRoot() {
        if (ACTIVE_WORKTREE_ROOT.isBound()) {
            return Optional.of(ACTIVE_WORKTREE_ROOT.get());
        }
        return Optional.empty();
    }

    public static <T> T runInWorktree(Path worktreeRoot, Callable<T> action) throws Exception {
        if (isNull(worktreeRoot)) {
            throw new IllegalArgumentException("worktreeRoot must not be null");
        }
        return ScopedValue.where(ACTIVE_WORKTREE_ROOT, worktreeRoot).call(action::call);
    }

    public static void runInWorktree(Path worktreeRoot, Runnable action) {
        if (isNull(worktreeRoot)) {
            throw new IllegalArgumentException("worktreeRoot must not be null");
        }
        ScopedValue.where(ACTIVE_WORKTREE_ROOT, worktreeRoot).run(action);
    }
}
