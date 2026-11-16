package guru.thomasweber.devagent.workspace;

/**
 * Strongly-typed domain exception raised when workspace, Git worktree, or launcher operations fail.
 */
public class WorkspaceException extends RuntimeException {

    public WorkspaceException(String message) {
        super(message);
    }

    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
