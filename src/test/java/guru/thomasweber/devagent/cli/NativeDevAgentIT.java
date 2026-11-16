package guru.thomasweber.devagent.cli;

import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainIntegrationTest;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static guru.thomasweber.devagent.testutil.CliOutputAssert.assertThatCli;

/**
 * Level 3 Native Binary & Subprocess CLI integration test suite.
 * Executes against the compiled Mandrel native binary when built with -Dnative,
 * asserting cold boot latency (&lt;50ms) and AOT reflection safety.
 */
@QuarkusMainIntegrationTest
class NativeDevAgentIT extends DevAgentIT {

    private static final long NATIVE_COLD_BOOT_SLA_MS = 50L;

    @Test
    @DisplayName("Given compiled native binary - when querying version - then cold start latency satisfies SLA")
    void testNativeColdStartSla(QuarkusMainLauncher launcher) {
        long start = System.nanoTime();
        LaunchResult result = launcher.launch("--version");
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        assertThatCli(result.exitCode(), result.getOutput())
            .isSuccess()
            .containsAnsiFree("dev-agent 1.0.0-SNAPSHOT");

        String packageType = System.getProperty("quarkus.package.type", "");
        boolean isNativeProfile = "native".equalsIgnoreCase(packageType)
            || Boolean.getBoolean("quarkus.native");
        if (isNativeProfile) {
            assertThat(durationMs)
                .as("Native binary cold start must be under %d ms according to SLA", NATIVE_COLD_BOOT_SLA_MS)
                .isLessThanOrEqualTo(NATIVE_COLD_BOOT_SLA_MS);
        }
    }

    @Test
    @DisplayName("Given compiled native binary - when querying status --json - then completes under 50ms SLA with valid JSON")
    void testNativeStatusJsonColdStartSla(QuarkusMainLauncher launcher) {
        long startNanos = System.nanoTime();
        LaunchResult result = launcher.launch("status", "--json");
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(result.exitCode()).isZero();
        assertThat(result.getOutput()).contains("\"activeTasks\"");

        String packageType = System.getProperty("quarkus.package.type", "");
        boolean isNativeProfile = "native".equalsIgnoreCase(packageType)
            || Boolean.getBoolean("quarkus.native");
        if (isNativeProfile) {
            assertThat(durationMs)
                .as("Native binary status --json cold start must be under %d ms according to SLA", NATIVE_COLD_BOOT_SLA_MS)
                .isLessThanOrEqualTo(NATIVE_COLD_BOOT_SLA_MS);
        }
    }
}
