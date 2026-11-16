package guru.thomasweber.devagent.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import guru.thomasweber.devagent.cognitive.MarkdownParserService;
import guru.thomasweber.devagent.cognitive.MarkdownParserService.ParsedMarkdown;
import guru.thomasweber.devagent.model.StatusPayload;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.workspace.PathGuard;
import guru.thomasweber.devagent.workspace.StateSocketService;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import guru.thomasweber.devagent.workspace.WorktreeLayout;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

@Dependent
@Unremovable
@Command(
    name = "status",
    description = "Displays non-locking telemetry and active task progress from state.json sockets",
    mixinStandardHelpOptions = true
)
public class StatusCommand extends BaseAgentCommand {

    private static final String EMPTY_MESSAGE = "No active background tasks detected.";

    @Option(names = {"--all"}, description = "Show all historical and active task statuses")
    private boolean all;

    @Option(names = {"--json"}, description = "Output status formatted as JSON")
    private boolean json;

    private final StateSocketService socketService;
    private final MarkdownParserService markdownParserService;
    private final ObjectMapper objectMapper;

    @Inject
    public StatusCommand(
        StateSocketService socketService,
        MarkdownParserService markdownParserService,
        ObjectMapper objectMapper
    ) {
        this.socketService = socketService;
        this.markdownParserService = markdownParserService;
        this.objectMapper = Objects.requireNonNullElseGet(objectMapper, StateSocketService::createDefaultObjectMapper);
    }

    public StatusCommand(StateSocketService socketService, MarkdownParserService markdownParserService) {
        this(socketService, markdownParserService, StateSocketService.createDefaultObjectMapper());
    }

    public StatusCommand() {
        this(new StateSocketService(), new MarkdownParserService(), StateSocketService.createDefaultObjectMapper());
    }

    @Override
    public void run() {
        PrintWriter out = getOut();
        WorkspaceContextResolver resolver = getWorkspaceContextResolver();
        Path repoRoot = resolver.resolveRepoRoot();

        List<WorktreeState> tasks = collectTasks(repoRoot);
        if (json) {
            printJson(out, tasks);
            return;
        }

        out.println("[dev-agent] Checking active task sockets...");
        if (tasks.isEmpty()) {
            out.println("[dev-agent] " + EMPTY_MESSAGE);
            return;
        }

        for (WorktreeState state : tasks) {
            String liveness = resolveLivenessLabel(state);
            String formattedCost = "0";
            if (nonNull(state.cost())) {
                formattedCost = state.cost().toPlainString();
            }
            out.printf(
                "[dev-agent] %s | %s (%s) | phase=%s | tokens=%d | cost=$%s | branch=%s%n",
                state.taskId(),
                state.status(),
                liveness,
                state.phase(),
                state.tokens(),
                formattedCost,
                Objects.requireNonNullElse(state.branch(), "unassigned")
            );
        }
    }

    private List<WorktreeState> collectTasks(Path repoRoot) {
        Map<String, WorktreeState> byId = new LinkedHashMap<>();
        for (WorktreeState socketState : socketService.readAllStates(repoRoot)) {
            if (nonNull(socketState.taskId())) {
                byId.put(socketState.taskId(), socketState);
            }
        }
        if (all) {
            readHistoricalMarkdownStates(repoRoot).forEach(hist -> byId.putIfAbsent(hist.taskId(), hist));
        }
        return new ArrayList<>(byId.values());
    }

    private List<WorktreeState> readHistoricalMarkdownStates(Path repoRoot) {
        Path runsDir = repoRoot.resolve(".agents").resolve("runs");
        if (Files.isSymbolicLink(repoRoot.resolve(".agents")) || !Files.isDirectory(runsDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<WorktreeState> historical = new ArrayList<>();
        try (Stream<Path> files = Files.list(runsDir)) {
            for (Path file : files
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                .sorted()
                .toList()) {
                parseJournalToState(repoRoot, file).ifPresent(historical::add);
            }
        } catch (IOException _) {
            return List.of();
        }
        return historical;
    }

    private Optional<WorktreeState> parseJournalToState(Path repoRoot, Path journalFile) {
        try {
            WorktreeLayout.validateNoSymlinks(repoRoot, journalFile);
            String content = PathGuard.readRegularFileBounded(journalFile, PathGuard.DEFAULT_MAX_TEXT_FILE_BYTES);
            ParsedMarkdown parsed = markdownParserService.parse(content);
            Map<String, List<String>> meta = parsed.metadata();
            String taskId = firstMeta(meta, "task_id");
            if (isNull(taskId) || taskId.isBlank()) {
                return Optional.empty();
            }
            TaskStatus status = parseStatus(firstMeta(meta, "status"));
            String phase = firstMeta(meta, "current_phase");
            String branch = firstMeta(meta, "branch");
            String projectId = firstMeta(meta, "project_id");
            Instant updatedAt = parseInstant(firstMeta(meta, "updated_at"));
            return Optional.of(WorktreeState.builder()
                .taskId(taskId)
                .projectId(projectId)
                .status(status)
                .phase(Objects.requireNonNullElseGet(phase, status::name))
                .cost(BigDecimal.ZERO)
                .branch(branch)
                .updatedAt(updatedAt)
                .build());
        } catch (IOException _) {
            return Optional.empty();
        }
    }

    private String firstMeta(Map<String, List<String>> meta, String key) {
        List<String> values = meta.get(key);
        if (nonNull(values) && !values.isEmpty()) {
            return values.getFirst();
        }
        return null;
    }

    private TaskStatus parseStatus(String raw) {
        if (isNull(raw) || raw.isBlank()) {
            return TaskStatus.COMPLETED;
        }
        try {
            return TaskStatus.valueOf(raw.trim());
        } catch (IllegalArgumentException _) {
            return TaskStatus.COMPLETED;
        }
    }

    private Instant parseInstant(String raw) {
        if (isNull(raw) || raw.isBlank() || "unknown".equals(raw)) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (DateTimeParseException _) {
            return null;
        }
    }

    private String resolveLivenessLabel(WorktreeState state) {
        if (state.status() == TaskStatus.QUEUED) {
            return "QUEUED";
        }
        if (nonNull(state.status()) && state.status().isTerminal()) {
            return state.status().name();
        }
        if (socketService.isProcessAlive(state)) {
            return "RUNNING pid=" + state.pid();
        }
        return "STALE";
    }

    private void printJson(PrintWriter out, List<WorktreeState> tasks) {
        if (tasks.isEmpty()) {
            out.println("{\"activeTasks\":[],\"message\":\"" + EMPTY_MESSAGE + "\"}");
            return;
        }
        StatusPayload payload = new StatusPayload(tasks, "Found " + tasks.size() + " task(s).");
        try {
            out.println(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            out.println("{\"activeTasks\":[],\"message\":\"Serialization error: " + e.getMessage() + "\"}");
        }
    }

    public boolean isAll() {
        return all;
    }

    public boolean isJson() {
        return json;
    }
}
