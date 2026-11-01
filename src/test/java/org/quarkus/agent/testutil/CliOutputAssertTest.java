package org.quarkus.agent.testutil;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.quarkus.agent.testutil.CliOutputAssert.assertThatCli;

class CliOutputAssertTest {

    @Test
    @DisplayName("Given ANSI escaped text - when stripAnsi invoked - returns clean plain text")
    void testStripAnsi() {
        String coloredText = "\u001B[32m[dev-agent]\u001B[0m Task completed with \u001B[1;31mSUCCESS\u001B[0m\u001B[?25h";
        String clean = CliOutputAssert.stripAnsi(coloredText);

        assertThat(clean).isEqualTo("[dev-agent] Task completed with SUCCESS");
        assertThat(CliOutputAssert.stripAnsi(null)).isNull();
        assertThat(CliOutputAssert.stripAnsi("")).isEmpty();
    }

    @Test
    @DisplayName("Given exit code 0 and output - when isSuccess asserted - passes cleanly")
    void testIsSuccessPasses() {
        assertThatCli(0, "\u001B[32mTask OK\u001B[0m")
            .isSuccess()
            .hasExitCode(0)
            .containsAnsiFree("Task OK");
    }

    @Test
    @DisplayName("Given non-zero exit code - when isSuccess asserted - throws AssertionError")
    void testIsSuccessFails() {
        CliOutputAssert assertion = assertThatCli(1, "error");
        assertThatThrownBy(assertion::isSuccess)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Expected command to succeed with exit code 0");
    }

    @Test
    @DisplayName("Given non-zero exit code - when isFailure asserted - passes cleanly")
    void testIsFailurePasses() {
        assertThatCli(2, "Invalid arguments", "Unmatched argument")
            .isFailure()
            .hasExitCode(2)
            .hasStdErrContaining("Unmatched argument");
    }

    @Test
    @DisplayName("Given exit code 0 - when isFailure asserted - throws AssertionError")
    void testIsFailureFails() {
        CliOutputAssert assertion = assertThatCli(0, "ok");
        assertThatThrownBy(assertion::isFailure)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Expected command to fail with non-zero exit code");
    }

    @Test
    @DisplayName("Given no exit code provided - when hasExitCode asserted - throws AssertionError")
    void testHasExitCodeWithoutExitCodeProvided() {
        CliOutputAssert assertion = assertThatCli("just output");
        assertThatThrownBy(() -> assertion.hasExitCode(0))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("No exit code was provided");
    }

    @Test
    @DisplayName("Given ANSI formatted text - when containsAnsiFree asserted - checks against stripped text")
    void testContainsAnsiFree() {
        String output = "\u001B[34m[dev-agent]\u001B[0m Found \u001B[1m2 interrupted tasks\u001B[0m";

        assertThatCli(output)
            .containsAnsiFree("[dev-agent]", "2 interrupted tasks")
            .doesNotContainAnsiFree("Error", "FAILED");

        CliOutputAssert assertion = assertThatCli(output);
        assertThatThrownBy(() -> assertion.containsAnsiFree("TASK-999"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("TASK-999");

        assertThatThrownBy(() -> assertion.doesNotContainAnsiFree("[dev-agent]"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("[dev-agent]");
    }

    @Test
    @DisplayName("Given multi-token text - when containsAnsiFreeInOrder asserted - validates sequence")
    void testContainsAnsiFreeInOrder() {
        String output = "Step 1: Planning\nStep 2: Red Phase\nStep 3: Green Phase";

        assertThatCli(output).containsAnsiFreeInOrder("Step 1", "Step 2", "Step 3");

        CliOutputAssert assertion = assertThatCli(output);
        assertThatThrownBy(() -> assertion.containsAnsiFreeInOrder("Step 3", "Step 1"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("to appear in order");
    }

    @Test
    @DisplayName("Given tabular output - when hasTableEntry asserted - matches taskId and phase on single line")
    void testHasTableEntry() {
        String output = """
              [1] TASK-201 (Phase: RED_TEST, Updated: 2026-10-29T21:00:00Z)
              [2] TASK-202 (Phase: GREEN_PATCH, Updated: 2026-10-29T21:05:00Z)
            """;

        assertThatCli(output)
            .hasTableEntry("TASK-201", "RED_TEST")
            .hasTableEntry("TASK-202", "GREEN_PATCH");

        CliOutputAssert assertion = assertThatCli(output);
        assertThatThrownBy(() -> assertion.hasTableEntry("TASK-201", "GREEN_PATCH"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("TASK-201")
            .hasMessageContaining("GREEN_PATCH");
    }

    @Test
    @DisplayName("Given stderr content - when hasStdErrContaining asserted - verifies stderr")
    void testHasStdErrContaining() {
        assertThatCli(1, "normal out", "Error: Process crashed")
            .hasStdErrContaining("Process crashed");

        CliOutputAssert assertion = assertThatCli(1, "out", "some error");
        assertThatThrownBy(() -> assertion.hasStdErrContaining("Missing flag"))
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Missing flag");
    }

    @Test
    @DisplayName("Given output - getAnsiFree returns stripped string")
    void testGetAnsiFree() {
        String output = "\u001B[32mClean\u001B[0m";
        assertThat(assertThatCli(output).getAnsiFree()).isEqualTo("Clean");
    }
}
