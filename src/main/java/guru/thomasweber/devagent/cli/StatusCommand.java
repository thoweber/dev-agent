package guru.thomasweber.devagent.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import guru.thomasweber.devagent.cognitive.MarkdownParserService;
import guru.thomasweber.devagent.cognitive.MarkdownParserService.ParsedMarkdown;
import guru.thomasweber.devagent.model.StatusPayload;
import guru.thomasweber.devagent.model.TaskStatus;
import guru.thomasweber.devagent.model.WorktreeState;
import guru.thomasweber.devagent.workspace.StateSocketService;
import guru.thomasweber.devagent.workspace.WorkspaceContextResolver;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static java.util.Objects.isNull;

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
        this.objectMapper = !isNull(objectMapper) ? objectMapper : createDefaultObjectMapper();
    }

    public StatusCommand(StateSocketService socketService, MarkdownParserService markdownParserService) {
        this(socketService, markdownParserService, createDefaultObjectMapper());
    }

    public StatusCommand() {
        this(new StateSocketService(), new MarkdownParserService(), createDefaultObjectMapper());
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
            out.printf(
                "[dev-agent] %s | %s (%s) | phase=%s | tokens=%d | cost=$%s | branch=%s%n",
                state.taskId(),
                state.status(),
                liveness,
                state.phase(),
                state.tokens(),
                state.cost() != null ? state.cost().toPlainString() : "0",
                state.branch() != null ? state.branch() : "unassigned"
            );
        }
    }

    private List<WorktreeState> collectTasks(Path repoRoot) {
        Map<String, WorktreeState> byId = new LinkedHashMap<>();
        for (WorktreeState socketState : socketService.readAllStates(repoRoot)) {
            if (!isNull(socketState.taskId())) {
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
        if (!Files.isDirectory(runsDir)) {
            return List.of();
        }
        List<WorktreeState> historical = new ArrayList<>();
        try (Stream<Path> files = Files.list(runsDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList()) {
                parseJournalToState(file).ifPresent(historical::add);
            }
        } catch (IOException _) {
            return List.of();
        }
        return historical;
    }

    private Optional<WorktreeState> parseJournalToState(Path journalFile) {
        try {
            ParsedMarkdown parsed = markdownParserService.parse(Files.readString(journalFile));
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
                .phase(phase != null ? phase : status.name())
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
        return (!isNull(values) && !values.isEmpty()) ? values.getFirst() : null;
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
        } catch (Exception _) {
            return null;
        }
    }

    private String resolveLivenessLabel(WorktreeState state) {
        if (state.status() == TaskStatus.QUEUED) {
            return "QUEUED";
        }
        if (!isNull(state.status()) && state.status().isTerminal()) {
            return state.status().name();
        }
        return socketService.isProcessAlive(state) ? "RUNNING pid=" + state.pid() : "STALE";
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

    private static ObjectMapper createDefaultObjectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public boolean isAll() {
        return all;
    }

    public boolean isJson() {
        return json;
    }
}
