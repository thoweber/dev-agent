package org.quarkus.agent.workspace;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Strongly-typed, canonical value object encapsulating a verified in-jail repository path.
 * Guarantees that the path physically resides within the repository root, rejects directory
 * traversal attempts, prohibits writes through symbolic links, and protects internal structures.
 */
@RegisterForReflection
public record RepoPath(Path path, Path root) {

    public RepoPath {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(root, "root must not be null");
    }

    /**
     * Resolves a relative child path against this RepoPath, returning a new validated RepoPath.
     *
     * @param relativePath untrusted relative child path
     * @return validated child RepoPath
     */
    public RepoPath resolve(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        return of(this.path.resolve(relativePath), this.root);
    }

    /**
     * Factory that validates and constructs a RepoPath within the given repository root.
     *
     * @param candidatePath raw candidate path
     * @param root repository root directory
     * @return validated RepoPath
     * @throws SecurityException if traversal, symlink, or boundary violations occur
     */
    public static RepoPath of(Path candidatePath, Path root) {
        Objects.requireNonNull(candidatePath, "candidatePath must not be null");
        Objects.requireNonNull(root, "root must not be null");

        String pathStr = candidatePath.toString();
        if (pathStr.contains("..")) {
            throw new SecurityException("Jailbreak attempt: Traversal detected: " + candidatePath);
        }

        if (Files.isSymbolicLink(candidatePath)) {
            throw new SecurityException("Jailbreak attempt: Symbolic links are forbidden for repository paths: " + candidatePath);
        }

        try {
            Path realRoot = root.toRealPath();
            Path normalized = candidatePath.toAbsolutePath().normalize();

            if (!normalized.equals(realRoot)) {
                Path parent = normalized.getParent();
                if (parent != null && Files.exists(parent)) {
                    if (!parent.toRealPath().startsWith(realRoot)) {
                        throw new SecurityException("Jailbreak attempt: Parent path resolves outside root: " + candidatePath);
                    }
                }
            }

            if (Files.exists(candidatePath, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(candidatePath) || !candidatePath.toRealPath().startsWith(realRoot)) {
                    throw new SecurityException("Jailbreak attempt: Path physically resolves outside root: " + candidatePath);
                }
            }

            if (!normalized.startsWith(realRoot)) {
                throw new SecurityException("Jailbreak attempt: Path resolves outside root: " + candidatePath);
            }

            Path relative = realRoot.relativize(normalized);
            String relStr = relative.toString().replace('\\', '/');
            if (relStr.equals(".git") || relStr.startsWith(".git/")) {
                throw new SecurityException("Protected location: modification of .git directory is forbidden: " + candidatePath);
            }

            return new RepoPath(normalized, realRoot);
        } catch (IOException e) {
            throw new SecurityException("Failed to resolve physical canonical path for: " + candidatePath, e);
        }
    }

    public Path toPath() {
        return path;
    }

    public Path relativize() {
        return root.relativize(path);
    }
}
