package org.quarkus.agent.spi.framework;

import java.nio.file.Path;
import java.util.List;

/**
 * Axis 2 SPI: Backend framework conventions, baseline invariants, and flags.
 */
public interface BackendFrameworkConvention {

    int priority();

    boolean isApplicable(Path projectRoot);

    String getFrameworkName();

    String getPersonaInstructions();

    List<String> getBaselineInvariants();

    List<String> getTestExecutionFlags();

    List<String> getOpenApiExportFlags(Path targetDir);
}
