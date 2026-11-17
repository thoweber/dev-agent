package guru.thomasweber.devagent.model;

import guru.thomasweber.devagent.config.DevAgentConfig.LoggingConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.jboss.logmanager.LogContext;

import java.util.Locale;
import java.util.logging.Level;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Strongly-typed opt-in logging verbosity tiers separating {@code dev-agent} categories from third-party libraries.
 */
@RegisterForReflection
public enum LogVerbosity {

    OFF(Level.OFF, Level.OFF, 0),
    WARN(jbossLevel("WARN"), jbossLevel("WARN"), 1),
    INFO(jbossLevel("INFO"), jbossLevel("INFO"), 2),
    DEBUG(jbossLevel("DEBUG"), jbossLevel("INFO"), 3),
    ALL(Level.ALL, jbossLevel("DEBUG"), 4);

    private final Level devAgentLevel;
    private final Level rootLevel;
    private final int verbosityCount;

    LogVerbosity(Level devAgentLevel, Level rootLevel, int verbosityCount) {
        this.devAgentLevel = devAgentLevel;
        this.rootLevel = rootLevel;
        this.verbosityCount = verbosityCount;
    }

    private static Level jbossLevel(String name) {
        return LogContext.getSystemLogContext().getLevelForName(name);
    }

    public Level devAgentLevel() {
        return devAgentLevel;
    }

    public Level rootLevel() {
        return rootLevel;
    }

    public int verbosityCount() {
        return verbosityCount;
    }

    public boolean isEnabled() {
        return this != OFF;
    }

    public static LogVerbosity fromVerbosityCount(int count) {
        if (count <= 0) {
            return OFF;
        }
        return switch (count) {
            case 1 -> WARN;
            case 2 -> INFO;
            case 3 -> DEBUG;
            default -> ALL;
        };
    }

    public static LogVerbosity parse(String rawLevel) {
        if (isNull(rawLevel) || rawLevel.isBlank()) {
            throw new IllegalArgumentException("Log verbosity level must not be null or blank");
        }
        String normalized = rawLevel.strip().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "OFF" -> OFF;
            case "ERROR", "WARN" -> WARN;
            case "INFO" -> INFO;
            case "DEBUG" -> DEBUG;
            case "TRACE", "ALL" -> ALL;
            default -> throw new IllegalArgumentException("Unsupported log verbosity level: " + rawLevel);
        };
    }

    public static LogVerbosity resolveEffective(int cliVerbosityCount, LoggingConfig loggingConfig) {
        if (cliVerbosityCount > 0) {
            return fromVerbosityCount(cliVerbosityCount);
        }
        if (nonNull(loggingConfig) && loggingConfig.enabled()) {
            return parse(loggingConfig.level());
        }
        return OFF;
    }
}
