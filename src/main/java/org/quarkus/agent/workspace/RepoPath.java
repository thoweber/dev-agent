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

        validateNoTraversal(path);

        try {
            Path realRoot = root.toRealPath();
            Path normRoot = root.toAbsolutePath().normalize();
            Path absoluteCandidate;
            if (path.isAbsolute()) {
                Path normPath = path.normalize();
                if (normPath.startsWith(normRoot)) {
                    Path rel = normRoot.relativize(normPath);
                    absoluteCandidate = realRoot.resolve(rel).normalize();
                } else {
                    absoluteCandidate = normPath;
                }
            } else {
                absoluteCandidate = realRoot.resolve(path).normalize();
            }

            validateNoAncestorSymlinks(absoluteCandidate, realRoot, path);
            Path canonicalCandidate = resolveCanonicalCandidate(absoluteCandidate, path);

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

    private static void validateNoTraversal(Path path) {
        for (Path segment : path) {
            if (segment.toString().equals("..")) {
                throw new SecurityException("Jailbreak attempt: Traversal detected: " + path);
            }
        }
    }

    private static void validateNoAncestorSymlinks(Path candidate, Path realRoot, Path originalPath) {
        Path curr = candidate;
        while (curr != null && !curr.equals(realRoot)) {
            if (Files.exists(curr, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(curr)) {
                throw new SecurityException("Jailbreak attempt: Symbolic links are forbidden for repository paths: " + originalPath);
            }
            curr = curr.getParent();
        }
    }

    private static Path resolveCanonicalCandidate(Path candidate, Path originalPath) throws IOException {
        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(candidate)) {
                throw new SecurityException("Jailbreak attempt: Symbolic links are forbidden for repository paths: " + originalPath);
            }
            return candidate.toRealPath();
        }
        return resolveMissingCandidate(candidate, originalPath);
    }

    private static Path resolveMissingCandidate(Path candidate, Path originalPath) throws IOException {
        Path existingParent = findExistingAncestor(candidate);
        if (existingParent == null) {
            return candidate;
        }
        if (Files.isSymbolicLink(existingParent)) {
            throw new SecurityException("Jailbreak attempt: Symbolic links are forbidden for repository paths: " + originalPath);
        }
        Path realParent = existingParent.toRealPath();
        Path relativeRemainder = existingParent.relativize(candidate);
        return realParent.resolve(relativeRemainder).normalize();
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
