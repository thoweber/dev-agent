package guru.thomasweber.devagent.config;

import guru.thomasweber.devagent.workspace.PathGuard;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.yaml.YamlConfigSource;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Loads and validates {@link DevAgentConfig} declarative configuration from YAML sources.
 */
@ApplicationScoped
public class DevAgentConfigLoader {

    public DevAgentConfig loadForWorkspace(Path repoRoot) {
        if (isNull(repoRoot)) {
            return loadFromYaml("");
        }
        return load(repoRoot.resolve(".agents").resolve("dev-agent.yaml"));
    }

    public DevAgentConfig load(Path configFile) {
        if (isNull(configFile)) {
            return loadFromYaml("");
        }
        if (Files.isSymbolicLink(configFile)) {
            throw new SecurityException("Refusing to read symbolic link configuration file: " + configFile);
        }
        if (!Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)) {
            return loadFromYaml("");
        }
        try {
            String content = PathGuard.readRegularFileBounded(configFile, PathGuard.DEFAULT_MAX_TEXT_FILE_BYTES);
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

        if (nonNull(yamlContent) && !yamlContent.isBlank()) {
            builder.withSources(new YamlConfigSource("dev-agent.yaml", yamlContent));
        }

        SmallRyeConfig config = builder.build();
        return config.getConfigMapping(DevAgentConfig.class);
    }
}
