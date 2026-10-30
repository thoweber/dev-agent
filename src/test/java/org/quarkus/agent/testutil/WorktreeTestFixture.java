package org.quarkus.agent.testutil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Fixture utility to create, inspect, and cleanly purge physical worktrees in .git/dev-agent-worktrees/ during tests.
 */
public final class WorktreeTestFixture {

    private static final java.util.Set<Path> CREATED_WORKTREES = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private WorktreeTestFixture() {}

    public static Path resolveWorktree(Path repoRoot, String taskId) {
        return repoRoot.resolve(".git").resolve("dev-agent-worktrees").resolve(taskId);
    }

    public static Path createWorktree(Path repoRoot, String taskId) throws IOException {
        Path worktreeDir = resolveWorktree(repoRoot, taskId);
        Files.createDirectories(worktreeDir);
        CREATED_WORKTREES.add(worktreeDir);
        return worktreeDir;
    }

    public static void deleteWorktree(Path repoRoot, String taskId) throws IOException {
        Path worktreeDir = resolveWorktree(repoRoot, taskId);
        CREATED_WORKTREES.remove(worktreeDir);
        deleteRecursively(worktreeDir);
    }

    public static void cleanAllWorktrees(Path repoRoot) throws IOException {
        for (Path p : CREATED_WORKTREES) {
            deleteRecursively(p);
        }
        CREATED_WORKTREES.clear();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
