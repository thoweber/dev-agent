package org.quarkus.agent.spi.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SanitizedLogTest {

    @Test
    @DisplayName("Given successful execution - when instantiated via builder - then hasErrors returns false")
    void testSuccessfulLogHasNoErrors() {
        SanitizedLog log = SanitizedLog.builder()
            .exitCode(0)
            .errorCount(0)
            .errorLines(List.of())
            .cleanStdout("Build succeeded")
            .cleanStderr("")
            .build();

        assertThat(log.hasErrors()).isFalse();
        assertThat(log.cleanStdout()).isEqualTo("Build succeeded");
    }

    @Test
    @DisplayName("Given failing build - when errorLines present - then hasErrors returns true")
    void testFailedLogReportsErrors() {
        SanitizedLog log = SanitizedLog.builder()
            .exitCode(1)
            .errorCount(1)
            .errorLines(List.of("[ERROR] Compilation failure at line 42"))
            .cleanStdout("")
            .cleanStderr("[ERROR] Compilation failure at line 42")
            .build();

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Given exitCode 0 but errorCount > 0 - then hasErrors returns true")
    void testZeroExitCodeWithErrorCountReportsErrors() {
        SanitizedLog log = SanitizedLog.builder()
            .exitCode(0)
            .errorCount(2)
            .errorLines(List.of())
            .cleanStdout("")
            .cleanStderr("")
            .build();

        assertThat(log.hasErrors()).isTrue();
    }

    @Test
    @DisplayName("Given exitCode 0 and errorCount 0 but non-empty errorLines - then hasErrors returns true")
    void testZeroExitCodeWithErrorLinesReportsErrors() {
        SanitizedLog log = SanitizedLog.builder()
            .exitCode(0)
            .errorCount(0)
            .errorLines(List.of("Warning escalated to error"))
            .cleanStdout("")
            .cleanStderr("")
            .build();

        assertThat(log.hasErrors()).isTrue();
    }
}
