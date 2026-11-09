package guru.thomasweber.devagent.spi.build;

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

/**
 * Axis 1 SPI implementation for Maven backend build tooling execution, command synthesis,
 * target directory slicing, and build output log sanitization.
 */
@ApplicationScoped
@RegisterForReflection
public class MavenBuildSystem implements BackendBuildSystem {

    public static final int DEFAULT_PRIORITY = 100;
    public static final String DEFAULT_TARGET_DIR = "target-agent";
    public static final String SUREFIRE_MEMORY_ARGS = "-DargLine=-Xmx512m -XX:+TieredCompilation -XX:TieredStopAtLevel=1";
    public static final String POM_XML = "pom.xml";

    private static final String MVNW_UNIX = "./mvnw";
    private static final String MVNW_WIN = "mvnw.cmd";
    private static final String MVN_FALLBACK = "mvn";

    private final MavenOutputParser outputParser;

    @Inject
    public MavenBuildSystem(MavenOutputParser outputParser) {
        this.outputParser = outputParser != null ? outputParser : new MavenOutputParser();
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
        if (projectRoot == null) {
            return false;
        }
        Path pom = projectRoot.resolve(POM_XML);
        return Files.exists(pom, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public String resolveExecutable(Path projectRoot) {
        if (projectRoot == null) {
            return MVN_FALLBACK;
        }
        boolean isWindows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (isWindows) {
            if (Files.exists(projectRoot.resolve("mvnw.cmd"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(projectRoot.resolve("mvnw.bat"), LinkOption.NOFOLLOW_LINKS)) {
                return MVNW_WIN;
            }
        } else {
            Path unixWrapper = projectRoot.resolve("mvnw");
            if (Files.exists(unixWrapper, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(unixWrapper, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isExecutable(unixWrapper)) {
                    unixWrapper.toFile().setExecutable(true, false);
                }
                if (Files.isExecutable(unixWrapper)) {
                    return MVNW_UNIX;
                }
            }
        }
        return MVN_FALLBACK;
    }

    @Override
    public List<String> buildCompileCommand(Path projectRoot, String modulePath, String targetDir) {
        List<String> command = new ArrayList<>();
        command.add(resolveExecutable(projectRoot));
        command.add("-B");

        String effectiveTargetDir = (targetDir != null && !targetDir.isBlank()) ? targetDir.trim() : DEFAULT_TARGET_DIR;
        command.add("-Dproject.build.directory=" + effectiveTargetDir);

        if (isNotBlankModule(modulePath)) {
            command.add("-pl");
            command.add(modulePath.trim());
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

        String effectiveTargetDir = (targetDir != null && !targetDir.isBlank()) ? targetDir.trim() : DEFAULT_TARGET_DIR;
        command.add("-Dproject.build.directory=" + effectiveTargetDir);
        command.add(SUREFIRE_MEMORY_ARGS);

        if (isNotBlankModule(modulePath)) {
            command.add("-pl");
            command.add(modulePath.trim());
            command.add("-am");
        }

        command.add("test");

        if (testPattern != null && !testPattern.isBlank()) {
            command.add("-Dtest=" + testPattern.trim());
        }

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

    private static boolean isNotBlankModule(String modulePath) {
        if (modulePath == null || modulePath.isBlank()) {
            return false;
        }
        String trimmed = modulePath.trim();
        return !trimmed.equals(".") && !trimmed.equals("./") && !trimmed.equals(".\\");
    }
}
