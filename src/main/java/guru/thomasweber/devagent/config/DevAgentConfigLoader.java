package guru.thomasweber.devagent.config;

import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.yaml.YamlConfigSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and validates {@link DevAgentConfig} declarative configuration from YAML sources.
 */
@ApplicationScoped
public class DevAgentConfigLoader {

    public DevAgentConfig load(Path configFile) {
        if (configFile == null || !Files.isRegularFile(configFile)) {
            return loadFromYaml("");
        }
        try {
            String content = Files.readString(configFile);
            return loadFromYaml(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read configuration file: " + configFile, e);
        }
    }

    public DevAgentConfig loadFromYaml(String yamlContent) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
            .addDefaultInterceptors()
            .addDiscoveredConverters()
            .withMapping(DevAgentConfig.class)
            .withValidateUnknown(false);

        if (yamlContent != null && !yamlContent.isBlank()) {
            builder.withSources(new YamlConfigSource("dev-agent.yaml", yamlContent));
        }

        SmallRyeConfig config = builder.build();
        return config.getConfigMapping(DevAgentConfig.class);
    }
}
