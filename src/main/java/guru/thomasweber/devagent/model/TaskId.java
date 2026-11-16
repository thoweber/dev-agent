package guru.thomasweber.devagent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.util.Objects.isNull;

/**
 * Strongly-typed, self-validating domain value object representing a canonical task identifier.
 * Enforces strict path-safe characters at construction time to prevent directory traversal and
 * malformed branch or socket paths.
 */
@RegisterForReflection
public record TaskId(@JsonValue String value) {

    private static final Pattern SAFE_TASK_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public TaskId {
        if (isNull(value)
            || value.isBlank()
            || value.contains("..")
            || !SAFE_TASK_ID_PATTERN.matcher(value).matches()) {
            throw new SecurityException("Jailbreak attempt: invalid task ID: " + value);
        }
    }

    @JsonCreator
    public static TaskId of(String raw) {
        return new TaskId(raw);
    }

    public static TaskId random() {
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        return new TaskId("TASK-" + suffix);
    }

    public String idString() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
