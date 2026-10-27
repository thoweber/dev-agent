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

        // Reject traversal segments (only components that are exactly "..")
        for (Path segment : path) {
            if (segment.toString().equals("..")) {
                throw new SecurityException("Jailbreak attempt: Traversal detected: " + path);
            }
        }

        try {
            Path realRoot = root.toRealPath();
            Path absoluteCandidate = path.isAbsolute() ? path.normalize() : realRoot.resolve(path).normalize();

            // Verify that no existing component in the path hierarchy is a symbolic link
            Path curr = absoluteCandidate;
            while (curr != null && !curr.equals(realRoot)) {
                if (Files.exists(curr, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(curr)) {
                    throw new SecurityException("Jailbreak attempt: Symbolic links are forbidden for repository paths: " + path);
                }
                curr = curr.getParent();
            }

            Path canonicalCandidate;
            if (Files.exists(absoluteCandidate, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(absoluteCandidate)) {
                    throw new SecurityException("Jailbreak attempt: Symbolic links are forbidden for repository paths: " + path);
                }
                canonicalCandidate = absoluteCandidate.toRealPath();
            } else {
                Path existingParent = findExistingAncestor(absoluteCandidate);
                if (existingParent != null) {
                    if (Files.isSymbolicLink(existingParent)) {
                        throw new SecurityException("Jailbreak attempt: Symbolic links are forbidden for repository paths: " + path);
                    }
                    Path realParent = existingParent.toRealPath();
                    Path relativeRemainder = existingParent.relativize(absoluteCandidate);
                    canonicalCandidate = realParent.resolve(relativeRemainder).normalize();
                } else {
                    canonicalCandidate = absoluteCandidate;
                }
            }

            if (!canonicalCandidate.startsWith(realRoot)) {
                throw new SecurityException("Jailbreak attempt: Path resolves outside root: " + path);
            }

            validateProtectedLocations(canonicalCandidate, realRoot);

            path = canonicalCandidate;
            root = realRoot;
        } catch (IOException e) {
            throw new SecurityException("Failed to resolve physical canonical path for: " + path, e);
        }
    }

    private static Path findExistingAncestor(Path p) {
        Path ancestor = p.getParent();
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        return ancestor;
    }

    private static void validateProtectedLocations(Path canonicalCandidate, Path realRoot) {
        Path relative = realRoot.relativize(canonicalCandidate);
        String relStr = relative.toString().replace('\\', '/');
        if (relStr.equals(".git") || relStr.startsWith(".git/")) {
            throw new SecurityException("Protected location: modification of .git directory is forbidden: " + canonicalCandidate);
        }
    }

    public RepoPath resolve(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath must not be null");
        return of(this.path.resolve(relativePath), this.root);
    }

    public static RepoPath of(Path candidatePath, Path root) {
        return new RepoPath(candidatePath, root);
    }

    public Path toPath() {
        return path;
    }

    public Path relativize() {
        return root.relativize(path);
    }
}
