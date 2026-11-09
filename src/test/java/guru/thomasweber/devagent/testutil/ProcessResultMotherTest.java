package guru.thomasweber.devagent.testutil;

import guru.thomasweber.devagent.workspace.ProcessResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessResultMotherTest {

    @Test
    @DisplayName("Given success factory - when invoked - produces clean exit code 0 result")
    void testSuccessFactory() {
        ProcessResult result = ProcessResultMother.success("build success");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdOut()).isEqualTo("build success");
        assertThat(result.stdErr()).isEmpty();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.truncated()).isFalse();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Given failure factory - when invoked - produces non-zero failure result")
    void testFailureFactory() {
        ProcessResult result = ProcessResultMother.failure(1, "out", "err");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdOut()).isEqualTo("out");
        assertThat(result.stdErr()).isEqualTo("err");
        assertThat(result.isSuccess()).isFalse();

        ProcessResult errOnly = ProcessResultMother.failure(2, "fatal");
        assertThat(errOnly.exitCode()).isEqualTo(2);
        assertThat(errOnly.stdErr()).isEqualTo("fatal");
    }

    @Test
    @DisplayName("Given timedOut factory - when invoked - produces timedOut flagged result")
    void testTimedOutFactory() {
        ProcessResult result = ProcessResultMother.timedOut();
        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isEqualTo(124);
        assertThat(result.isSuccess()).isFalse();

        ProcessResult custom = ProcessResultMother.timedOut("aborted");
        assertThat(custom.timedOut()).isTrue();
        assertThat(custom.stdErr()).isEqualTo("aborted");
    }

    @Test
    @DisplayName("Given truncated factory - when invoked - produces truncated flagged result")
    void testTruncatedFactory() {
        ProcessResult result = ProcessResultMother.truncated("partial output");
        assertThat(result.truncated()).isTrue();
        assertThat(result.stdOut()).isEqualTo("partial output");
        assertThat(result.exitCode()).isZero();
    }
}
