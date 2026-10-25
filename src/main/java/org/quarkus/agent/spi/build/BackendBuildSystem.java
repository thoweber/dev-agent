package org.quarkus.agent.spi.build;

import java.nio.file.Path;
import java.util.List;

/**
 * Axis 1 SPI: Backend build tooling execution and output sanitization.
 */
public interface BackendBuildSystem {

    int priority();

    boolean isApplicable(Path projectRoot);

    String resolveExecutable(Path projectRoot);

    List<String> buildCompileCommand(Path projectRoot, String modulePath, String targetDir);

    List<String> buildTestCommand(Path projectRoot, String modulePath, String testPattern, String targetDir);

    SanitizedLog sanitizeOutput(String rawStdout, String rawStderr);
}
