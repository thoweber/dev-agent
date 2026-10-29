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

    private WorktreeTestFixture() {}

    public static Path resolveWorktree(Path repoRoot, String taskId) {
        return repoRoot.resolve(".git").resolve("dev-agent-worktrees").resolve(taskId);
    }

    public static Path createWorktree(Path repoRoot, String taskId) throws IOException {
        Path worktreeDir = resolveWorktree(repoRoot, taskId);
        Files.createDirectories(worktreeDir);
        return worktreeDir;
    }

    public static void deleteWorktree(Path repoRoot, String taskId) throws IOException {
        Path worktreeDir = resolveWorktree(repoRoot, taskId);
        deleteRecursively(worktreeDir);
    }

    public static void cleanAllWorktrees(Path repoRoot) throws IOException {
        Path worktreeBase = repoRoot.resolve(".git").resolve("dev-agent-worktrees");
        if (Files.exists(worktreeBase)) {
            try (Stream<Path> stream = Files.list(worktreeBase)) {
                for (Path p : stream.toList()) {
                    deleteRecursively(p);
                }
            }
        }
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
