package org.quarkus.agent.cli;

import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainLauncher;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkus.agent.testutil.CliOutputAssert.assertThatCli;

@QuarkusMainTest
class DevAgentIT {

    @Test
    @DisplayName("Given dev-agent --version - when launched - then outputs version banner with exit code 0")
    void testRootVersion(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("--version");
        assertThatCli(result.exitCode(), result.getOutput())
            .isSuccess()
            .containsAnsiFree("dev-agent 1.0.0-SNAPSHOT");
    }

    @Test
    @DisplayName("Given dev-agent --help - when launched - then lists all subcommands with exit code 0")
    void testRootHelp(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("--help");
        assertThatCli(result.exitCode(), result.getOutput())
            .isSuccess()
            .containsAnsiFree("Autonomous full-stack software engineering CLI")
            .containsAnsiFree("Commands:")
            .containsAnsiFree("init")
            .containsAnsiFree("plan")
            .containsAnsiFree("feature")
            .containsAnsiFree("status")
            .containsAnsiFree("logs")
            .containsAnsiFree("resume")
            .containsAnsiFree("abort")
            .containsAnsiFree("metrics");
    }

    @Test
    @DisplayName("Given dev-agent status - when launched without active tasks - then outputs socket check with exit code 0")
    void testStatusCommand(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("status");
        assertThatCli(result.exitCode(), result.getOutput())
            .isSuccess()
            .containsAnsiFree("Checking active task sockets...")
            .containsAnsiFree("No active background tasks detected.");
    }

    @Test
    @DisplayName("Given dev-agent metrics - when launched - then outputs spend summary with exit code 0")
    void testMetricsCommand(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("metrics");
        assertThatCli(result.exitCode(), result.getOutput())
            .isSuccess()
            .containsAnsiFree("Telemetry metrics:")
            .containsAnsiFree("0 tokens consumed ($0.00 USD)");
    }

    @Test
    @DisplayName("Given unknown subcommand - when launched - then fails with non-zero exit code")
    void testUnknownSubcommand(QuarkusMainLauncher launcher) {
        LaunchResult result = launcher.launch("unrecognized-subcommand-xyz");
        assertThat(result.exitCode()).isNotZero();
    }
}
