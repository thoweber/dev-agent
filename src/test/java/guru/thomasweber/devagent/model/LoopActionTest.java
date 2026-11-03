package guru.thomasweber.devagent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoopActionTest {

    @Test
    @DisplayName("Given LoopAction enum values - when inspecting - then contains expected actions")
    void givenEnumValues_whenInspecting_thenContainsExpectedActions() {
        assertThat(LoopAction.values()).containsExactly(
            LoopAction.CONTINUE,
            LoopAction.INJECT_WARNING,
            LoopAction.ROLLBACK_AND_PIVOT,
            LoopAction.TRIP_AND_HALT
        );
    }
}
