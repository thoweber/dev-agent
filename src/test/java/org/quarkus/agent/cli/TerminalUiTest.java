package org.quarkus.agent.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quarkus.agent.cli.ui.TerminalBadge;
import org.quarkus.agent.cli.ui.TerminalUi;

import static org.assertj.core.api.Assertions.assertThat;

class TerminalUiTest {

    @Test
    @DisplayName("Given TerminalBadge enum - when rendered - then returns formatted label")
    void testBadgeRendering() {
        assertThat(TerminalBadge.PLANNING.getLabel()).isEqualTo("PLANNING");
        assertThat(TerminalBadge.PLANNING.getAnsiStyle()).isEqualTo("bold,cyan");
        assertThat(TerminalBadge.GATE_APPROVED.getLabel()).isEqualTo("GATE: APPROVED");
        assertThat(TerminalBadge.GATE_APPROVED.getAnsiStyle()).isEqualTo("bold,green");
        assertThat(TerminalBadge.GATE_REJECTED.getLabel()).isEqualTo("GATE: REJECTED");
        assertThat(TerminalBadge.GATE_REJECTED.getAnsiStyle()).isEqualTo("bold,red");
        assertThat(TerminalBadge.TDD_RED.getLabel()).isEqualTo("TDD: RED");
        assertThat(TerminalBadge.TDD_RED.getAnsiStyle()).isEqualTo("bold,red");
        assertThat(TerminalBadge.TDD_GREEN.getLabel()).isEqualTo("TDD: GREEN");
        assertThat(TerminalBadge.TDD_GREEN.getAnsiStyle()).isEqualTo("bold,green");
        assertThat(TerminalBadge.COMMIT.getLabel()).isEqualTo("COMMIT");
        assertThat(TerminalBadge.COMMIT.getAnsiStyle()).isEqualTo("bold,yellow");
        assertThat(TerminalBadge.PUSH.getLabel()).isEqualTo("PUSH");
        assertThat(TerminalBadge.PUSH.getAnsiStyle()).isEqualTo("bold,blue");
    }

    @Test
    @DisplayName("Given TerminalUi - when formatBadge called - then contains badge label and message")
    void testFormatBadgeMessage() {
        String formatted = TerminalUi.formatBadge(TerminalBadge.PLANNING, "Drafting implementation plan");
        assertThat(formatted).contains("PLANNING").contains("Drafting implementation plan");
    }
}
