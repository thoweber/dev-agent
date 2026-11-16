package guru.thomasweber.devagent.workspace;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.regex.Pattern;

import static java.util.Objects.isNull;

/**
 * Strongly-typed, self-validating value object representing the sliced backend build directory
 * (defaults to {@code target-agent}). Normalizes blank values to the default and rejects
 * path traversal, glob metacharacters, flag prefixes, or absolute paths.
 */
@RegisterForReflection
public record TargetDir(String value) {

    public static final String DEFAULT_VALUE = "target-agent";
    private static final Pattern SAFE_TARGET_PATTERN = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._/-]*$");
    public static final TargetDir DEFAULT = new TargetDir(DEFAULT_VALUE);

    public TargetDir {
        if (isNull(value) || value.isBlank()) {
            value = DEFAULT_VALUE;
        } else {
            value = normalize(value);
        }
        if (value.isBlank()
            || value.equals(".")
            || value.contains("..")
            || value.contains("//")
            || !SAFE_TARGET_PATTERN.matcher(value).matches()) {
            throw new SecurityException("Jailbreak attempt: invalid target directory: " + value);
        }
    }

    public static TargetDir of(String raw) {
        if (isNull(raw) || raw.isBlank()) {
            return DEFAULT;
        }
        return new TargetDir(raw);
    }

    public String excludeEntry() {
        return value + "/";
    }

    @Override
    public String toString() {
        return value;
    }

    private static String normalize(String raw) {
        String normalized = raw.trim().replace('\\', '/');
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
