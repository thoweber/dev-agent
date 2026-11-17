package guru.thomasweber.devagent.logging;

import guru.thomasweber.devagent.config.DevAgentConfig.LoggingConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import guru.thomasweber.devagent.model.LogVerbosity;
import guru.thomasweber.devagent.model.SessionId;
import guru.thomasweber.devagent.model.SessionLoggingHandle;
import guru.thomasweber.devagent.model.SessionLoggingRequest;
import guru.thomasweber.devagent.workspace.SecretRedactionFilter;
import guru.thomasweber.devagent.workspace.WorkspaceException;
import org.jboss.logmanager.Level;
import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.Logger;
import org.jboss.logmanager.handlers.ConsoleHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionLoggingServiceTest {

    @TempDir
    Path tempDir;

    private LogContext logContext;
    private Path defaultLogDir;
    private SessionLoggingService service;
    private final DevAgentConfigLoader configLoader = new DevAgentConfigLoader();

    @BeforeEach
    void setUp() {
        logContext = LogContext.create();
        defaultLogDir = tempDir.resolve("default-logs");
        Clock fixedClock = Clock.fixed(Instant.parse("2026-11-17T00:45:12.123Z"), ZoneOffset.UTC);
        service = new SessionLoggingService(logContext, new SecretRedactionFilter(), defaultLogDir, fixedClock);
    }

    @AfterEach
    void tearDown() throws Exception {
        service.close();
        logContext.close();
    }

    @Test
    @DisplayName("Given default configuration and no -v flags - when configureSession called - then keeps loggers OFF and creates no log files")
    void givenDefaultOffConfig_whenConfigureSession_thenCreatesNoFiles() {
        SessionLoggingRequest request = SessionLoggingRequest.builder()
            .workspaceRoot(tempDir)
            .cliVerbosityCount(0)
            .loggingConfig(configLoader.loadFromYaml("").logging())
            .sessionId(SessionId.of("sess-off"))
            .commandName("status")
            .build();

        SessionLoggingHandle handle = service.configureSession(request);

        assertThat(handle.verbosity()).isEqualTo(LogVerbosity.OFF);
        assertThat(handle.sessionLogFile()).isNull();
        assertThat(handle.consoleEnabled()).isFalse();
        assertThat(service.activeVerbosityCount()).isZero();
        assertThat(Files.exists(defaultLogDir)).isFalse();
        assertThat(logContext.getLogger("").getLevel()).isEqualTo(Level.OFF);
        assertThat(logContext.getLogger("guru.thomasweber.devagent").getLevel()).isEqualTo(Level.OFF);
    }

    @Test
    @DisplayName("Given -vvv CLI flag - when configureSession called - then sets dev-agent=DEBUG, third-party=INFO, writes per-session redacted file")
    void givenTripleVerboseFlag_whenConfigureSession_thenAppliesScopedLevelsAndRedactsSecrets() throws IOException {
        SessionLoggingRequest request = SessionLoggingRequest.builder()
            .workspaceRoot(tempDir)
            .cliVerbosityCount(3)
            .loggingConfig(configLoader.loadFromYaml("").logging())
            .sessionId(SessionId.of("sess-debug-1"))
            .commandName("feature")
            .build();

        SessionLoggingHandle handle = service.configureSession(request);

        assertThat(handle.verbosity()).isEqualTo(LogVerbosity.DEBUG);
        assertThat(handle.sessionLogFile()).isNotNull();
        assertThat(handle.sessionLogFile().getFileName().toString())
            .startsWith("20261117-004512123-sess-debug-1")
            .endsWith(".log");
        assertThat(service.activeVerbosityCount()).isEqualTo(3);

        Logger appLogger = logContext.getLogger("guru.thomasweber.devagent.TestApp");
        Logger frameworkLogger = logContext.getLogger("io.quarkus.TestFramework");

        appLogger.log(Level.DEBUG, "App debug with token=ghp_123456789012345678901234567890123456");
        appLogger.log(Level.TRACE, "App trace should be filtered at -vvv");
        frameworkLogger.log(Level.INFO, "Framework info should be recorded");
        frameworkLogger.log(Level.DEBUG, "Framework debug should be filtered at -vvv");

        service.close();

        String content = Files.readString(handle.sessionLogFile(), StandardCharsets.UTF_8);
        assertThat(content)
            .contains("Starting dev-agent session sess-debug-1", "App debug with token=***REDACTED_CREDENTIAL***", "Framework info should be recorded")
            .doesNotContain("ghp_123456789012345678901234567890123456", "App trace should be filtered", "Framework debug should be filtered");
    }

    @Test
    @DisplayName("Given -vvvv CLI flag - when configureSession called - then sets dev-agent=ALL and third-party=DEBUG")
    void givenQuadVerboseFlag_whenConfigureSession_thenEnablesAllForDevAgentAndDebugForThirdParty() throws IOException {
        SessionLoggingRequest request = SessionLoggingRequest.builder()
            .workspaceRoot(tempDir)
            .cliVerbosityCount(4)
            .loggingConfig(configLoader.loadFromYaml("").logging())
            .sessionId(SessionId.of("sess-all-1"))
            .commandName("worker")
            .build();

        SessionLoggingHandle handle = service.configureSession(request);
        assertThat(handle.verbosity()).isEqualTo(LogVerbosity.ALL);

        Logger appLogger = logContext.getLogger("guru.thomasweber.devagent.TraceTest");
        Logger frameworkLogger = logContext.getLogger("io.quarkus.DebugTest");

        appLogger.log(Level.TRACE, "Deep trace inside dev-agent");
        frameworkLogger.log(Level.DEBUG, "Framework debug captured at -vvvv");
        frameworkLogger.log(Level.TRACE, "Framework trace still filtered at -vvvv");

        service.close();

        String content = Files.readString(handle.sessionLogFile(), StandardCharsets.UTF_8);
        assertThat(content)
            .contains("Deep trace inside dev-agent", "Framework debug captured at -vvvv")
            .doesNotContain("Framework trace still filtered at -vvvv");
    }

    @Test
    @DisplayName("Given -v and sequential sessions - when configured twice - then creates separate log files per session")
    void givenSequentialSessions_whenConfiguredTwice_thenCreatesUniqueSessionFiles() throws IOException {
        LoggingConfig config = configLoader.loadFromYaml("").logging();

        SessionLoggingHandle first = service.configureSession(
            SessionLoggingRequest.builder()
                .workspaceRoot(tempDir)
                .cliVerbosityCount(1)
                .loggingConfig(config)
                .commandName("status")
                .build()
        );
        logContext.getLogger("guru.thomasweber.devagent.Session1").log(Level.WARN, "First session warning");

        SessionLoggingHandle second = service.configureSession(
            SessionLoggingRequest.builder()
                .workspaceRoot(tempDir)
                .cliVerbosityCount(1)
                .loggingConfig(config)
                .commandName("status")
                .build()
        );
        logContext.getLogger("guru.thomasweber.devagent.Session2").log(Level.WARN, "Second session warning");
        service.close();

        assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        assertThat(first.sessionLogFile()).isNotEqualTo(second.sessionLogFile());
        assertThat(Files.readString(first.sessionLogFile())).contains("First session warning").doesNotContain("Second session warning");
        assertThat(Files.readString(second.sessionLogFile())).contains("Second session warning").doesNotContain("First session warning");
    }

    @Test
    @DisplayName("Given YAML opt-in with consoleEnabled=true and fileEnabled=false - when configureSession called - then attaches stderr ConsoleHandler only")
    void givenYamlConsoleOnly_whenConfigureSession_thenAttachesConsoleHandlerOnly() {
        LoggingConfig yamlConfig = configLoader.loadFromYaml("""
            agent:
              logging:
                enabled: true
                level: INFO
                file-enabled: false
                console-enabled: true
            """).logging();

        SessionLoggingHandle handle = service.configureSession(
            SessionLoggingRequest.builder()
                .workspaceRoot(tempDir)
                .cliVerbosityCount(0)
                .loggingConfig(yamlConfig)
                .sessionId(SessionId.of("sess-stderr"))
                .commandName("status")
                .build()
        );

        assertThat(handle.verbosity()).isEqualTo(LogVerbosity.INFO);
        assertThat(handle.sessionLogFile()).isNull();
        assertThat(handle.consoleEnabled()).isTrue();
        Handler[] attached = logContext.getLogger("").getHandlers();
        assertThat(Arrays.stream(attached))
            .hasSize(1)
            .allSatisfy(handler -> {
                assertThat(handler).isInstanceOf(ConsoleHandler.class);
                assertThat(handler.getFormatter()).isInstanceOf(SecretRedactingFormatter.class);
            });

        service.close();
        assertThat(logContext.getLogger("").getHandlers()).isEmpty();
    }

    @Test
    @DisplayName("Given max-files limit and workspace-relative dir - when multiple sessions created - then prunes oldest session logs")
    void givenMaxFilesLimit_whenSessionsCreated_thenPrunesOldestLogs() throws IOException {
        Path workspaceLogs = tempDir.resolve(".agents/logs");
        Files.createDirectories(workspaceLogs);
        Path old1 = Files.writeString(workspaceLogs.resolve("20261116-000001000-old1.log"), "old1");
        Path old2 = Files.writeString(workspaceLogs.resolve("20261116-000002000-old2.log"), "old2");

        LoggingConfig yamlConfig = configLoader.loadFromYaml("""
            agent:
              logging:
                enabled: true
                level: WARN
                dir: ".agents/logs"
                max-files: 2
            """).logging();

        SessionLoggingHandle handle = service.configureSession(
            SessionLoggingRequest.builder()
                .workspaceRoot(tempDir)
                .cliVerbosityCount(0)
                .loggingConfig(yamlConfig)
                .sessionId(SessionId.of("sess-new"))
                .commandName("plan")
                .build()
        );
        service.close();

        assertThat(Files.exists(old1)).isFalse();
        assertThat(Files.exists(old2)).isTrue();
        assertThat(Files.exists(handle.sessionLogFile())).isTrue();
        try (Stream<Path> files = Files.list(workspaceLogs)) {
            List<Path> remaining = files.toList();
            assertThat(remaining).hasSize(2);
        }
    }

    @Test
    @DisplayName("Given path traversal or symlink log directory or unsafe sessionId - when configureSession called - then throws SecurityException")
    void givenUnsafePathsOrSessionId_whenConfigureSession_thenThrowsSecurityException() throws IOException {
        LoggingConfig traversalConfig = configLoader.loadFromYaml("""
            agent:
              logging:
                enabled: true
                dir: "../../outside-logs"
            """).logging();

        SessionLoggingRequest traversalRequest = SessionLoggingRequest.builder()
            .workspaceRoot(tempDir)
            .cliVerbosityCount(1)
            .loggingConfig(traversalConfig)
            .sessionId(SessionId.of("sess-ok"))
            .commandName("status")
            .build();
        assertThatThrownBy(() -> service.configureSession(traversalRequest))
            .isInstanceOf(SecurityException.class);

        Path targetOutside = tempDir.resolve("target-outside");
        Files.createDirectories(targetOutside);
        Path symlinkDir = Files.createSymbolicLink(tempDir.resolve("sym-logs"), targetOutside);
        LoggingConfig symlinkConfig = configLoader.loadFromYaml("""
            agent:
              logging:
                enabled: true
                dir: "sym-logs"
            """).logging();

        SessionLoggingRequest symlinkRequest = SessionLoggingRequest.builder()
            .workspaceRoot(tempDir)
            .cliVerbosityCount(1)
            .loggingConfig(symlinkConfig)
            .sessionId(SessionId.of("sess-ok"))
            .commandName("status")
            .build();
        assertThatThrownBy(() -> service.configureSession(symlinkRequest))
            .isInstanceOf(SecurityException.class);

        SessionLoggingRequest unsafeSessionRequest = SessionLoggingRequest.builder()
            .workspaceRoot(tempDir)
            .cliVerbosityCount(1)
            .loggingConfig(configLoader.loadFromYaml("").logging())
            .sessionId(SessionId.of("../evil"))
            .commandName("status")
            .build();
        assertThatThrownBy(() -> service.configureSession(unsafeSessionRequest))
            .isInstanceOf(SecurityException.class);
        assertThat(symlinkDir).isSymbolicLink();
    }

    @Test
    @DisplayName("Given null requests, same-ms collisions, tilde/absolute paths, and disabled sinks - when configured - then handles all edge branches cleanly")
    void givenEdgeCasesAndAlternativeDirModes_whenConfigured_thenHandlesCleanly() throws IOException {
        assertThat(new SessionLoggingService().activeVerbosityCount()).isZero();
        assertThat(service.activeVerbosityCount()).isZero();
        assertThat(service.configureSession(null).verbosity()).isEqualTo(LogVerbosity.OFF);

        SessionLoggingHandle firstCollision = service.configureSession(
            SessionLoggingRequest.builder()
                .workspaceRoot(tempDir)
                .cliVerbosityCount(2)
                .loggingConfig(configLoader.loadFromYaml("agent:\n  logging:\n    dir: '   '\n").logging())
                .sessionId(SessionId.of("same-id"))
                .commandName("   ")
                .build()
        );
        SessionLoggingHandle secondCollision = service.configureSession(
            SessionLoggingRequest.builder()
                .workspaceRoot(tempDir)
                .cliVerbosityCount(2)
                .loggingConfig(null)
                .sessionId(SessionId.of("same-id"))
                .commandName("status")
                .build()
        );
        assertThat(secondCollision.sessionLogFile()).isNotEqualTo(firstCollision.sessionLogFile());
        assertThat(secondCollision.sessionLogFile().getFileName().toString()).endsWith("-same-id-1.log");

        Path absDir = tempDir.resolve("abs-logs");
        SessionLoggingHandle absHandle = service.configureSession(
            SessionLoggingRequest.builder()
                .workspaceRoot(null)
                .cliVerbosityCount(1)
                .loggingConfig(
                    configLoader.loadFromYaml(
                        "agent:\n  logging:\n    enabled: true\n    max-files: 0\n    dir: '" + absDir + "'\n"
                    ).logging()
                )
                .sessionId(SessionId.of("abs-1"))
                .build()
        );
        assertThat(absHandle.sessionLogFile()).hasParentRaw(absDir);

        SessionLoggingHandle noSinksHandle = service.configureSession(
            SessionLoggingRequest.builder()
                .workspaceRoot(tempDir)
                .cliVerbosityCount(1)
                .loggingConfig(
                    configLoader.loadFromYaml("agent:\n  logging:\n    file-enabled: false\n    console-enabled: false\n").logging()
                )
                .sessionId(SessionId.of("no-sink"))
                .build()
        );
        assertThat(noSinksHandle.verbosity()).isEqualTo(LogVerbosity.WARN);
        assertThat(noSinksHandle.sessionLogFile()).isNull();
        assertThat(noSinksHandle.consoleEnabled()).isFalse();

        String originalUserHome = System.getProperty("user.home");
        Path fakeHome = Files.createDirectories(tempDir.resolve("fake-home"));
        try {
            System.setProperty("user.home", fakeHome.toString());
            SessionLoggingHandle tildeHandle = service.configureSession(
                SessionLoggingRequest.builder()
                    .cliVerbosityCount(1)
                    .loggingConfig(configLoader.loadFromYaml("agent:\n  logging:\n    dir: '~/tilde-logs'\n").logging())
                    .sessionId(SessionId.of("tilde-1"))
                    .build()
            );
            assertThat(tildeHandle.sessionLogFile()).hasParentRaw(fakeHome.resolve("tilde-logs"));

            SessionLoggingHandle tildeRootHandle = service.configureSession(
                SessionLoggingRequest.builder()
                    .cliVerbosityCount(1)
                    .loggingConfig(configLoader.loadFromYaml("agent:\n  logging:\n    dir: '~'\n").logging())
                    .sessionId(SessionId.of("tilde-root"))
                    .build()
            );
            assertThat(tildeRootHandle.sessionLogFile()).hasParentRaw(fakeHome);

            SessionLoggingHandle thirdCollision = service.configureSession(
                SessionLoggingRequest.builder()
                    .cliVerbosityCount(1)
                    .loggingConfig(configLoader.loadFromYaml("agent:\n  logging:\n    dir: '~'\n").logging())
                    .sessionId(SessionId.of("tilde-root"))
                    .build()
            );
            SessionLoggingHandle fourthCollision = service.configureSession(
                SessionLoggingRequest.builder()
                    .cliVerbosityCount(1)
                    .loggingConfig(configLoader.loadFromYaml("agent:\n  logging:\n    dir: '~'\n").logging())
                    .sessionId(SessionId.of("tilde-root"))
                    .build()
            );
            assertThat(thirdCollision.sessionLogFile().getFileName().toString()).endsWith("-tilde-root-1.log");
            assertThat(fourthCollision.sessionLogFile().getFileName().toString()).endsWith("-tilde-root-2.log");

            Path symTildeTarget = Files.createDirectories(tempDir.resolve("sym-tilde-target"));
            Files.createSymbolicLink(fakeHome.resolve("bad-link"), symTildeTarget);
            SessionLoggingRequest badTildeRequest = SessionLoggingRequest.builder()
                .cliVerbosityCount(1)
                .loggingConfig(configLoader.loadFromYaml("agent:\n  logging:\n    dir: '~/bad-link'\n").logging())
                .build();
            assertThatThrownBy(() -> service.configureSession(badTildeRequest))
                .isInstanceOf(SecurityException.class);
        } finally {
            System.setProperty("user.home", originalUserHome);
        }

        Path regularFileAsDir = Files.writeString(tempDir.resolve("not-a-dir"), "blocking");
        SessionLoggingRequest blockedDirRequest = SessionLoggingRequest.builder()
            .workspaceRoot(tempDir)
            .cliVerbosityCount(1)
            .loggingConfig(
                configLoader.loadFromYaml("agent:\n  logging:\n    dir: '" + regularFileAsDir + "'\n").logging()
            )
            .build();
        assertThatThrownBy(() -> service.configureSession(blockedDirRequest))
            .isInstanceOf(WorkspaceException.class);
    }
}
