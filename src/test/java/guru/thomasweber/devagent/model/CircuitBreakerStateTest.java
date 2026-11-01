package guru.thomasweber.devagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerStateTest {

    @Test
    @DisplayName("Given CircuitBreakerState enum values - when inspecting - then contains expected lifecycle states")
    void givenEnumValues_whenInspecting_thenContainsExpectedStates() {
        assertThat(CircuitBreakerState.values()).containsExactly(
            CircuitBreakerState.NORMAL,
            CircuitBreakerState.WARNING,
            CircuitBreakerState.PIVOT_ACTIVE,
            CircuitBreakerState.TRIPPED
        );
    }
}
