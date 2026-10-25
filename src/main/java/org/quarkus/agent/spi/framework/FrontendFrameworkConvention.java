package org.quarkus.agent.spi.framework;

import java.nio.file.Path;
import java.util.List;

/**
 * Axis 2 SPI: Frontend framework conventions, component guidance, and baseline invariants.
 */
public interface FrontendFrameworkConvention {

    int priority();

    boolean isApplicable(Path frontendRoot);

    String getFrameworkName();

    String getComponentGuidance();

    List<String> getBaselineInvariants();

    Path resolveSourceRoot(Path frontendRoot);
}
