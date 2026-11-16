package guru.thomasweber.devagent.spi.build;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.nonNull;

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
    public SanitizedLog {
        if (nonNull(errorLines)) {
            errorLines = List.copyOf(errorLines);
        } else {
            errorLines = List.of();
        }
        cleanStdout = Objects.toString(cleanStdout, "");
        cleanStderr = Objects.toString(cleanStderr, "");
    }

    public boolean hasErrors() {
        return exitCode != 0 || errorCount > 0 || !errorLines.isEmpty();
    }
}
