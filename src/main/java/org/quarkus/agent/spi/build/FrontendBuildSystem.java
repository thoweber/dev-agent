package org.quarkus.agent.spi.build;

import java.nio.file.Path;
import java.util.List;

/**
 * Axis 1 SPI: Frontend build tooling execution and output sanitization.
 */
public interface FrontendBuildSystem {

    int priority();

    boolean isApplicable(Path frontendRoot);

    String resolveExecutable(Path frontendRoot);

    List<String> buildTypeCheckCommand(Path frontendRoot);

    List<String> buildTestCommand(Path frontendRoot, String testPattern);

    List<String> buildClientCodegenCommand(Path frontendRoot, Path openApiSchema, Path targetDir);

    SanitizedLog sanitizeOutput(String rawStdout, String rawStderr);
}
