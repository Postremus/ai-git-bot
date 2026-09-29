package org.remus.giteabot.agent.validation;

/**
 * A workspace could not be prepared or a git operation in it failed. The message is
 * written for humans (it names the failing step and includes git's output) because
 * callers post it into PR and issue comments. Remotes are credential-free.
 */
public class WorkspaceException extends RuntimeException {

    public WorkspaceException(String message) {
        super(message);
    }

    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
