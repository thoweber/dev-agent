package guru.thomasweber.devagent.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevAgentConfigLoaderTest {

    private final DevAgentConfigLoader loader = new DevAgentConfigLoader();

    @Test
    @DisplayName("Given null or non-existent file - when load - then returns default configuration")
    void givenMissingFile_whenLoad_thenReturnsDefaults() {
        DevAgentConfig config1 = loader.load(null);
        DevAgentConfig config2 = loader.load(Path.of("non-existent-file.yaml"));

        assertThat(config1).isNotNull();
        assertThat(config1.workspace().backendRoot()).isEqualTo(".");
        assertThat(config1.workspace().frontendRoot()).isEqualTo("frontend");
        assertThat(config1.build().backend().targetDir()).isEqualTo("target-agent");
        assertThat(config1.knowledge().rulesPaths()).isEmpty();
        assertThat(config1.knowledge().retrieval().engine()).isEqualTo("bm25");
        assertThat(config1.knowledge().retrieval().maxAdrsPerPrompt()).isEqualTo(4);
        assertThat(config1.isolation().useWorktrees()).isTrue();
        assertThat(config1.budget().maxTaskCostUsd()).isEqualTo(2.00);

        assertThat(config2).isNotNull();
        assertThat(config2.workspace().backendRoot()).isEqualTo(".");
    }

    @Test
    @DisplayName("Given empty or blank YAML string - when loadFromYaml - then returns default configuration")
    void givenEmptyYaml_whenLoadFromYaml_thenReturnsDefaults() {
        DevAgentConfig configNull = loader.loadFromYaml(null);
        DevAgentConfig configEmpty = loader.loadFromYaml("");
        DevAgentConfig configBlank = loader.loadFromYaml("   \n\t  ");

        assertThat(configNull.workspace().backendRoot()).isEqualTo(".");
        assertThat(configEmpty.workspace().backendRoot()).isEqualTo(".");
        assertThat(configBlank.knowledge().retrieval().engine()).isEqualTo("bm25");
    }

    @Test
    @DisplayName("Given complete YAML configuration - when load - then maps all sections correctly")
    void givenCompleteYaml_whenLoad_thenMapsAllSections(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("dev-agent.yaml");
        Files.writeString(configFile, """
            agent:
              archetype: "REST_PANACHE"
              workspace:
                backend-root: "backend"
                frontend-root: "ui"
                active-backend-module: "service-core"
              build:
                backend:
                  executable: "./mvnw"
                  extra-args: ["-Pfast", "-DskipTests"]
                  dev-services:
                    enabled: true
                  target-dir: "custom-target"
                frontend:
                  executable: "pnpm"
                  custom-test-command: "pnpm test"
                  custom-type-check-command: "pnpm typecheck"
              contract:
                enabled: true
                spec:
                  strategy: "quarkus"
                  output-file: "target/openapi.yaml"
                client:
                  tool: "openapi-generator"
                  target-dir: "ui/src/api"
                  generator: "typescript-angular"
                  additional-properties:
                    ngVersion: "18.0.0"
                    stringEnums: "true"
              models:
                planner:
                  provider: "gemini"
                  model-name: "gemini-1.5-pro"
                  temperature: 0.2
                gatekeeper:
                  provider: "gemini"
                  model-name: "gemini-1.5-flash"
                  temperature: 0.0
                coder:
                  provider: "anthropic"
                  model-name: "claude-3-5-sonnet"
                  temperature: 0.1
                fallback:
                  provider: "ollama"
                  endpoint: "http://localhost:11434"
                  model-name: "qwen2.5-coder:7b"
                  temperature: 0.5
              knowledge:
                rules-paths:
                  - ".agents/rules/**/*.md"
                  - "docs/adr/*.md"
                retrieval:
                  engine: "bm25"
                  max-adrs-per-prompt: 6
                  min-relevance-score: 0.75
              isolation:
                use-worktrees: false
                branch-template: "custom/{task-id}"
                auto-stash-uncommitted: false
                auto-prune-on-complete: false
              delivery:
                github:
                  target-branch: "develop"
                  draft: false
                  labels: ["agent", "pr"]
                ci:
                  enabled: false
                  timeout: "10m"
                  poll-interval: "15s"
                  job-filter-regex: "^build.*"
                sonar:
                  enabled: true
                  project-key: "my-sonar-key"
                coderabbit:
                  enabled: false
                  max-bot-turns: 5
              budget:
                max-task-cost-usd: 5.50
                max-task-tokens: 500000
                max-self-healing-retries: 5
                on-limit-exceeded: "abort"
            """);

        DevAgentConfig config = loader.load(configFile);

        assertThat(config.archetype()).contains("REST_PANACHE");
        assertThat(config.workspace().backendRoot()).isEqualTo("backend");
        assertThat(config.workspace().frontendRoot()).isEqualTo("ui");
        assertThat(config.workspace().activeBackendModule()).contains("service-core");

        assertThat(config.build().backend().executable()).contains("./mvnw");
        assertThat(config.build().backend().extraArgs()).contains(List.of("-Pfast", "-DskipTests"));
        assertThat(config.build().backend().devServices().enabled()).isTrue();
        assertThat(config.build().backend().targetDir()).isEqualTo("custom-target");
        assertThat(config.build().frontend().executable()).contains("pnpm");
        assertThat(config.build().frontend().customTestCommand()).contains("pnpm test");
        assertThat(config.build().frontend().customTypeCheckCommand()).contains("pnpm typecheck");

        assertThat(config.contract().enabled()).isTrue();
        assertThat(config.contract().spec().strategy()).contains("quarkus");
        assertThat(config.contract().spec().outputFile()).contains("target/openapi.yaml");
        assertThat(config.contract().client().tool()).contains("openapi-generator");
        assertThat(config.contract().client().targetDir()).contains("ui/src/api");
        assertThat(config.contract().client().generator()).contains("typescript-angular");
        assertThat(config.contract().client().additionalProperties())
            .containsEntry("ngVersion", "18.0.0")
            .containsEntry("stringEnums", "true");

        assertThat(config.models().planner().provider()).contains("gemini");
        assertThat(config.models().planner().modelName()).contains("gemini-1.5-pro");
        assertThat(config.models().planner().temperature()).contains(0.2);
        assertThat(config.models().gatekeeper().provider()).contains("gemini");
        assertThat(config.models().gatekeeper().modelName()).contains("gemini-1.5-flash");
        assertThat(config.models().gatekeeper().temperature()).contains(0.0);
        assertThat(config.models().coder().provider()).contains("anthropic");
        assertThat(config.models().coder().modelName()).contains("claude-3-5-sonnet");
        assertThat(config.models().coder().temperature()).contains(0.1);
        assertThat(config.models().fallback().provider()).contains("ollama");
        assertThat(config.models().fallback().endpoint()).contains("http://localhost:11434");
        assertThat(config.models().fallback().modelName()).contains("qwen2.5-coder:7b");
        assertThat(config.models().fallback().temperature()).contains(0.5);

        assertThat(config.knowledge().rulesPaths()).contains(List.of(".agents/rules/**/*.md", "docs/adr/*.md"));
        assertThat(config.knowledge().retrieval().engine()).isEqualTo("bm25");
        assertThat(config.knowledge().retrieval().maxAdrsPerPrompt()).isEqualTo(6);
        assertThat(config.knowledge().retrieval().minRelevanceScore()).isEqualTo(0.75);

        assertThat(config.isolation().useWorktrees()).isFalse();
        assertThat(config.isolation().branchTemplate()).isEqualTo("custom/{task-id}");
        assertThat(config.isolation().autoStashUncommitted()).isFalse();
        assertThat(config.isolation().autoPruneOnComplete()).isFalse();

        assertThat(config.delivery().github().targetBranch()).isEqualTo("develop");
        assertThat(config.delivery().github().draft()).isFalse();
        assertThat(config.delivery().github().labels()).contains(List.of("agent", "pr"));
        assertThat(config.delivery().ci().enabled()).isFalse();
        assertThat(config.delivery().ci().timeout()).isEqualTo("10m");
        assertThat(config.delivery().ci().pollInterval()).isEqualTo("15s");
        assertThat(config.delivery().ci().jobFilterRegex()).contains("^build.*");
        assertThat(config.delivery().sonar().enabled()).isTrue();
        assertThat(config.delivery().sonar().projectKey()).contains("my-sonar-key");
        assertThat(config.delivery().coderabbit().enabled()).isFalse();
        assertThat(config.delivery().coderabbit().maxBotTurns()).isEqualTo(5);

        assertThat(config.budget().maxTaskCostUsd()).isEqualTo(5.50);
        assertThat(config.budget().maxTaskTokens()).isEqualTo(500000L);
        assertThat(config.budget().maxSelfHealingRetries()).isEqualTo(5);
        assertThat(config.budget().onLimitExceeded()).isEqualTo("abort");
    }

    @Test
    @DisplayName("Given inline array syntax for rules-paths - when loadFromYaml - then parses list properly")
    void givenInlineArraySyntax_whenLoadFromYaml_thenParsesProperly() {
        String yaml = """
            agent:
              knowledge:
                rules-paths: [ "inline/adrs/*.md" # inline comment
                             , "specs/*.md" ]
            """;

        DevAgentConfig config = loader.loadFromYaml(yaml);

        assertThat(config.knowledge().rulesPaths()).contains(List.of("inline/adrs/*.md", "specs/*.md"));
    }

    @Test
    @DisplayName("Given blank string item in rules-paths - when loadFromYaml - then preserves blank item")
    void givenBlankStringItemInRulesPaths_whenLoadFromYaml_thenPreservesBlankItem() {
        String yaml = """
            agent:
              knowledge:
                rules-paths:
                  - "   "
            """;
        DevAgentConfig config = loader.loadFromYaml(yaml);
        assertThat(config.knowledge().rulesPaths()).contains(List.of("   "));
    }

    @Test
    @DisplayName("Given unreadable file - when load - then throws UncheckedIOException")
    void givenUnreadableFile_whenLoad_thenThrowsUncheckedIOException(@TempDir Path tempDir) throws IOException {
        Path unreadableFile = tempDir.resolve("unreadable.yaml");
        Files.createFile(unreadableFile);
        unreadableFile.toFile().setReadable(false);

        // If OS allows setting unreadable
        if (!unreadableFile.toFile().canRead()) {
            assertThatThrownBy(() -> loader.load(unreadableFile))
                .isInstanceOf(UncheckedIOException.class);
        }
    }
}
