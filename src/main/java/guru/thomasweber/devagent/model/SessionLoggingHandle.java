package guru.thomasweber.devagent.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;

import java.nio.file.Path;

/**
 * Immutable handle describing active per-session logging configuration and sinks.
 */
@RegisterForReflection
@Builder(toBuilder = true)
public record SessionLoggingHandle(
    SessionId sessionId,
    LogVerbosity verbosity,
    Path sessionLogFile,
    boolean consoleEnabled
) {}
