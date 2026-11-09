package guru.thomasweber.devagent.spi.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MavenOutputParserTest {

    private final MavenOutputParser parser = new MavenOutputParser();

    @Test
    @DisplayName("Given javac compiler errors with continuation lines - when parsed - then coalesces symbol and location")
    void givenJavacCompilerErrors_whenParsed_thenCoalescesSymbolAndLocation() {
        String stdout = String.join("\n",
            "Downloading from central: https://repo.maven.apache.org/maven2/org/example/lib/1.0/lib-1.0.pom",
            "Progress (1): 15 kB",
            "Downloaded from central: https://repo.maven.apache.org/maven2/org/example/lib/1.0/lib-1.0.pom (15 kB at 42 kB/s)",
            "[INFO] Compiling 8 source files with javac [debug target 21] to target-agent/classes",
            "[ERROR] /home/dev/order-service/src/main/java/com/example/OrderService.java:[42,18] cannot find symbol",
            "  symbol:   method calculateDiscount()",
            "  location: variable customer of type Customer",
            "[ERROR] /home/dev/order-service/src/main/java/com/example/OrderService.java:[55,30] incompatible types: String cannot be converted to BigDecimal",
            "[ERROR] /home/dev/order-service/src/main/java/com/example/OrderService.java:[60,5] ';' expected",
            "[INFO] 3 errors",
            "[INFO] BUILD FAILURE"
        );

        SanitizedLog log = parser.parse(1, stdout, "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.exitCode()).isEqualTo(1);
        assertThat(log.errorCount()).isEqualTo(3);
        assertThat(log.errorLines()).hasSize(3);

        String firstError = log.errorLines().get(0);
        assertThat(firstError)
            .contains("cannot find symbol")
            .contains("method calculateDiscount()")
            .contains("variable customer of type Customer");

        assertThat(log.errorLines().get(1)).contains("incompatible types: String cannot be converted to BigDecimal");
        assertThat(log.errorLines().get(2)).contains("';' expected");

        assertThat(log.cleanStdout())
            .doesNotContain("Downloading from central", "Progress (1)", "Downloaded from central")
            .contains("cannot find symbol", "BUILD FAILURE");
    }

    @Test
    @DisplayName("Given Surefire assertion failure - when parsed - then extracts failure method, expected vs actual, and test line")
    void givenSurefireAssertionFailure_whenParsed_thenExtractsFailureMethodExpectedVsActualAndTestLine() {
        String stdout = String.join("\n",
            "[INFO] Running com.example.OrderProcessorTest",
            "[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.123 s <<< FAILURE! -- in com.example.OrderProcessorTest",
            "[ERROR] com.example.OrderProcessorTest.testProcessPayment() -- Time elapsed: 0.045 s <<< FAILURE!",
            "AssertionFailedError: expected: <COMPLETED> but was: <FAILED>",
            "\tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:55)",
            "\tat com.example.OrderProcessorTest.testProcessPayment(OrderProcessorTest.java:38)",
            "[INFO] Results:",
            "[ERROR] Failures: ",
            "[ERROR]   OrderProcessorTest.testProcessPayment:38 expected: <COMPLETED> but was: <FAILED>",
            "[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0",
            "[INFO] BUILD FAILURE"
        );

        SanitizedLog log = parser.parse(1, stdout, "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorCount()).isGreaterThanOrEqualTo(1);

        List<String> errors = log.errorLines();
        assertThat(errors)
            .anyMatch(err -> err.contains("testProcessPayment") && err.contains("expected: <COMPLETED> but was: <FAILED>"));

        assertThat(log.cleanStdout()).contains("AssertionFailedError: expected: <COMPLETED> but was: <FAILED>");
    }

    @Test
    @DisplayName("Given Surefire unhandled runtime exception - when parsed - then captures full stack trace and cause")
    void givenSurefireUnhandledRuntimeException_whenParsed_thenCapturesFullStackTraceAndCause() {
        String stdout = String.join("\n",
            "[INFO] Running com.example.OrderServiceTest",
            "[ERROR] Tests run: 2, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.089 s <<< FAILURE! -- in com.example.OrderServiceTest",
            "[ERROR] com.example.OrderServiceTest.testCalculateTax() -- Time elapsed: 0.012 s <<< FAILURE!",
            "NullPointerException: Cannot invoke \"TaxConfig.getRate()\" because \"this.taxConfig\" is null",
            "\tat com.example.OrderService.calculateTax(OrderService.java:45)",
            "\tat com.example.OrderServiceTest.testCalculateTax(OrderServiceTest.java:28)",
            "Caused by: IllegalStateException: Missing configuration",
            "\tat com.example.ConfigLoader.load(ConfigLoader.java:12)",
            "[INFO] BUILD FAILURE"
        );

        SanitizedLog log = parser.parse(1, stdout, "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorLines()).isNotEmpty();

        String combinedErrors = String.join("\n", log.errorLines());
        assertThat(combinedErrors)
            .contains("testCalculateTax")
            .contains("NullPointerException: Cannot invoke")
            .contains("this.taxConfig")
            .contains("OrderService.calculateTax(OrderService.java:45)")
            .contains("Caused by: IllegalStateException: Missing configuration");
    }

    @Test
    @DisplayName("Given Quarkus CDI bootstrap failure - when parsed - then captures UnsatisfiedResolutionException and bean details")
    void givenQuarkusCdiBootstrapFailure_whenParsed_thenCapturesUnsatisfiedResolutionExceptionAndBeanDetails() {
        String stdout = String.join("\n",
            "[INFO] Running com.example.OrderResourceTest",
            "2026-11-09 20:00:01,123 ERROR [io.qua.run.Application] (main) Failed to start application: UnsatisfiedResolutionException: Unsatisfied dependency for type PaymentGateway",
            "\t- java member: com.example.OrderResource#paymentGateway",
            "\t- declared on CLASS bean [types=[OrderResource], qualifiers=[@Default]]",
            "\tat io.quarkus.arc.impl.InstanceImpl.get(InstanceImpl.java:250)",
            "[ERROR] Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 1.234 s <<< FAILURE! -- in com.example.OrderResourceTest",
            "[ERROR] com.example.OrderResourceTest -- Time elapsed: 1.234 s <<< FAILURE!",
            "RuntimeException: Build failure: class loading error",
            "\tat io.quarkus.test.junit.QuarkusTestExtension.throwBootFailureException(QuarkusTestExtension.java:654)",
            "[INFO] BUILD FAILURE"
        );

        SanitizedLog log = parser.parse(1, stdout, "");

        assertThat(log.hasErrors()).isTrue();
        String combinedErrors = String.join("\n", log.errorLines());

        assertThat(combinedErrors)
            .contains("UnsatisfiedResolutionException")
            .contains("paymentGateway")
            .contains("OrderResourceTest");
    }

    @Test
    @DisplayName("Given multi-module reactor failure - when parsed - then captures module failure and compilation cause")
    void givenMultiModuleReactorFailure_whenParsed_thenCapturesModuleFailureAndCompilationCause() {
        String stdout = String.join("\n",
            "[INFO] Reactor Summary for OmniDepot 1.0.0-SNAPSHOT:",
            "[INFO] core-domain ........................................ SUCCESS [  1.234 s]",
            "[INFO] payment-service .................................... FAILURE [  2.456 s]",
            "[INFO] order-service ...................................... SKIPPED",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] BUILD FAILURE",
            "[INFO] ------------------------------------------------------------------------",
            "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.13.0:compile (default-compile) on project payment-service: Compilation failure",
            "[ERROR] /home/dev/payment-service/src/main/java/com/example/StripeClient.java:[19,35] package com.stripe does not exist"
        );

        SanitizedLog log = parser.parse(1, stdout, "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorLines())
            .anyMatch(err -> err.contains("Failed to execute goal") && err.contains("payment-service"))
            .anyMatch(err -> err.contains("package com.stripe does not exist"));
    }

    @Test
    @DisplayName("Given dependency or plugin resolution failure - when parsed - then captures artifact descriptor error")
    void givenDependencyOrPluginResolutionFailure_whenParsed_thenCapturesArtifactDescriptorError() {
        String stdout = String.join("\n",
            "[INFO] Scanning for projects...",
            "[ERROR] Plugin org.apache.maven.plugins:maven-surefire-plugin:3.5.6 or one of its dependencies could not be resolved: Failed to read artifact descriptor: Network is unreachable",
            "[INFO] BUILD FAILURE"
        );

        SanitizedLog log = parser.parse(1, stdout, "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorLines().get(0))
            .contains("maven-surefire-plugin")
            .contains("Network is unreachable");
    }

    @Test
    @DisplayName("Given fatal error on stderr with clean stdout - when parsed - then captures stderr in errorLines")
    void givenFatalErrorOnStderrWithCleanStdout_whenParsed_thenCapturesStderrInErrorLines() {
        String stdout = "[INFO] Scanning for projects...\n[INFO] Compiling 1 source file\n";
        String stderr = "Fatal error compiling: error: release version 25 not supported\n";

        SanitizedLog log = parser.parse(1, stdout, stderr);

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.exitCode()).isEqualTo(1);
        assertThat(log.errorLines()).contains("Fatal error compiling: error: release version 25 not supported");
        assertThat(log.cleanStderr()).contains("Fatal error compiling");
    }

    @Test
    @DisplayName("Given fatal JVM crash or OOM with non-zero exit code - when parsed - then guarantees critical diagnostics are preserved")
    void givenFatalJvmCrashOrOomWithNonZeroExitCode_whenParsed_thenGuaranteesCriticalDiagnosticsArePreserved() {
        String stdout = String.join("\n",
            "#",
            "# OutOfMemoryError: Java heap space",
            "# -XX:OnOutOfMemoryError=\"kill -9 %p\"",
            "# Executing /bin/sh -c \"kill -9 12345\"..."
        );

        SanitizedLog log = parser.parse(137, stdout, "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.exitCode()).isEqualTo(137);
        assertThat(log.errorLines()).isNotEmpty();
        assertThat(String.join("\n", log.errorLines())).contains("OutOfMemoryError: Java heap space");
    }

    @Test
    @DisplayName("Given successful clean build with download noise - when parsed - then strips downloader headers and reports zero errors")
    void givenSuccessfulCleanBuildWithDownloadNoise_whenParsed_thenStripsDownloaderHeadersAndReportsZeroErrors() {
        String stdout = String.join("\n",
            "Downloading from central: https://repo.maven.apache.org/maven2/org/example/1.0/example-1.0.jar",
            "Progress (1): 10 kB",
            "Downloaded from central: https://repo.maven.apache.org/maven2/org/example/1.0/example-1.0.jar (10 kB)",
            "[INFO] Compiling 5 source files to target-agent/classes",
            "[INFO] BUILD SUCCESS"
        );

        SanitizedLog log = parser.parse(0, stdout, "");

        assertThat(log.hasErrors()).isFalse();
        assertThat(log.exitCode()).isEqualTo(0);
        assertThat(log.errorCount()).isEqualTo(0);
        assertThat(log.errorLines()).isEmpty();
        assertThat(log.cleanStdout())
            .doesNotContain("Downloading from central", "Progress (1)", "Downloaded from central")
            .contains("Compiling 5 source files", "BUILD SUCCESS");
    }

    @Test
    @DisplayName("Given boilerplate Maven noise lines - when parsed - then ignores help instructions from errorLines")
    void givenBoilerplateMavenNoiseLines_whenParsed_thenIgnoresHelpInstructionsFromErrorLines() {
        String stdout = String.join("\n",
            "[ERROR] -> [Help 1]",
            "[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.",
            "[ERROR] Re-run Maven using the -X switch to enable full debug logging.",
            "[ERROR] For more information about the errors, please read documentation."
        );

        SanitizedLog log = parser.parse(0, stdout, "");

        assertThat(log.errorLines()).isEmpty();
        assertThat(log.hasErrors()).isFalse();
    }

    @Test
    @DisplayName("Given Surefire error marker and suppressed exceptions - when parsed - then captures error and suppressed frames")
    void givenSurefireErrorMarkerAndSuppressedExceptions_whenParsed_thenCapturesErrorAndSuppressedFrames() {
        String stdout = String.join("\n",
            "[ERROR] com.example.DbTest.testConnect() <<< ERROR!",
            "java.sql.SQLException: Connection refused",
            "\tat com.example.Db.connect(Db.java:15)",
            "Suppressed: java.lang.IllegalStateException: Pool closed",
            "\tat com.example.Pool.close(Pool.java:99)",
            "... 5 more"
        );

        SanitizedLog log = parser.parse(1, stdout, "");

        assertThat(log.hasErrors()).isTrue();
        String combined = String.join("\n", log.errorLines());
        assertThat(combined)
            .contains("testConnect()")
            .contains("Connection refused")
            .contains("Suppressed: java.lang.IllegalStateException: Pool closed")
            .contains("... 5 more");
    }

    @Test
    @DisplayName("Given debug line during error block - when parsed - then terminates error block")
    void givenDebugLineDuringErrorBlock_whenParsed_thenTerminatesErrorBlock() {
        String stdout = String.join("\n",
            "[ERROR] First compilation error",
            "  symbol: method foo()",
            "[DEBUG] Debugging compiler options",
            "  regular indented line not part of error",
            "[ERROR] Second compilation error"
        );

        SanitizedLog log = parser.parse(1, stdout, "");

        assertThat(log.errorCount()).isEqualTo(2);
        assertThat(log.errorLines().get(0)).contains("First compilation error", "method foo()");
        assertThat(log.errorLines().get(1)).isEqualTo("[ERROR] Second compilation error");
    }

    @Test
    @DisplayName("Given direct javac error and fatal log line - when parsed - then captures diagnostics")
    void givenDirectJavacErrorAndFatalLogLine_whenParsed_thenCapturesDiagnostics() {
        String stdout = String.join("\n",
            "javac: invalid flag: -XunknownFlag",
            "FATAL [io.quarkus.runner] Critical initialization failure"
        );

        SanitizedLog log = parser.parse(1, stdout, "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorLines()).hasSize(2);
        assertThat(log.errorLines().get(0)).isEqualTo("javac: invalid flag: -XunknownFlag");
        assertThat(log.errorLines().get(1)).isEqualTo("FATAL [io.quarkus.runner] Critical initialization failure");
    }

    @Test
    @DisplayName("Given non-zero exit code with no matching error keywords - when parsed - then captures last stdout line")
    void givenNonZeroExitCodeWithNoMatchingKeywords_whenParsed_thenCapturesLastStdoutLine() {
        String stdout = "Process terminated abnormally with status 137 without keywords\nTerminated\n";

        SanitizedLog log = parser.parse(137, stdout, "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorLines()).contains("Terminated");
    }

    @Test
    @DisplayName("Given non-zero exit code with blank lines on stderr - when parsed - then filters blanks and captures errors")
    void givenNonZeroExitCodeWithBlankLinesOnStderr_whenParsed_thenFiltersBlanksAndCapturesErrors() {
        String stderr = "\n   \nActual fatal error message\n\n";

        SanitizedLog log = parser.parse(1, "", stderr);

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorLines()).containsExactly("Actual fatal error message");
    }

    @Test
    @DisplayName("Given non-zero exit code with completely empty streams - when parsed - then preserves exit code without exception")
    void givenNonZeroExitCodeWithCompletelyEmptyStreams_whenParsed_thenPreservesExitCodeWithoutException() {
        SanitizedLog log = parser.parse(1, "", "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.exitCode()).isEqualTo(1);
        assertThat(log.errorLines()).isEmpty();
    }

    @Test
    @DisplayName("Given null or empty streams - when parsed - then returns safe empty SanitizedLog")
    void givenNullOrEmptyStreams_whenParsed_thenReturnsSafeEmptySanitizedLog() {
        SanitizedLog logNull = parser.parse(null, null);
        assertThat(logNull.hasErrors()).isFalse();
        assertThat(logNull.cleanStdout()).isEmpty();
        assertThat(logNull.cleanStderr()).isEmpty();
        assertThat(logNull.errorLines()).isEmpty();

        SanitizedLog logBlank = parser.parse("   ", "   ");
        assertThat(logBlank.hasErrors()).isFalse();
        assertThat(logBlank.cleanStdout()).isEmpty();
        assertThat(logBlank.cleanStderr()).isEmpty();
    }
}
