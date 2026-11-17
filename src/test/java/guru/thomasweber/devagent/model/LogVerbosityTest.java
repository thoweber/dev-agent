package guru.thomasweber.devagent.model;

import guru.thomasweber.devagent.config.DevAgentConfig;
import guru.thomasweber.devagent.config.DevAgentConfigLoader;
import org.jboss.logmanager.Level;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.logging.Level.ALL;
import static java.util.logging.Level.OFF;

class LogVerbosityTest {

    @Test
    @DisplayName("Given verbosity counts 0 through 5 - when mapped - then matches exact dev-agent and root levels")
    void givenVerbosityCounts_whenMapped_thenMatchesExactLevels() {
        assertThat(LogVerbosity.fromVerbosityCount(-1)).isEqualTo(LogVerbosity.OFF);
        assertThat(LogVerbosity.fromVerbosityCount(0)).isEqualTo(LogVerbosity.OFF);
        assertThat(LogVerbosity.fromVerbosityCount(1)).isEqualTo(LogVerbosity.WARN);
        assertThat(LogVerbosity.fromVerbosityCount(2)).isEqualTo(LogVerbosity.INFO);
        assertThat(LogVerbosity.fromVerbosityCount(3)).isEqualTo(LogVerbosity.DEBUG);
        assertThat(LogVerbosity.fromVerbosityCount(4)).isEqualTo(LogVerbosity.ALL);
        assertThat(LogVerbosity.fromVerbosityCount(7)).isEqualTo(LogVerbosity.ALL);

        assertThat(LogVerbosity.OFF.isEnabled()).isFalse();
        assertThat(LogVerbosity.OFF.devAgentLevel()).isEqualTo(OFF);
        assertThat(LogVerbosity.OFF.rootLevel()).isEqualTo(OFF);
        assertThat(LogVerbosity.OFF.verbosityCount()).isZero();

        assertThat(LogVerbosity.WARN.isEnabled()).isTrue();
        assertThat(LogVerbosity.WARN.devAgentLevel()).isEqualTo(Level.WARN);
        assertThat(LogVerbosity.WARN.rootLevel()).isEqualTo(Level.WARN);
        assertThat(LogVerbosity.WARN.verbosityCount()).isEqualTo(1);

        assertThat(LogVerbosity.INFO.devAgentLevel()).isEqualTo(Level.INFO);
        assertThat(LogVerbosity.INFO.rootLevel()).isEqualTo(Level.INFO);
        assertThat(LogVerbosity.INFO.verbosityCount()).isEqualTo(2);

        assertThat(LogVerbosity.DEBUG.devAgentLevel()).isEqualTo(Level.DEBUG);
        assertThat(LogVerbosity.DEBUG.rootLevel()).isEqualTo(Level.INFO);
        assertThat(LogVerbosity.DEBUG.verbosityCount()).isEqualTo(3);

        assertThat(LogVerbosity.ALL.devAgentLevel()).isEqualTo(ALL);
        assertThat(LogVerbosity.ALL.rootLevel()).isEqualTo(Level.DEBUG);
        assertThat(LogVerbosity.ALL.verbosityCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("Given string level values - when parsed - then normalizes valid names and rejects invalid inputs")
    void givenStringLevels_whenParsed_thenNormalizesOrRejects() {
        assertThat(LogVerbosity.parse("off")).isEqualTo(LogVerbosity.OFF);
        assertThat(LogVerbosity.parse("warn")).isEqualTo(LogVerbosity.WARN);
        assertThat(LogVerbosity.parse("ERROR")).isEqualTo(LogVerbosity.WARN);
        assertThat(LogVerbosity.parse(" info ")).isEqualTo(LogVerbosity.INFO);
        assertThat(LogVerbosity.parse("debug")).isEqualTo(LogVerbosity.DEBUG);
        assertThat(LogVerbosity.parse("trace")).isEqualTo(LogVerbosity.ALL);
        assertThat(LogVerbosity.parse("ALL")).isEqualTo(LogVerbosity.ALL);

        assertThatThrownBy(() -> LogVerbosity.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogVerbosity.parse("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LogVerbosity.parse("VERBOSE")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Given CLI count and YAML config - when resolveEffective called - then CLI flags override YAML settings")
    void givenCliAndYamlConfig_whenResolveEffective_thenAppliesPrecedence() {
        DevAgentConfigLoader loader = new DevAgentConfigLoader();
        DevAgentConfig disabledConfig = loader.loadFromYaml("");
        DevAgentConfig enabledDebugConfig = loader.loadFromYaml("""
            agent:
              logging:
                enabled: true
                level: DEBUG
            """);

        assertThat(LogVerbosity.resolveEffective(0, null)).isEqualTo(LogVerbosity.OFF);
        assertThat(LogVerbosity.resolveEffective(0, disabledConfig.logging())).isEqualTo(LogVerbosity.OFF);
        assertThat(LogVerbosity.resolveEffective(0, enabledDebugConfig.logging())).isEqualTo(LogVerbosity.DEBUG);
        assertThat(LogVerbosity.resolveEffective(1, enabledDebugConfig.logging())).isEqualTo(LogVerbosity.WARN);
        assertThat(LogVerbosity.resolveEffective(4, disabledConfig.logging())).isEqualTo(LogVerbosity.ALL);
    }
}
