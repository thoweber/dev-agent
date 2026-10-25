package org.quarkus.agent.cli.ui;

/**
 * Terminal badges representing autonomous agent execution phases.
 */
public enum TerminalBadge {
    PLANNING("PLANNING", "bold,cyan"),
    GATE_APPROVED("GATE: APPROVED", "bold,green"),
    GATE_REJECTED("GATE: REJECTED", "bold,red"),
    TDD_RED("TDD: RED", "bold,red"),
    TDD_GREEN("TDD: GREEN", "bold,green"),
    COMMIT("COMMIT", "bold,yellow"),
    PUSH("PUSH", "bold,blue");

    private final String label;
    private final String ansiStyle;

    TerminalBadge(String label, String ansiStyle) {
        this.label = label;
        this.ansiStyle = ansiStyle;
    }

    public String getLabel() {
        return label;
    }

    public String getAnsiStyle() {
        return ansiStyle;
    }
}
