package org.quarkus.agent.spi.build;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;
import java.util.List;

/**
 * Encapsulates filtered build/test output stripped of extraneous downloader
 * and task execution progress noise.
 */
@RegisterForReflection
@Builder(toBuilder = true)
public record SanitizedLog(
    int exitCode,
    int errorCount,
    List<String> errorLines,
    String cleanStdout,
    String cleanStderr
) {
    public boolean hasErrors() {
        return exitCode != 0 || errorCount > 0 || !errorLines.isEmpty();
    }
}
