package org.quarkus.agent.testutil;

import org.assertj.core.api.AbstractAssert;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fluent AssertJ custom assertion for verifying CLI outputs, exit codes, and tabular data
 * without sensitivity to ANSI escape sequences or terminal color codes.
 */
public class CliOutputAssert extends AbstractAssert<CliOutputAssert, String> {

    private static final Pattern ANSI_PATTERN = Pattern.compile("\\u001B\\[[0-9;?]*[a-zA-Z]");

    private final Integer exitCode;
    private final String stdErr;

    protected CliOutputAssert(String actual, Integer exitCode, String stdErr) {
        super(actual, CliOutputAssert.class);
        this.exitCode = exitCode;
        this.stdErr = stdErr != null ? stdErr : "";
    }

    public static CliOutputAssert assertThatCli(String actual) {
        return new CliOutputAssert(actual != null ? actual : "", null, "");
    }

    public static CliOutputAssert assertThatCli(int exitCode, String actual) {
        return new CliOutputAssert(actual != null ? actual : "", exitCode, "");
    }

    public static CliOutputAssert assertThatCli(int exitCode, String actual, String stdErr) {
        return new CliOutputAssert(actual != null ? actual : "", exitCode, stdErr);
    }

    public static String stripAnsi(String input) {
        if (input == null) {
            return null;
        }
        return ANSI_PATTERN.matcher(input).replaceAll("");
    }

    public CliOutputAssert hasExitCode(int expected) {
        if (exitCode == null) {
            failWithMessage("No exit code was provided to assertThatCli(). Expected <%s>.", expected);
        }
        if (exitCode != expected) {
            failWithMessage("Expected exit code to be <%s> but was <%s>. Output:\n%s\nStderr:\n%s",
                expected, exitCode, actual, stdErr);
        }
        return this;
    }

    public CliOutputAssert isSuccess() {
        if (exitCode == null) {
            failWithMessage("Expected command to succeed with exit code 0, but no exit code was provided to assertThatCli().");
        }
        if (exitCode != 0) {
            failWithMessage("Expected command to succeed with exit code 0, but was <%s>. Output:\n%s\nStderr:\n%s",
                exitCode, actual, stdErr);
        }
        return this;
    }

    public CliOutputAssert isFailure() {
        if (exitCode == null) {
            failWithMessage("Expected command to fail with non-zero exit code, but no exit code was provided to assertThatCli().");
        }
        if (exitCode == 0) {
            failWithMessage("Expected command to fail with non-zero exit code, but exit code was 0. Output:\n%s", actual);
        }
        return this;
    }

    public CliOutputAssert containsAnsiFree(CharSequence... values) {
        isNotNull();
        String clean = stripAnsi(actual);
        for (CharSequence val : values) {
            if (!clean.contains(val)) {
                failWithMessage("Expected output (ANSI-free) to contain <%s>, but was:\n%s", val, clean);
            }
        }
        return this;
    }

    public CliOutputAssert doesNotContainAnsiFree(CharSequence... values) {
        isNotNull();
        String clean = stripAnsi(actual);
        for (CharSequence val : values) {
            if (clean.contains(val)) {
                failWithMessage("Expected output (ANSI-free) not to contain <%s>, but was:\n%s", val, clean);
            }
        }
        return this;
    }

    public CliOutputAssert containsAnsiFreeInOrder(String... values) {
        isNotNull();
        String clean = stripAnsi(actual);
        int lastIndex = 0;
        for (String val : values) {
            int idx = clean.indexOf(val, lastIndex);
            if (idx == -1) {
                failWithMessage("Expected <%s> to appear in order after position %d in ANSI-free output:\n%s",
                    val, lastIndex, clean);
            }
            lastIndex = idx + val.length();
        }
        return this;
    }

    public CliOutputAssert hasTableEntry(String taskId, String phase) {
        isNotNull();
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        String clean = stripAnsi(actual);
        boolean found = clean.lines().anyMatch(line -> line.contains(taskId) && line.contains(phase));
        if (!found) {
            failWithMessage("Expected output to contain a table/list entry for task '%s' and phase '%s', but was:\n%s",
                taskId, phase, clean);
        }
        return this;
    }

    public CliOutputAssert hasStdErrContaining(CharSequence... values) {
        String cleanErr = stripAnsi(stdErr);
        for (CharSequence val : values) {
            if (!cleanErr.contains(val)) {
                failWithMessage("Expected stderr (ANSI-free) to contain <%s>, but was:\n%s", val, cleanErr);
            }
        }
        return this;
    }

    public String getAnsiFree() {
        return stripAnsi(actual);
    }
}
