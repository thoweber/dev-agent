package guru.thomasweber.devagent.workspace;

import guru.thomasweber.devagent.spi.build.BackendBuildSystem;
import guru.thomasweber.devagent.spi.build.SanitizedLog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Application service orchestrating backend build execution across dynamic build systems (Axis 1 SPI).
 * Coordinates repository discovery, command synthesis, subprocess lifecycle with timeouts,
 * and build output sanitization.
 */
@ApplicationScoped
public class BackendBuildService {

    public static final String DEFAULT_TARGET_DIR = "target-agent";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final WorkspaceContextResolver contextResolver;
    private final SubprocessExecutor subprocessExecutor;

    @Inject
    public BackendBuildService(WorkspaceContextResolver contextResolver, SubprocessExecutor subprocessExecutor) {
        this.contextResolver = contextResolver;
        this.subprocessExecutor = subprocessExecutor;
    }

    /**
     * Compiles the backend project in the workspace repository root.
     *
     * @param modulePath optional relative module path within a multi-module reactor
     * @return SanitizedLog containing exit code, clean stdout/stderr, and isolated error lines
     */
    public SanitizedLog compile(String modulePath) {
        return compile(contextResolver.resolveRepoRoot(), modulePath, DEFAULT_TARGET_DIR);
    }

    /**
     * Compiles the backend project with explicit project root and target build directory.
     *
     * @param projectRoot the repository or project root directory
     * @param modulePath optional relative module path within a multi-module reactor
     * @param targetDir target build output directory name (e.g. target-agent)
     * @return SanitizedLog containing exit code, clean stdout/stderr, and isolated error lines
     */
    public SanitizedLog compile(Path projectRoot, String modulePath, String targetDir) {
        Path effectiveRoot = (projectRoot != null) ? projectRoot : contextResolver.resolveRepoRoot();
        String effectiveTarget = (targetDir != null && !targetDir.isBlank()) ? targetDir : DEFAULT_TARGET_DIR;

        BackendBuildSystem buildSystem = resolveBuildSystem(effectiveRoot);
        List<String> command = buildSystem.buildCompileCommand(effectiveRoot, modulePath, effectiveTarget);

        return executeAndSanitize(buildSystem, command, effectiveRoot);
    }

    /**
     * Executes backend tests in the workspace repository root.
     *
     * @param testPattern class name pattern or method pattern (e.g. OrderServiceTest or *Test)
     * @param modulePath optional relative module path within a multi-module reactor
     * @return SanitizedLog containing exit code, clean stdout/stderr, and isolated error lines
     */
    public SanitizedLog test(String testPattern, String modulePath) {
        return test(contextResolver.resolveRepoRoot(), modulePath, testPattern, DEFAULT_TARGET_DIR);
    }

    /**
     * Executes backend tests with explicit project root and target build directory.
     *
     * @param projectRoot the repository or project root directory
     * @param modulePath optional relative module path within a multi-module reactor
     * @param testPattern class name pattern or method pattern (e.g. OrderServiceTest or *Test)
     * @param targetDir target build output directory name (e.g. target-agent)
     * @return SanitizedLog containing exit code, clean stdout/stderr, and isolated error lines
     */
    public SanitizedLog test(Path projectRoot, String modulePath, String testPattern, String targetDir) {
        Path effectiveRoot = (projectRoot != null) ? projectRoot : contextResolver.resolveRepoRoot();
        String effectiveTarget = (targetDir != null && !targetDir.isBlank()) ? targetDir : DEFAULT_TARGET_DIR;

        BackendBuildSystem buildSystem = resolveBuildSystem(effectiveRoot);
        List<String> command = buildSystem.buildTestCommand(effectiveRoot, modulePath, testPattern, effectiveTarget);

        return executeAndSanitize(buildSystem, command, effectiveRoot);
    }

    private SanitizedLog executeAndSanitize(BackendBuildSystem buildSystem, List<String> command, Path workingDir) {
        ProcessResult result = subprocessExecutor.execute(command, workingDir, DEFAULT_TIMEOUT);
        SanitizedLog sanitized = buildSystem.sanitizeOutput(result.exitCode(), result.stdOut(), result.stdErr());

        if (result.timedOut() || result.truncated()) {
            List<String> errors = new ArrayList<>(sanitized.errorLines());
            if (result.timedOut()) {
                errors.add(0, "Build process timed out after " + DEFAULT_TIMEOUT.toMinutes() + " minutes");
            }
            if (result.truncated()) {
                errors.add("Build process output was truncated due to buffer limits");
            }
            int effectiveExitCode = (result.timedOut() && sanitized.exitCode() == 0) ? 124 : sanitized.exitCode();
            return sanitized.toBuilder()
                .exitCode(effectiveExitCode)
                .errorCount(errors.size())
                .errorLines(Collections.unmodifiableList(errors))
                .build();
        }

        return sanitized;
    }

    private BackendBuildSystem resolveBuildSystem(Path projectRoot) {
        return contextResolver.resolveBackendBuildSystem(projectRoot)
            .orElseThrow(() -> new IllegalStateException("No applicable backend build system found for project root: " + projectRoot));
    }
}
