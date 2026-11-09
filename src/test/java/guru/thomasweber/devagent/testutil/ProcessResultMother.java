package guru.thomasweber.devagent.testutil;

import guru.thomasweber.devagent.workspace.ProcessResult;

/**
 * Object Mother test factory providing pre-configured {@link ProcessResult} fixtures
 * adhering to the 4+ fields builder pattern (Directive 8 & 19).
 */
public final class ProcessResultMother {

    private ProcessResultMother() {}

    /**
     * Base builder pre-configured with standard success defaults.
     */
    public static ProcessResult.ProcessResultBuilder builder() {
        return ProcessResult.builder()
            .exitCode(0)
            .stdOut("")
            .stdErr("")
            .timedOut(false)
            .truncated(false);
    }

    /**
     * Successful execution fixture with specified stdout.
     */
    public static ProcessResult success(String stdOut) {
        return builder()
            .exitCode(0)
            .stdOut(stdOut)
            .build();
    }

    /**
     * Failed execution fixture with exit code and stderr.
     */
    public static ProcessResult failure(int exitCode, String stdErr) {
        return builder()
            .exitCode(exitCode)
            .stdErr(stdErr)
            .build();
    }

    /**
     * Failed execution fixture with exit code, stdout, and stderr.
     */
    public static ProcessResult failure(int exitCode, String stdOut, String stdErr) {
        return builder()
            .exitCode(exitCode)
            .stdOut(stdOut)
            .stdErr(stdErr)
            .build();
    }

    /**
     * Subprocess timeout fixture.
     */
    public static ProcessResult timedOut() {
        return builder()
            .exitCode(124)
            .stdErr("Process timed out")
            .timedOut(true)
            .build();
    }

    /**
     * Subprocess timeout fixture with specific stderr.
     */
    public static ProcessResult timedOut(String stdErr) {
        return builder()
            .exitCode(124)
            .stdErr(stdErr)
            .timedOut(true)
            .build();
    }

    /**
     * Subprocess output truncated fixture.
     */
    public static ProcessResult truncated(String stdOut) {
        return builder()
            .exitCode(0)
            .stdOut(stdOut)
            .truncated(true)
            .build();
    }
}
