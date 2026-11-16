package guru.thomasweber.devagent.workspace;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Objects;

import static java.util.Objects.isNull;

/**
 * Strongly-typed value object representing credential-scrubbed text output.
 * Ensures raw unredacted tool execution outputs cannot be written to persistence sinks
 * or exported runbooks without explicit filtration.
 */
@RegisterForReflection
public record SanitizedOutput(String value) {

    public SanitizedOutput {
        value = Objects.requireNonNullElse(value, "");
    }

    public static SanitizedOutput of(String rawText, SecretRedactionFilter filter) {
        Objects.requireNonNull(filter, "SecretRedactionFilter cannot be null");
        if (isNull(rawText) || rawText.isBlank()) {
            return empty();
        }
        return new SanitizedOutput(filter.scrub(rawText));
    }

    public static SanitizedOutput empty() {
        return new SanitizedOutput("");
    }

    public boolean isBlank() {
        return value.isBlank();
    }

    @Override
    public String toString() {
        return value;
    }
}
