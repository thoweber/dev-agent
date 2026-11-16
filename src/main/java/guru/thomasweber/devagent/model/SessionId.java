package guru.thomasweber.devagent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Objects;

/**
 * Strongly-typed domain value object representing a distinct conversational or task execution session.
 */
@RegisterForReflection
public record SessionId(String value) {

    public SessionId {
        Objects.requireNonNull(value, "Session identifier must not be null");
        String stripped = value.strip();
        if (stripped.isBlank()) {
            throw new IllegalArgumentException("Session identifier must not be blank");
        }
        value = stripped;
    }

    @JsonCreator
    public static SessionId of(String value) {
        return new SessionId(value);
    }

    public static SessionId from(Object identifier) {
        Objects.requireNonNull(identifier, "Session identifier must not be null");
        if (identifier instanceof SessionId sid) {
            return sid;
        }
        return new SessionId(identifier.toString());
    }

    @JsonValue
    public String idString() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
