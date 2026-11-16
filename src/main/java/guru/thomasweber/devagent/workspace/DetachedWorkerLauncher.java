package guru.thomasweber.devagent.workspace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;

/**
 * Launches detached background worker OS processes for asynchronous task execution,
 * redirecting stdout/stderr to the task's {@code worker.log} and discarding stdin.
 */
@ApplicationScoped
public class DetachedWorkerLauncher {

    private final StateSocketService socketService;

    @Inject
    public DetachedWorkerLauncher(StateSocketService socketService) {
        this.socketService = socketService;
    }

    public DetachedWorkerLauncher() {
        this(new StateSocketService());
    }

    public long launchWorker(Path repoRoot, String taskId, String instruction) {
        if (isNull(repoRoot)) {
            throw new IllegalArgumentException("repoRoot must not be null");
        }
        Path logFile = socketService.resolveWorkerLogFile(repoRoot, taskId);
        Path worktreeDir = logFile.getParent();
        Path workingDir = repoRoot;
        if (nonNull(worktreeDir) && Files.isDirectory(worktreeDir)) {
            workingDir = worktreeDir;
        }
        List<String> command = buildWorkerCommand(repoRoot, taskId, instruction);
        return launchCommand(command, workingDir, logFile);
    }

    public List<String> buildWorkerCommand(Path repoRoot, String taskId, String instruction) {
        String effectiveInstruction = Objects.requireNonNullElse(instruction, "");
        List<String> command = new ArrayList<>();
        String currentExecutable = ProcessHandle.current().info().command().orElse("java");
        String exeName = Path.of(currentExecutable).getFileName().toString().toLowerCase();

        if (exeName.equals("java") || exeName.equals("java.exe")) {
            command.add(currentExecutable);
            String classpath = System.getProperty("java.class.path", "");
            if (classpath.endsWith(".jar") && !classpath.contains(File.pathSeparator)) {
                command.add("-jar");
                command.add(classpath);
            } else {
                command.add("-cp");
                command.add(classpath);
                command.add(resolveJvmMainClass());
            }
        } else {
            command.add(currentExecutable);
        }

        command.add("--dir");
        command.add(repoRoot.toAbsolutePath().normalize().toString());
        command.add("worker");
        command.add("--task-id");
        command.add(taskId);
        command.add("--instruction=" + effectiveInstruction);
        return List.copyOf(command);
    }

    public long launchCommand(List<String> command, Path workingDir, Path logFile) {
        if (isNull(command) || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        try {
            if (nonNull(logFile) && nonNull(logFile.getParent())) {
                Files.createDirectories(logFile.getParent());
            }
            ProcessBuilder pb = new ProcessBuilder(command);
            if (nonNull(workingDir)) {
                pb.directory(workingDir.toFile());
            }
            if (nonNull(logFile)) {
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
                pb.redirectErrorStream(true);
            }
            Process process = pb.start();
            closeChildStdin(process);
            return process.pid();
        } catch (IOException e) {
            throw new WorkspaceException("Failed to launch detached worker process", e);
        }
    }

    private void closeChildStdin(Process process) {
        try {
            process.getOutputStream().close();
        } catch (IOException _) {
            // Child process already closed stdin or terminated
        }
    }

    private String resolveJvmMainClass() {
        String sunCmd = System.getProperty("sun.java.command", "").trim();
        if (!sunCmd.isEmpty()) {
            String firstToken = sunCmd.split("\\s+")[0];
            if (!firstToken.endsWith(".jar") && firstToken.contains(".")) {
                return firstToken;
            }
        }
        return "io.quarkus.runner.GeneratedMain";
    }
}
