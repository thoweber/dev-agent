package guru.thomasweber.devagent.config;

import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Strongly-typed declarative configuration model mapping {@code .agents/dev-agent.yaml}.
 */
@RegisterForReflection
@ConfigMapping(prefix = "agent")
public interface DevAgentConfig {

    Optional<String> archetype();

    WorkspaceConfig workspace();

    BuildConfig build();

    ContractConfig contract();

    ModelsConfig models();

    KnowledgeConfig knowledge();

    IsolationConfig isolation();

    DeliveryConfig delivery();

    BudgetConfig budget();

    interface WorkspaceConfig {
        @WithDefault(".")
        @WithName("backend-root")
        String backendRoot();

        @WithDefault("frontend")
        @WithName("frontend-root")
        String frontendRoot();

        @WithName("active-backend-module")
        Optional<String> activeBackendModule();
    }

    interface BuildConfig {
        BackendBuildConfig backend();

        FrontendBuildConfig frontend();

        interface BackendBuildConfig {
            @WithName("executable")
            Optional<String> executable();

            @WithName("extra-args")
            Optional<List<String>> extraArgs();

            @WithName("dev-services")
            DevServicesConfig devServices();

            @WithDefault("target-agent")
            @WithName("target-dir")
            String targetDir();

            interface DevServicesConfig {
                @WithDefault("false")
                @WithName("enabled")
                boolean enabled();
            }
        }

        interface FrontendBuildConfig {
            @WithName("executable")
            Optional<String> executable();

            @WithName("custom-test-command")
            Optional<String> customTestCommand();

            @WithName("custom-type-check-command")
            Optional<String> customTypeCheckCommand();
        }
    }

    interface ContractConfig {
        @WithDefault("false")
        @WithName("enabled")
        boolean enabled();

        SpecConfig spec();

        ClientConfig client();

        interface SpecConfig {
            @WithName("strategy")
            Optional<String> strategy();

            @WithName("output-file")
            Optional<String> outputFile();
        }

        interface ClientConfig {
            @WithName("tool")
            Optional<String> tool();

            @WithName("target-dir")
            Optional<String> targetDir();

            @WithName("generator")
            Optional<String> generator();

            @WithName("additional-properties")
            Map<String, String> additionalProperties();
        }
    }

    interface ModelsConfig {
        PlannerConfig planner();

        GatekeeperConfig gatekeeper();

        CoderConfig coder();

        FallbackConfig fallback();

        interface ModelEndpointConfig {
            @WithName("provider")
            Optional<String> provider();

            @WithName("model-name")
            Optional<String> modelName();

            @WithName("temperature")
            Optional<Double> temperature();
        }

        interface PlannerConfig extends ModelEndpointConfig {}

        interface GatekeeperConfig extends ModelEndpointConfig {}

        interface CoderConfig extends ModelEndpointConfig {}

        interface FallbackConfig extends ModelEndpointConfig {
            @WithName("endpoint")
            Optional<String> endpoint();
        }
    }

    interface KnowledgeConfig {
        @WithName("rules-paths")
        Optional<List<String>> rulesPaths();

        RetrievalConfig retrieval();

        interface RetrievalConfig {
            @WithDefault("bm25")
            @WithName("engine")
            String engine();

            @WithDefault("4")
            @WithName("max-adrs-per-prompt")
            int maxAdrsPerPrompt();

            @WithDefault("0.65")
            @WithName("min-relevance-score")
            double minRelevanceScore();
        }
    }

    interface IsolationConfig {
        @WithDefault("true")
        @WithName("use-worktrees")
        boolean useWorktrees();

        @WithDefault("feature/{task-id}-{story-slug}")
        @WithName("branch-template")
        String branchTemplate();

        @WithDefault("true")
        @WithName("auto-stash-uncommitted")
        boolean autoStashUncommitted();

        @WithDefault("true")
        @WithName("auto-prune-on-complete")
        boolean autoPruneOnComplete();
    }

    interface DeliveryConfig {
        GithubConfig github();

        CiConfig ci();

        SonarConfig sonar();

        CoderabbitConfig coderabbit();

        interface GithubConfig {
            @WithDefault("main")
            @WithName("target-branch")
            String targetBranch();

            @WithDefault("true")
            @WithName("draft")
            boolean draft();

            @WithName("labels")
            Optional<List<String>> labels();
        }

        interface CiConfig {
            @WithDefault("true")
            @WithName("enabled")
            boolean enabled();

            @WithDefault("20m")
            @WithName("timeout")
            String timeout();

            @WithDefault("20s")
            @WithName("poll-interval")
            String pollInterval();

            @WithName("job-filter-regex")
            Optional<String> jobFilterRegex();
        }

        interface SonarConfig {
            @WithDefault("false")
            @WithName("enabled")
            boolean enabled();

            @WithName("project-key")
            Optional<String> projectKey();
        }

        interface CoderabbitConfig {
            @WithDefault("true")
            @WithName("enabled")
            boolean enabled();

            @WithDefault("2")
            @WithName("max-bot-turns")
            int maxBotTurns();
        }
    }

    interface BudgetConfig {
        @WithDefault("2.00")
        @WithName("max-task-cost-usd")
        double maxTaskCostUsd();

        @WithDefault("250000")
        @WithName("max-task-tokens")
        long maxTaskTokens();

        @WithDefault("3")
        @WithName("max-self-healing-retries")
        int maxSelfHealingRetries();

        @WithDefault("rollback")
        @WithName("on-limit-exceeded")
        String onLimitExceeded();
    }
}
