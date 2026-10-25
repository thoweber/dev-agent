package org.quarkus.agent.cli.ui;

import picocli.CommandLine.Help.Ansi;

/**
 * Terminal UI formatter providing ANSI badges and consistent CLI styling.
 */
public final class TerminalUi {

    private TerminalUi() {
        // Utility class
    }

    /**
     * Formats a message with a styled terminal badge.
     *
     * @param badge   the badge category
     * @param message the informational message
     * @return the ANSI-formatted string
     */
    public static String formatBadge(TerminalBadge badge, String message) {
        String badgeText = String.format("@|%s [%s]|@ %s", badge.getAnsiStyle(), badge.getLabel(), message);
        return Ansi.AUTO.string(badgeText);
    }
}
