package guru.thomasweber.devagent.model;

import guru.thomasweber.devagent.config.DevAgentConfig.LoggingConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;

import java.nio.file.Path;

/**
 * Immutable input parameters used to initialize opt-in per-session logging.
 */
@RegisterForReflection
@Builder(toBuilder = true)
public record SessionLoggingRequest(
    Path workspaceRoot,
    int cliVerbosityCount,
    LoggingConfig loggingConfig,
    SessionId sessionId,
    String commandName
) {}
