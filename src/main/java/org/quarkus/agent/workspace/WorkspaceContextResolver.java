package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Auto-detects workspace boundaries, repository root, build systems, and frontend subsystems.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class WorkspaceContextResolver {

    private final Path workingDir;

    public WorkspaceContextResolver() {
        this(Path.of(System.getProperty("user.dir")));
    }

    /**
     * Finds the repository root by traversing parent directories up to root.
     * Looks for .git, pom.xml, or build.gradle.
     *
     * @return Path to repository root, or fallback to workingDir if not found
     */
    public Path resolveRepoRoot() {
        Path current = workingDir.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git")) ||
                Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("build.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        return workingDir.toAbsolutePath().normalize();
    }

    /**
     * Scans for frontend subsystem directories.
     *
     * @return Optional containing frontend root Path if detected
     */
    public Optional<Path> resolveFrontendRoot() {
        Path root = resolveRepoRoot();
        for (String candidate : new String[]{"frontend", "ui", "src/main/webapp", "webapp"}) {
            Path candidatePath = root.resolve(candidate);
            if (Files.isDirectory(candidatePath) &&
                (Files.exists(candidatePath.resolve("package.json")) || Files.exists(candidatePath.resolve("angular.json")))) {
                return Optional.of(candidatePath);
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the target directory for sliced agent builds.
     */
    public Path resolveAgentTargetDir() {
        return resolveRepoRoot().resolve("target-agent");
    }

    public Path getWorkingDir() {
        return workingDir;
    }
}
