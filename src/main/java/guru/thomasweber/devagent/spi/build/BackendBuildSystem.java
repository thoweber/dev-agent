package guru.thomasweber.devagent.spi.build;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Axis 1 SPI: Backend build tooling execution and output sanitization.
 */
public interface BackendBuildSystem {

    Pattern SAFE_MODULE_PATH = Pattern.compile("^[\\w.-][\\w./-]*$");
    Pattern SAFE_TEST_PATTERN = Pattern.compile("^[\\w$.#,*!+?/-]+$");

    int priority();

    boolean isApplicable(Path projectRoot);

    String resolveExecutable(Path projectRoot);

    List<String> buildCompileCommand(Path projectRoot, String modulePath, String targetDir);

    List<String> buildTestCommand(Path projectRoot, String modulePath, String testPattern, String targetDir);

    SanitizedLog sanitizeOutput(String rawStdout, String rawStderr);

    default SanitizedLog sanitizeOutput(int exitCode, String rawStdout, String rawStderr) {
        return sanitizeOutput(rawStdout, rawStderr);
    }

    static Optional<String> normalizeModulePath(Path projectRoot, String modulePath) {
        if (isNull(modulePath) || modulePath.isBlank()) {
            return Optional.empty();
        }
        String trimmed = modulePath.trim();
        if (trimmed.equals(".") || trimmed.equals("./") || trimmed.equals(".\\")) {
            return Optional.empty();
        }
        String normalized = trimmed.replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.startsWith("-")
            || normalized.startsWith("/")
            || normalized.contains(":")
            || normalized.contains("//")
            || normalized.contains("..")
            || !SAFE_MODULE_PATH.matcher(normalized).matches()) {
            throw new SecurityException("Jailbreak attempt: unsafe backend module path: " + modulePath);
        }
        if (nonNull(projectRoot)) {
            Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
            Path resolved = normalizedRoot.resolve(normalized).normalize();
            if (!resolved.startsWith(normalizedRoot)) {
                throw new SecurityException("Jailbreak attempt: module path escapes repository root: " + modulePath);
            }
        }
        return Optional.of(normalized);
    }

    static Optional<String> normalizeTestPattern(String testPattern) {
        if (isNull(testPattern) || testPattern.isBlank()) {
            return Optional.empty();
        }
        String trimmed = testPattern.trim();
        if (trimmed.startsWith("-")
            || trimmed.contains("..")
            || trimmed.contains("${")
            || trimmed.contains("$(")
            || !SAFE_TEST_PATTERN.matcher(trimmed).matches()) {
            throw new SecurityException("Unsafe backend test pattern: " + testPattern);
        }
        return Optional.of(trimmed);
    }
}
