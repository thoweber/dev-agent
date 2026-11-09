package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.spi.build.BackendBuildSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;

/**
 * Auto-detects workspace boundaries, repository root, build systems, and frontend subsystems.
 * Serves as the single authoritative component for workspace path resolution.
 */
@ApplicationScoped
public class WorkspaceContextResolver {

    private final Path workingDir;
    private final Instance<BackendBuildSystem> backendBuildSystems;

    @Inject
    public WorkspaceContextResolver(Instance<BackendBuildSystem> backendBuildSystems) {
        this(Path.of(System.getProperty("user.dir")), backendBuildSystems);
    }

    public WorkspaceContextResolver(Path workingDir, Instance<BackendBuildSystem> backendBuildSystems) {
        this.workingDir = workingDir;
        this.backendBuildSystems = backendBuildSystems;
    }

    public WorkspaceContextResolver(Path workingDir) {
        this(workingDir, null);
    }

    public WorkspaceContextResolver() {
        this(Path.of(System.getProperty("user.dir")), null);
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
        Path highestBuildRoot = null;
        Path gitRoot = null;

        while (current != null) {
            if (gitRoot == null && Files.exists(current.resolve(".git"))) {
                gitRoot = current;
            }
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("build.gradle.kts"))) {
                highestBuildRoot = current;
            }
            current = current.getParent();
        }

        if (gitRoot != null) {
            return gitRoot;
        }
        if (highestBuildRoot != null) {
            return highestBuildRoot;
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
     * Resolves the applicable backend build system with the highest priority for the given project root.
     *
     * @param projectRoot the repository or project root path
     * @return Optional containing the matching BackendBuildSystem, or empty if none applicable
     */
    public Optional<BackendBuildSystem> resolveBackendBuildSystem(Path projectRoot) {
        if (backendBuildSystems == null || backendBuildSystems.isUnsatisfied() || projectRoot == null) {
            return Optional.empty();
        }
        return backendBuildSystems.stream()
            .filter(bs -> bs.isApplicable(projectRoot))
            .max(Comparator.comparingInt(BackendBuildSystem::priority));
    }

    /**
     * Resolves the applicable backend build system with the highest priority for this workspace repository root.
     *
     * @return Optional containing the matching BackendBuildSystem, or empty if none applicable
     */
    public Optional<BackendBuildSystem> resolveBackendBuildSystem() {
        return resolveBackendBuildSystem(resolveRepoRoot());
    }

    /**
     * Resolves the target directory for sliced agent builds.
     */
    public Path resolveAgentTargetDir() {
        return resolveRepoRoot().resolve("target-agent");
    }

    /**
     * Resolves the dedicated worktree directory for a specific task.
     */
    public Path resolveWorktreeDir(String taskId) {
        return resolveRepoRoot().resolve(".git").resolve("dev-agent-worktrees").resolve(taskId);
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

    public Path getProjectRoot() {
        return resolveRepoRoot();
    }
}
