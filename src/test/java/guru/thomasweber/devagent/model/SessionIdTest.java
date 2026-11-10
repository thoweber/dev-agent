package guru.thomasweber.devagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SessionId Domain Value Object Tests")
class SessionIdTest {

    @Test
    @DisplayName("Given valid session string - when of - then returns populated SessionId")
    void givenValidSessionString_whenOf_thenReturnsPopulatedSessionId() {
        SessionId sessionId = SessionId.of("task-001");

        assertThat(sessionId.value()).isEqualTo("task-001");
        assertThat(sessionId.idString()).isEqualTo("task-001");
        assertThat(sessionId).hasToString("task-001");
    }

    @Test
    @DisplayName("Given session string with surrounding whitespace - when of - then strips whitespace")
    void givenSessionStringWithSurroundingWhitespace_whenOf_thenStripsWhitespace() {
        SessionId sessionId = SessionId.of("   task-with-padding   ");

        assertThat(sessionId.value()).isEqualTo("task-with-padding");
    }

    @Test
    @DisplayName("Given null string - when of - then throws NullPointerException")
    void givenNullString_whenOf_thenThrowsNullPointerException() {
        assertThatThrownBy(() -> SessionId.of(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Session identifier must not be null");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    @DisplayName("Given blank string - when of - then throws IllegalArgumentException")
    void givenBlankString_whenOf_thenThrowsIllegalArgumentException(String blankValue) {
        assertThatThrownBy(() -> SessionId.of(blankValue))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Session identifier must not be blank");
    }

    @Test
    @DisplayName("Given existing SessionId instance - when from - then returns same instance")
    void givenExistingSessionId_whenFrom_thenReturnsSameInstance() {
        SessionId original = SessionId.of("session-123");
        SessionId result = SessionId.from(original);

        assertThat(result).isSameAs(original);
    }

    @Test
    @DisplayName("Given raw string object - when from - then creates SessionId")
    void givenRawStringObject_whenFrom_thenCreatesSessionId() {
        Object rawObject = "task-raw-string";
        SessionId result = SessionId.from(rawObject);

        assertThat(result.value()).isEqualTo("task-raw-string");
    }

    @Test
    @DisplayName("Given numeric identifier object - when from - then creates SessionId from toString")
    void givenNumericIdentifier_whenFrom_thenCreatesSessionIdFromToString() {
        Object numericId = 42L;
        SessionId result = SessionId.from(numericId);

        assertThat(result.value()).isEqualTo("42");
    }

    @Test
    @DisplayName("Given null object - when from - then throws NullPointerException")
    void givenNullObject_whenFrom_thenThrowsNullPointerException() {
        assertThatThrownBy(() -> SessionId.from(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Session identifier must not be null");
    }

    @Test
    @DisplayName("Given two identical session IDs - when compared - then equals and hashCode match")
    void givenIdenticalSessionIds_whenCompared_thenEqualsAndHashCodeMatch() {
        SessionId first = SessionId.of("task-abc");
        SessionId second = SessionId.of("task-abc");

        assertThat(first)
            .isEqualTo(second)
            .hasSameHashCodeAs(second);
    }
}
