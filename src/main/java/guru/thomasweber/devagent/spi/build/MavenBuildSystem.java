package guru.thomasweber.devagent.spi.build;

import guru.thomasweber.devagent.workspace.TargetDir;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.isNull;

/**
 * Axis 1 SPI implementation for Maven backend build tooling execution, command synthesis,
 * target directory slicing, and build output log sanitization.
 */
@ApplicationScoped
@RegisterForReflection
public class MavenBuildSystem implements BackendBuildSystem {

    public static final int DEFAULT_PRIORITY = 100;
    public static final String DEFAULT_TARGET_DIR = TargetDir.DEFAULT_VALUE;
    public static final String SUREFIRE_MEMORY_ARGS = "-DargLine=-Xmx512m -XX:+TieredCompilation -XX:TieredStopAtLevel=1";
    public static final String POM_XML = "pom.xml";

    private static final String MVNW_WIN = "mvnw.cmd";
    private static final String MVNW_BAT = "mvnw.bat";
    private static final String MVN_FALLBACK = "mvn";

    private final MavenOutputParser outputParser;

    @Inject
    public MavenBuildSystem(MavenOutputParser outputParser) {
        this.outputParser = Objects.requireNonNullElseGet(outputParser, MavenOutputParser::new);
    }

    public MavenBuildSystem() {
        this(new MavenOutputParser());
    }

    @Override
    public int priority() {
        return DEFAULT_PRIORITY;
    }

    @Override
    public boolean isApplicable(Path projectRoot) {
        if (isNull(projectRoot)) {
            return false;
        }
        Path pom = projectRoot.resolve(POM_XML);
        return Files.exists(pom, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public String resolveExecutable(Path projectRoot) {
        if (isNull(projectRoot)) {
            return MVN_FALLBACK;
        }
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (isWindows) {
            return resolveWindowsWrapper(projectRoot);
        }
        return resolveUnixWrapper(projectRoot);
    }

    private String resolveWindowsWrapper(Path projectRoot) {
        Path winCmd = projectRoot.resolve(MVNW_WIN);
        if (Files.isRegularFile(winCmd, LinkOption.NOFOLLOW_LINKS)) {
            return winCmd.toAbsolutePath().toString();
        }
        Path winBat = projectRoot.resolve(MVNW_BAT);
        if (Files.isRegularFile(winBat, LinkOption.NOFOLLOW_LINKS)) {
            return winBat.toAbsolutePath().toString();
        }
        return MVN_FALLBACK;
    }

    private String resolveUnixWrapper(Path projectRoot) {
        Path unixWrapper = projectRoot.resolve("mvnw");
        if (Files.isRegularFile(unixWrapper, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isExecutable(unixWrapper) && !tryMakeExecutable(unixWrapper) && !Files.isExecutable(unixWrapper)) {
                return MVN_FALLBACK;
            }
            if (Files.isExecutable(unixWrapper)) {
                return unixWrapper.toAbsolutePath().toString();
            }
        }
        return MVN_FALLBACK;
    }

    private boolean tryMakeExecutable(Path unixWrapper) {
        return unixWrapper.toFile().setExecutable(true, true);
    }

    @Override
    public List<String> buildCompileCommand(Path projectRoot, String modulePath, String targetDir) {
        List<String> command = new ArrayList<>();
        command.add(resolveExecutable(projectRoot));
        command.add("-B");

        String effectiveTargetDir = TargetDir.of(targetDir).value();
        command.add("-Dproject.build.directory=" + effectiveTargetDir);

        Optional<String> safeModule = BackendBuildSystem.normalizeModulePath(projectRoot, modulePath);
        if (safeModule.isPresent()) {
            command.add("-pl");
            command.add(safeModule.get());
            command.add("-am");
        }

        command.add("test-compile");
        return Collections.unmodifiableList(command);
    }

    @Override
    public List<String> buildTestCommand(Path projectRoot, String modulePath, String testPattern, String targetDir) {
        List<String> command = new ArrayList<>();
        command.add(resolveExecutable(projectRoot));
        command.add("-B");

        String effectiveTargetDir = TargetDir.of(targetDir).value();
        command.add("-Dproject.build.directory=" + effectiveTargetDir);
        command.add(SUREFIRE_MEMORY_ARGS);

        Optional<String> safeModule = BackendBuildSystem.normalizeModulePath(projectRoot, modulePath);
        if (safeModule.isPresent()) {
            command.add("-pl");
            command.add(safeModule.get());
            command.add("-am");
        }

        command.add("test");

        Optional<String> safeTestPattern = BackendBuildSystem.normalizeTestPattern(testPattern);
        safeTestPattern.ifPresent(pattern -> command.add("-Dtest=" + pattern));

        return Collections.unmodifiableList(command);
    }

    @Override
    public SanitizedLog sanitizeOutput(String rawStdout, String rawStderr) {
        return outputParser.parse(rawStdout, rawStderr);
    }

    @Override
    public SanitizedLog sanitizeOutput(int exitCode, String rawStdout, String rawStderr) {
        return outputParser.parse(exitCode, rawStdout, rawStderr);
    }
}
