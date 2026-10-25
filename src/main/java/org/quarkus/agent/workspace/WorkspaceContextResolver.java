package org.quarkus.agent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import lombok.RequiredArgsConstructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Auto-detects workspace boundaries, repository root, build systems, and frontend subsystems.
 * Serves as the single authoritative component for workspace path resolution.
 */
@ApplicationScoped
@RequiredArgsConstructor
public class WorkspaceContextResolver {

    private final Path workingDir;

    public WorkspaceContextResolver() {
        this(Path.of(System.getProperty("user.dir")));
    }

    /**
     * Resolves the effective workspace root from CLI arguments and optional parent command option.
     *
     * @param cliDir direct command option string
     * @param parentDir inherited parent command option string
     * @return canonical Path to the effective workspace root
     */
    public static Path resolveEffectiveRoot(String cliDir, String parentDir) {
        if (cliDir != null && !cliDir.equals(".") && !cliDir.isBlank()) {
            return Paths.get(cliDir).toAbsolutePath().normalize();
        }
        if (parentDir != null && !parentDir.equals(".") && !parentDir.isBlank()) {
            return Paths.get(parentDir).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
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

    /**
     * Resolves the .agents metadata directory.
     */
    public Path resolveAgentsDir() {
        return resolveRepoRoot().resolve(".agents");
    }

    /**
     * Resolves the .agents/rules directory.
     */
    public Path resolveRulesDir() {
        return resolveAgentsDir().resolve("rules");
    }

    /**
     * Resolves the .agents/dev-agent.yaml declarative config path.
     */
    public Path resolveConfigFile() {
        return resolveAgentsDir().resolve("dev-agent.yaml");
    }

    /**
     * Creates a jail validator anchored to this workspace's repository root.
     */
    public WorkspaceJailValidator getJailValidator() {
        return new WorkspaceJailValidator(resolveRepoRoot());
    }

    public Path getWorkingDir() {
        return workingDir;
    }
}
