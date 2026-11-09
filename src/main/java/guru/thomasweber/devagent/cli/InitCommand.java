package guru.thomasweber.devagent.cli;

import guru.thomasweber.devagent.workspace.RepoPath;
import guru.thomasweber.devagent.workspace.WorkspaceJailValidator;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.Callable;

@Dependent
@Unremovable
@Command(
    name = "init",
    description = "Inspects the workspace and initializes declarative .agents configuration",
    mixinStandardHelpOptions = true
)
public class InitCommand extends BaseAgentCommand implements Callable<Integer> {

    private static final Set<String> ALL_MANAGED_RULE_FILES = Set.of(
        "01-quarkus-panache-invariants.md",
        "02-rest-validation-rules.md",
        "01-reactive-messaging-invariants.md",
        "01-angular-standalone-signals.md",
        "02-contract-sync.md",
        "01-modulith-package-boundaries.md"
    );

    @Option(names = {"-t", "--type"}, description = "Archetype profile (rest-panache, reactive-messaging, fullstack-angular, modulith)", defaultValue = "rest-panache")
    private String type = "rest-panache";

    @Option(names = {"--force"}, description = "Force overwrite existing configuration files")
    private boolean force;

    @Override
    public Integer call() {
        ArchetypeType archetype = ArchetypeType.fromString(type);
        Path targetRoot = getWorkspaceRoot();
        WorkspaceJailValidator jail = new WorkspaceJailValidator(targetRoot);

        Path agentsDir = jail.validateAndResolve(".agents");
        Path rulesDir = jail.validateAndResolve(".agents/rules");
        RepoPath yamlPath = jail.validateForWrite(".agents/dev-agent.yaml");

        try {
            if (!Files.exists(agentsDir)) {
                Files.createDirectories(agentsDir);
            }
            if (!Files.exists(rulesDir)) {
                Files.createDirectories(rulesDir);
            }

            if (Files.exists(yamlPath.toPath()) && !force) {
                String existingYaml = Files.readString(yamlPath.toPath());
                if (!existingYaml.contains("Archetype: " + archetype.getId())) {
                    getErr().printf("[dev-agent] Workspace already initialized with different archetype. Use --force to overwrite.%n");
                    return 1;
                }
            } else {
                String yamlContent = generateYamlConfig(archetype);
                Files.writeString(yamlPath.toPath(), yamlContent);
            }

            if (force) {
                cleanObsoleteManagedRules(rulesDir, archetype);
            }

            scaffoldArchetypeRules(rulesDir, archetype, jail);

            getOut().printf("[dev-agent] Initialized workspace configuration for archetype '%s' in .agents/%n", archetype.getId());
            return 0;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scaffold .agents configuration", e);
        }
    }

    @Override
    public void run() {
        call();
    }

    private String generateYamlConfig(ArchetypeType archetype) {
        return String.format("""
            # ==============================================================================
            # Quarkus Dev-Agent Declarative Configuration (.agents/dev-agent.yaml)
            # Archetype: %s
            # ==============================================================================

            agent:
              workspace:
                backend-root: "."
              build:
                backend:
                  target-dir: "target-agent"
                  dev-services:
                    enabled: false
              contract:
                enabled: %b
              knowledge:
                rules-paths:
                  - ".agents/rules/**/*.md"
                  - "docs/adr/*.md"
                retrieval:
                  engine: "bm25"
                  max-adrs-per-prompt: 4
              isolation:
                use-worktrees: true
                branch-template: "feature/{task-id}-{story-slug}"
              budget:
                max-task-cost-usd: 2.00
                max-task-tokens: 250000
            """, archetype.getId(), archetype == ArchetypeType.FULLSTACK_ANGULAR);
    }

    private void scaffoldArchetypeRules(Path rulesDir, ArchetypeType archetype, WorkspaceJailValidator jail) throws IOException {
        switch (archetype) {
            case REST_PANACHE -> {
                writeRuleIfMissing(rulesDir.resolve("01-quarkus-panache-invariants.md"), """
                    # Rule: Quarkus Panache & Persistence Best Practices
                    * Use Active Record or Repository pattern consistently.
                    * In micro-TDD loops, ensure dev-services are suppressed in favor of in-memory H2.
                    """, jail);
                writeRuleIfMissing(rulesDir.resolve("02-rest-validation-rules.md"), """
                    # Rule: REST API & DTO Invariants
                    * All endpoint inputs must have Jakarta Validation annotations (@NotNull, @Valid, @Size).
                    * Return Response.created() or Response.ok() with explicit entity DTOs.
                    """, jail);
            }
            case REACTIVE_MESSAGING -> writeRuleIfMissing(rulesDir.resolve("01-reactive-messaging-invariants.md"), """
                # Rule: Reactive Messaging & Mutiny Streams
                * Never block Vert.x event loops. Use @Blocking only when invoking legacy blocking I/O.
                * Prefer Uni and Multi types for reactive asynchronous pipelines.
                """, jail);
            case FULLSTACK_ANGULAR -> {
                writeRuleIfMissing(rulesDir.resolve("01-angular-standalone-signals.md"), """
                    # Rule: Angular Standalone & Signals Reactivity
                    * Components must be standalone: standalone: true.
                    * Bridge OpenAPI RxJS observables to Signals using toSignal() from @angular/core/rxjs-interop.
                    """, jail);
                writeRuleIfMissing(rulesDir.resolve("02-contract-sync.md"), """
                    # Rule: Contract Synchronization
                    * Generated TypeScript API clients reside strictly in frontend/src/app/core/api/generated/.
                    * Never commit manual modifications directly inside generated client directories.
                    """, jail);
            }
            case MODULITH -> writeRuleIfMissing(rulesDir.resolve("01-modulith-package-boundaries.md"), """
                # Rule: Modulith Package Boundaries
                * Enforce hexagonal isolation between module packages.
                * Cross-module communication must proceed through published events or SPI interfaces.
                """, jail);
        }
    }

    private void writeRuleIfMissing(Path path, String content, WorkspaceJailValidator jail) throws IOException {
        RepoPath repoPath = jail.validateForWrite(path.toString());
        if (!Files.exists(repoPath.toPath()) || force) {
            Files.writeString(
                repoPath.toPath(),
                content.stripIndent(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
            );
        }
    }

    private void cleanObsoleteManagedRules(Path rulesDir, ArchetypeType newArchetype) throws IOException {
        Set<String> activeRules = getManagedRuleFilesForArchetype(newArchetype);
        for (String ruleFile : ALL_MANAGED_RULE_FILES) {
            if (!activeRules.contains(ruleFile)) {
                Path obsolete = rulesDir.resolve(ruleFile);
                if (Files.exists(obsolete)) {
                    Files.delete(obsolete);
                }
            }
        }
    }

    private static Set<String> getManagedRuleFilesForArchetype(ArchetypeType archetype) {
        return switch (archetype) {
            case REST_PANACHE -> Set.of("01-quarkus-panache-invariants.md", "02-rest-validation-rules.md");
            case REACTIVE_MESSAGING -> Set.of("01-reactive-messaging-invariants.md");
            case FULLSTACK_ANGULAR -> Set.of("01-angular-standalone-signals.md", "02-contract-sync.md");
            case MODULITH -> Set.of("01-modulith-package-boundaries.md");
        };
    }
}
