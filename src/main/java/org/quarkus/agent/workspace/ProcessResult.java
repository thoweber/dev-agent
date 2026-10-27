package org.quarkus.agent.workspace;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Builder;

/**
 * Immutable value object holding the output, exit status, and timeout diagnostics of a completed subprocess.
 */
@RegisterForReflection
@Builder(toBuilder = true)
public record ProcessResult(
    int exitCode,
    String stdOut,
    String stdErr,
    boolean timedOut,
    boolean truncated
) {

    public boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }
}
