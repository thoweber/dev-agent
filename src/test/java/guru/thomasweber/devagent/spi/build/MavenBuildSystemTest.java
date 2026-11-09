package guru.thomasweber.devagent.spi.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MavenBuildSystemTest {

    private final MavenBuildSystem buildSystem = new MavenBuildSystem();

    @Test
    @DisplayName("Given default configuration - when priority checked - then returns 100")
    void givenDefaultConfiguration_whenPriorityChecked_thenReturns100() {
        assertThat(buildSystem.priority()).isEqualTo(100);
    }

    @Test
    @DisplayName("Given directory with pom.xml - when isApplicable checked - then returns true")
    void givenDirectoryWithPomXml_whenIsApplicableChecked_thenReturnsTrue(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        assertThat(buildSystem.isApplicable(tempDir)).isTrue();
    }

    @Test
    @DisplayName("Given directory without pom.xml - when isApplicable checked - then returns false")
    void givenDirectoryWithoutPomXml_whenIsApplicableChecked_thenReturnsFalse(@TempDir Path tempDir) {
        assertThat(buildSystem.isApplicable(tempDir)).isFalse();
    }

    @Test
    @DisplayName("Given null project root - when isApplicable checked - then returns false")
    void givenNullProjectRoot_whenIsApplicableChecked_thenReturnsFalse() {
        assertThat(buildSystem.isApplicable(null)).isFalse();
    }

    @Test
    @DisplayName("Given pom.xml is a directory - when isApplicable checked - then returns false")
    void givenPomXmlIsDirectory_whenIsApplicableChecked_thenReturnsFalse(@TempDir Path tempDir) throws IOException {
        Files.createDirectory(tempDir.resolve("pom.xml"));
        assertThat(buildSystem.isApplicable(tempDir)).isFalse();
    }

    @Test
    @DisplayName("Given directory with mvnw wrapper - when resolveExecutable called - then returns ./mvnw")
    void givenDirectoryWithMvnw_whenResolveExecutableCalled_thenReturnsWrapper(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("mvnw"), "#!/bin/sh\n");
        String executable = buildSystem.resolveExecutable(tempDir);
        assertThat(executable).isIn("./mvnw", "mvnw.cmd", "mvnw");
    }

    @Test
    @DisplayName("Given mvnw is a directory - when resolveExecutable called - then returns mvn")
    void givenMvnwIsDirectory_whenResolveExecutableCalled_thenReturnsMvn(@TempDir Path tempDir) throws IOException {
        Files.createDirectory(tempDir.resolve("mvnw"));
        assertThat(buildSystem.resolveExecutable(tempDir)).isEqualTo("mvn");
    }

    @Test
    @DisplayName("Given default BackendBuildSystem interface method - when sanitizeOutput with exitCode called - then delegates")
    void givenDefaultBackendBuildSystemInterfaceMethod_whenCalled_thenDelegates() {
        BackendBuildSystem stub = new BackendBuildSystem() {
            @Override
            public int priority() {
                return 1;
            }

            @Override
            public boolean isApplicable(Path root) {
                return false;
            }

            @Override
            public String resolveExecutable(Path root) {
                return "mvn";
            }

            @Override
            public List<String> buildCompileCommand(Path root, String module, String target) {
                return List.of();
            }

            @Override
            public List<String> buildTestCommand(Path root, String module, String pattern, String target) {
                return List.of();
            }

            @Override
            public SanitizedLog sanitizeOutput(String out, String err) {
                return SanitizedLog.builder().cleanStdout(out).cleanStderr(err).build();
            }
        };

        SanitizedLog result = stub.sanitizeOutput(0, "stdout", "stderr");
        assertThat(result.cleanStdout()).isEqualTo("stdout");
    }

    @Test
    @DisplayName("Given directory without wrapper - when resolveExecutable called - then returns mvn")
    void givenDirectoryWithoutWrapper_whenResolveExecutableCalled_thenReturnsMvn(@TempDir Path tempDir) {
        assertThat(buildSystem.resolveExecutable(tempDir)).isEqualTo("mvn");
    }

    @Test
    @DisplayName("Given null project root - when resolveExecutable called - then returns mvn")
    void givenNullProjectRoot_whenResolveExecutableCalled_thenReturnsMvn() {
        assertThat(buildSystem.resolveExecutable(null)).isEqualTo("mvn");
    }

    @Test
    @DisplayName("Given single-module project - when buildCompileCommand called - then generates batch test-compile with target-agent and no -pl")
    void givenSingleModuleProject_whenBuildCompileCommandCalled_thenGeneratesBatchTestCompileWithTargetAgentAndNoPl(@TempDir Path tempDir) {
        List<String> command = buildSystem.buildCompileCommand(tempDir, null, null);

        assertThat(command).contains("mvn", "-B", "-Dproject.build.directory=target-agent", "test-compile");
        assertThat(command).doesNotContain("-pl", "-am");
    }

    @Test
    @DisplayName("Given multi-module project - when buildCompileCommand called - then generates -pl and -am flags")
    void givenMultiModuleProject_whenBuildCompileCommandCalled_thenGeneratesPlAndAmFlags(@TempDir Path tempDir) {
        List<String> command = buildSystem.buildCompileCommand(tempDir, "core-module", "custom-target");

        assertThat(command).contains("mvn", "-B", "-Dproject.build.directory=custom-target", "-pl", "core-module", "-am", "test-compile");
    }

    @Test
    @DisplayName("Given custom MavenOutputParser - when instantiated - then uses supplied parser")
    void givenCustomOutputParser_whenInstantiated_thenUsesSuppliedParser() {
        MavenOutputParser customParser = new MavenOutputParser();
        MavenBuildSystem custom = new MavenBuildSystem(customParser);
        assertThat(custom.sanitizeOutput("clean", "").cleanStdout()).isEqualTo("clean");
    }

    @Test
    @DisplayName("Given null parser - when instantiated - then falls back to default parser")
    void givenNullParser_whenInstantiated_thenFallsBackToDefaultParser() {
        MavenBuildSystem custom = new MavenBuildSystem(null);
        assertThat(custom.sanitizeOutput("clean", "").cleanStdout()).isEqualTo("clean");
    }

    @Test
    @DisplayName("Given dot or blank modulePath - when buildCompileCommand called - then omits -pl and -am flags")
    void givenDotOrBlankModulePath_whenBuildCompileCommandCalled_thenOmitsPlAndAmFlags(@TempDir Path tempDir) {
        List<String> cmd1 = buildSystem.buildCompileCommand(tempDir, ".", "target-agent");
        List<String> cmd2 = buildSystem.buildCompileCommand(tempDir, "   ", "target-agent");
        List<String> cmd3 = buildSystem.buildCompileCommand(tempDir, "./", "target-agent");
        List<String> cmd4 = buildSystem.buildCompileCommand(tempDir, ".\\", "target-agent");

        assertThat(cmd1).doesNotContain("-pl", "-am");
        assertThat(cmd2).doesNotContain("-pl", "-am");
        assertThat(cmd3).doesNotContain("-pl", "-am");
        assertThat(cmd4).doesNotContain("-pl", "-am");
    }

    @Test
    @DisplayName("Given single-module with testPattern - when buildTestCommand called - then injects test goal, pattern, and Surefire memory args")
    void givenSingleModuleWithTestPattern_whenBuildTestCommandCalled_thenInjectsTestGoalPatternAndSurefireMemoryArgs(@TempDir Path tempDir) {
        List<String> command = buildSystem.buildTestCommand(tempDir, null, "MyServiceTest", "target-agent");

        assertThat(command).contains(
            "mvn",
            "-B",
            "-Dproject.build.directory=target-agent",
            "-DargLine=-Xmx512m -XX:+TieredCompilation -XX:TieredStopAtLevel=1",
            "test",
            "-Dtest=MyServiceTest"
        );
        assertThat(command).doesNotContain("-pl", "-am");
    }

    @Test
    @DisplayName("Given multi-module reactor root - when buildTestCommand called with submodule - then correctly formats -pl submodule -am with -Dtest and target directory")
    void givenMultiModuleReactorRoot_whenBuildTestCommandCalledWithSubmodule_thenCorrectlyFormatsPlSubmoduleAmWithDtestAndTargetDirectory(@TempDir Path tempDir) {
        List<String> command = buildSystem.buildTestCommand(tempDir, "services/payment-service", "*Test", "target-agent");

        assertThat(command).contains(
            "mvn",
            "-B",
            "-Dproject.build.directory=target-agent",
            "-DargLine=-Xmx512m -XX:+TieredCompilation -XX:TieredStopAtLevel=1",
            "-pl",
            "services/payment-service",
            "-am",
            "test",
            "-Dtest=*Test"
        );
    }

    @Test
    @DisplayName("Given blank testPattern - when buildTestCommand called - then omits -Dtest flag")
    void givenBlankTestPattern_whenBuildTestCommandCalled_thenOmitsDtestFlag(@TempDir Path tempDir) {
        List<String> command = buildSystem.buildTestCommand(tempDir, null, "  ", null);

        assertThat(command).contains("mvn", "-B", "-Dproject.build.directory=target-agent", "test");
        assertThat(command).noneMatch(arg -> arg.startsWith("-Dtest="));
    }

    @Test
    @DisplayName("Given successful clean output - when sanitizeOutput called - then reports zero errors and clean output")
    void givenSuccessfulCleanOutput_whenSanitizeOutputCalled_thenReportsZeroErrorsAndCleanOutput() {
        String stdout = """
            [INFO] Scanning for projects...
            [INFO] Building my-app 1.0.0
            [INFO] --- maven-compiler-plugin:3.13.0:compile (default-compile) @ my-app ---
            [INFO] Compiling 12 source files to target-agent/classes
            [INFO] ------------------------------------------------------------------------
            [INFO] BUILD SUCCESS
            [INFO] ------------------------------------------------------------------------
            """;

        SanitizedLog log = buildSystem.sanitizeOutput(stdout, "");

        assertThat(log.hasErrors()).isFalse();
        assertThat(log.exitCode()).isEqualTo(0);
        assertThat(log.errorCount()).isEqualTo(0);
        assertThat(log.errorLines()).isEmpty();
        assertThat(log.cleanStdout()).contains("Compiling 12 source files", "BUILD SUCCESS");
    }

    @Test
    @DisplayName("Given realistic Maven compilation failure log with downloader clutter - when sanitizeOutput called - then strips download headers and isolates compiler errorLines")
    void givenRealisticMavenCompilationFailureLog_whenSanitizeOutputCalled_thenStripsDownloadHeadersAndIsolatesCompilerErrorLines() {
        String stdout = String.join("\n",
            "Downloading from central: https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.pom",
            "Progress (1): 2.8/15 kB",
            "Progress (1): 15 kB",
            "Downloaded from central: https://repo.maven.apache.org/maven2/org/apache/commons/commons-lang3/3.14.0/commons-lang3-3.14.0.pom (15 kB at 42 kB/s)",
            "[INFO] Scanning for projects...",
            "[INFO] ---------------------< com.example:order-service >----------------------",
            "[INFO] Building order-service 1.0.0-SNAPSHOT",
            "[INFO] ",
            "[INFO] --- maven-compiler-plugin:3.13.0:compile (default-compile) @ order-service ---",
            "[INFO] Changes detected - recompiling the module!",
            "[INFO] Compiling 8 source files with javac [debug target 21] to target-agent/classes",
            "[ERROR] /home/dev/order-service/src/main/java/com/example/OrderService.java:[42,18] cannot find symbol",
            "  symbol:   method calculateDiscount()",
            "  location: variable customer of type com.example.Customer",
            "[ERROR] /home/dev/order-service/src/main/java/com/example/OrderService.java:[55,30] incompatible types: java.lang.String cannot be converted to java.math.BigDecimal",
            "[INFO] 2 errors ",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] BUILD FAILURE",
            "[INFO] ------------------------------------------------------------------------"
        );

        SanitizedLog log = buildSystem.sanitizeOutput(stdout, "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorCount()).isEqualTo(2);
        assertThat(log.errorLines()).hasSize(2);
        assertThat(log.errorLines().get(0)).contains("cannot find symbol", "calculateDiscount()");
        assertThat(log.errorLines().get(1)).contains("incompatible types", "java.math.BigDecimal");

        // Verify downloader clutter is completely stripped from clean output
        assertThat(log.cleanStdout())
            .doesNotContain("Downloading from central", "Progress (1)", "Downloaded from central");
        assertThat(log.cleanStdout())
            .contains("cannot find symbol", "incompatible types", "BUILD FAILURE");
    }

    @Test
    @DisplayName("Given realistic Surefire assertion failure and stack trace log - when sanitizeOutput called - then extracts failure summary, test method, and failure causes into errorLines")
    void givenRealisticSurefireAssertionFailureAndStackTraceLog_whenSanitizeOutputCalled_thenExtractsFailureSummaryTestMethodAndFailureCausesIntoErrorLines() {
        String stdout = String.join("\n",
            "[INFO] -------------------------------------------------------",
            "[INFO]  T E S T S",
            "[INFO] -------------------------------------------------------",
            "[INFO] Running com.example.OrderProcessorTest",
            "[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.123 s <<< FAILURE! -- in com.example.OrderProcessorTest",
            "[ERROR] com.example.OrderProcessorTest.testProcessPayment() -- Time elapsed: 0.045 s <<< FAILURE!",
            "org.opentest4j.AssertionFailedError: expected: <COMPLETED> but was: <FAILED>",
            "\tat com.example.OrderProcessorTest.testProcessPayment(OrderProcessorTest.java:38)",
            "[INFO] ",
            "[INFO] Results:",
            "[INFO] ",
            "[ERROR] Failures: ",
            "[ERROR]   OrderProcessorTest.testProcessPayment:38 expected: <COMPLETED> but was: <FAILED>",
            "[INFO] ",
            "[ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0",
            "[INFO] ",
            "[INFO] ------------------------------------------------------------------------",
            "[INFO] BUILD FAILURE",
            "[INFO] ------------------------------------------------------------------------"
        );

        SanitizedLog log = buildSystem.sanitizeOutput(stdout, "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorCount()).isGreaterThanOrEqualTo(1);
        assertThat(log.errorLines())
            .anyMatch(line -> line.contains("OrderProcessorTest.testProcessPayment") && line.contains("expected: <COMPLETED> but was: <FAILED>"));

        assertThat(log.cleanStdout()).contains("AssertionFailedError: expected: <COMPLETED> but was: <FAILED>");
    }

    @Test
    @DisplayName("Given null or empty logs - when sanitizeOutput called - then handles gracefully without exception")
    void givenNullOrEmptyLogs_whenSanitizeOutputCalled_thenHandlesGracefullyWithoutException() {
        SanitizedLog logNull = buildSystem.sanitizeOutput(null, null);
        assertThat(logNull.hasErrors()).isFalse();
        assertThat(logNull.errorCount()).isEqualTo(0);
        assertThat(logNull.errorLines()).isEmpty();
        assertThat(logNull.cleanStdout()).isEmpty();
        assertThat(logNull.cleanStderr()).isEmpty();

        SanitizedLog logBlank = buildSystem.sanitizeOutput("   ", "   ");
        assertThat(logBlank.hasErrors()).isFalse();
        assertThat(logBlank.cleanStdout()).isEmpty();
    }

    @Test
    @DisplayName("Given explicit exitCode overload - when sanitizeOutput called - then preserves exitCode")
    void givenExplicitExitCodeOverload_whenSanitizeOutputCalled_thenPreservesExitCode() {
        SanitizedLog log = buildSystem.sanitizeOutput(42, "Success message", "");
        assertThat(log.exitCode()).isEqualTo(42);
        assertThat(log.cleanStdout()).contains("Success message");
    }

    @Test
    @DisplayName("Given Windows environment with mvnw.cmd - when resolveExecutable called - then returns mvnw.cmd")
    void givenWindowsEnvironmentWithMvnwCmd_whenResolveExecutableCalled_thenReturnsMvnwCmd(@TempDir Path tempDir) throws IOException {
        String originalOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 11");
            Files.writeString(tempDir.resolve("mvnw.cmd"), "@echo off\n");
            assertThat(buildSystem.resolveExecutable(tempDir)).isEqualTo("mvnw.cmd");
        } finally {
            System.setProperty("os.name", originalOs);
        }
    }

    @Test
    @DisplayName("Given Windows environment with mvnw.bat - when resolveExecutable called - then returns mvnw.cmd")
    void givenWindowsEnvironmentWithMvnwBat_whenResolveExecutableCalled_thenReturnsMvnwCmd(@TempDir Path tempDir) throws IOException {
        String originalOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows Server 2022");
            Files.writeString(tempDir.resolve("mvnw.bat"), "@echo off\n");
            assertThat(buildSystem.resolveExecutable(tempDir)).isEqualTo("mvnw.cmd");
        } finally {
            System.setProperty("os.name", originalOs);
        }
    }

    @Test
    @DisplayName("Given Windows environment without wrapper - when resolveExecutable called - then returns mvn")
    void givenWindowsEnvironmentWithoutWrapper_whenResolveExecutableCalled_thenReturnsMvn(@TempDir Path tempDir) {
        String originalOs = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Windows 10");
            assertThat(buildSystem.resolveExecutable(tempDir)).isEqualTo("mvn");
        } finally {
            System.setProperty("os.name", originalOs);
        }
    }

    @Test
    @DisplayName("Given stderr with compilation errors - when sanitizeOutput called - then populates cleanStderr and errorLines")
    void givenStderrWithCompilationErrors_whenSanitizeOutputCalled_thenPopulatesCleanStderrAndErrorLines() {
        String stderr = "[ERROR] Fatal javac failure in stderr\nDownloading from central: https://repo.maven.org/foo\n";
        SanitizedLog log = buildSystem.sanitizeOutput("", stderr);

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorLines()).contains("[ERROR] Fatal javac failure in stderr");
        assertThat(log.cleanStderr()).contains("[ERROR] Fatal javac failure in stderr");
        assertThat(log.cleanStderr()).doesNotContain("Downloading from central");
    }

    @Test
    @DisplayName("Given Maven noise error lines - when sanitizeOutput called - then ignores noise errors from errorLines")
    void givenMavenNoiseErrorLines_whenSanitizeOutputCalled_thenIgnoresNoiseErrorsFromErrorLines() {
        String stdout = """
            [ERROR] 
            [ERROR] BUILD FAILURE
            [ERROR] -> [Help 1]
            [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
            [ERROR] Re-run Maven using the -X switch to enable full debug logging.
            [ERROR] For more information about the errors, please read documentation.
            """;

        SanitizedLog log = buildSystem.sanitizeOutput(stdout, "");

        assertThat(log.errorLines()).isEmpty();
        assertThat(log.hasErrors()).isFalse();
    }

    @Test
    @DisplayName("Given tab-indented error continuation and AssertionError - when sanitizeOutput called - then appends continuation and extracts AssertionError")
    void givenTabIndentedErrorContinuationAndAssertionError_whenSanitizeOutputCalled_thenAppendsContinuationAndExtractsAssertionError() {
        String stdout = String.join("\n",
            "[ERROR] Main test failure",
            "\tat com.example.MyTest.verify(MyTest.java:10)",
            "[INFO] Next phase",
            "  regular indented info line",
            "java.lang.AssertionError: Condition failed"
        );

        SanitizedLog log = buildSystem.sanitizeOutput(stdout, "");

        assertThat(log.hasErrors()).isTrue();
        assertThat(log.errorLines()).hasSize(2);
        assertThat(log.errorLines().get(0)).isEqualTo("[ERROR] Main test failure\nat com.example.MyTest.verify(MyTest.java:10)");
        assertThat(log.errorLines().get(1)).isEqualTo("java.lang.AssertionError: Condition failed");
    }
}
