package guru.thomasweber.devagent.logging;

import guru.thomasweber.devagent.DevAgentCommand;
import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.LogVerbosity;
import guru.thomasweber.devagent.model.SessionLoggingHandle;
import guru.thomasweber.devagent.workspace.SecretRedactionFilter;
import io.quarkus.runtime.ShutdownEvent;
import org.jboss.logmanager.LogContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionLoggingLifecycleTest {

    @TempDir
    Path tempDir;

    private LogContext logContext;
    private Path defaultLogsDir;
    private SessionLoggingService loggingService;
    private SessionLoggingLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        logContext = LogContext.create();
        defaultLogsDir = tempDir.resolve("user-logs");
        loggingService = new SessionLoggingService(
            logContext,
            new SecretRedactionFilter(),
            defaultLogsDir,
            Clock.fixed(Instant.parse("2026-11-17T00:50:00.000Z"), ZoneOffset.UTC)
        );
        lifecycle = new SessionLoggingLifecycle(loggingService, new DevAgentConfigLoader());
    }

    @AfterEach
    void tearDown() throws Exception {
        lifecycle.onShutdown(new ShutdownEvent());
        logContext.close();
    }

    @Test
    @DisplayName("Given -vvv on subcommand parse result - when onParseResult triggered - then opens per-session log and closes on shutdown")
    void givenVerboseSubcommandParseResult_whenOnParseResultAndShutdown_thenActivatesAndClosesCleanly() {
        DevAgentCommand root = new DevAgentCommand();
        CommandLine cmd = new CommandLine(root);
        ParseResult parseResult = cmd.parseArgs("--dir=" + tempDir.toString(), "status", "-vvv");

        lifecycle.onParseResult(parseResult);

        SessionLoggingHandle handle = loggingService.currentHandle();
        assertThat(handle).isNotNull();
        assertThat(handle.verbosity()).isEqualTo(LogVerbosity.DEBUG);
        assertThat(handle.sessionLogFile()).isNotNull();
        assertThat(Files.exists(handle.sessionLogFile())).isTrue();

        lifecycle.onShutdown(new ShutdownEvent());
        assertThat(loggingService.currentHandle()).isNull();
        assertThat(logContext.getLogger("").getHandlers()).isEmpty();
    }

    @Test
    @DisplayName("Given default parse result with YAML logging enabled - when onParseResult triggered - then activates YAML verbosity")
    void givenYamlOptIn_whenOnParseResult_thenActivatesConfiguredLogging() throws IOException {
        Files.createDirectories(tempDir.resolve(".agents"));
        Files.writeString(tempDir.resolve(".agents/dev-agent.yaml"), """
            agent:
              logging:
                enabled: true
                level: INFO
                dir: ".agents/session-logs"
            """);

        DevAgentCommand root = new DevAgentCommand();
        CommandLine cmd = new CommandLine(root);
        ParseResult parseResult = cmd.parseArgs("--dir=" + tempDir.toString(), "status");

        lifecycle.onParseResult(parseResult);

        SessionLoggingHandle handle = loggingService.currentHandle();
        assertThat(handle).isNotNull();
        assertThat(handle.verbosity()).isEqualTo(LogVerbosity.INFO);
        assertThat(handle.sessionLogFile()).isNotNull();
        Path expectedLogsDir = tempDir.toRealPath().resolve(".agents/session-logs");
        assertThat(handle.sessionLogFile()).hasParentRaw(expectedLogsDir);
    }

    @Test
    @DisplayName("Given malformed dev-agent.yaml and -v flag - when onParseResult triggered - then falls back cleanly to CLI verbosity without leaking parser message")
    void givenMalformedYamlAndCliVerbose_whenOnParseResult_thenFallsBackCleanly() throws IOException {
        Files.createDirectories(tempDir.resolve(".agents"));
        Files.writeString(
            tempDir.resolve(".agents/dev-agent.yaml"),
            "agent:\n  token: \"unterminated-secret-value\n  logging:\n    enabled: [unclosed"
        );

        Logger lifecycleJulLogger = Logger.getLogger(SessionLoggingLifecycle.class.getName());
        List<String> capturedWarnings = new ArrayList<>();
        SimpleFormatter formatter = new SimpleFormatter();
        Handler captureHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                capturedWarnings.add(formatter.formatMessage(record));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        lifecycleJulLogger.addHandler(captureHandler);
        try {
            DevAgentCommand root = new DevAgentCommand();
            CommandLine cmd = new CommandLine(root);
            ParseResult parseResult = cmd.parseArgs("-v", "--dir=" + tempDir.toString());

            lifecycle.onParseResult(parseResult);

            SessionLoggingHandle handle = loggingService.currentHandle();
            assertThat(handle).isNotNull();
            assertThat(handle.verbosity()).isEqualTo(LogVerbosity.WARN);
            assertThat(handle.sessionLogFile()).isNotNull();
        } finally {
            lifecycleJulLogger.removeHandler(captureHandler);
        }

        assertThat(capturedWarnings)
            .singleElement()
            .satisfies(warning -> {
                assertThat(warning).contains("Using default configuration due to invalid .agents/dev-agent.yaml:");
                assertThat(warning).doesNotContain("unterminated-secret-value", "unclosed");
            });
    }

    @Test
    @DisplayName("Given null parseResult, root-only standalone command, and symlinked dev-agent.yaml - when handled - then behaves safely")
    void givenNullAndStandaloneAndSymlinkYaml_whenOnParseResult_thenHandlesSafely() throws IOException {
        assertThat(new SessionLoggingLifecycle()).isNotNull();

        lifecycle.onParseResult(null);
        assertThat(loggingService.currentHandle()).isNull();

        DevAgentConfig defaultConfig = new DevAgentConfigLoader().loadFromYaml("");
        DevAgentConfigLoader isolatedLoader = mock(DevAgentConfigLoader.class);
        when(isolatedLoader.loadForWorkspace(any())).thenReturn(defaultConfig);
        SessionLoggingLifecycle isolatedLifecycle = new SessionLoggingLifecycle(loggingService, isolatedLoader);

        ParseResult standalone = new CommandLine(new StandaloneCmd()).parseArgs();
        isolatedLifecycle.onParseResult(standalone);
        assertThat(loggingService.currentHandle()).isNotNull();
        assertThat(loggingService.currentHandle().verbosity()).isEqualTo(LogVerbosity.OFF);

        Path agentsDir = Files.createDirectories(tempDir.resolve(".agents"));
        Path externalYaml = Files.writeString(tempDir.resolve("external.yaml"), "agent:\n  logging:\n    enabled: true\n");
        Files.createSymbolicLink(agentsDir.resolve("dev-agent.yaml"), externalYaml);
        DevAgentCommand root = new DevAgentCommand();
        ParseResult symlinkParse = new CommandLine(root).parseArgs("-v", "--dir=" + tempDir.toString());
        assertThatThrownBy(() -> lifecycle.onParseResult(symlinkParse))
            .isInstanceOf(SecurityException.class);
    }

    @Command(name = "standalone")
    private static class StandaloneCmd implements Runnable {
        @Override
        public void run() {
            // No-op test command
        }
    }
}
