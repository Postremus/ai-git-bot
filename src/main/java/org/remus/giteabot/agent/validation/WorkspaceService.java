package org.remus.giteabot.agent.validation;

import lombok.extern.slf4j.Slf4j;
import org.remus.giteabot.repository.RepositoryApiClient;
import org.remus.giteabot.repository.model.PullRequestHead;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Opens temporary git workspaces for the AI agents.
 * <p>
 * Every {@code open…} method returns a {@link Workspace} owned by the caller, who closes
 * it (try-with-resources) to delete it. Failures throw {@link WorkspaceException}.
 * File changes are made directly in {@link Workspace#dir()} via
 * {@link org.remus.giteabot.agent.validation.ToolExecutionService}.
 */
@Slf4j
@Service
public class WorkspaceService {

    private final WorkspaceDirectories directories;

    /** Creates a service that places workspaces under the system temporary directory. */
    public WorkspaceService() {
        this((String) null);
    }

    /** Creates a service that places private workspace parents under the configured directory. */
    @Autowired
    public WorkspaceService(@Value("${giteabot.workspaces.dir:#{null}}") String configuredDir) {
        this(new WorkspaceDirectories(configuredDir == null || configuredDir.isBlank()
                ? null : Path.of(configuredDir)));
    }

    WorkspaceService(WorkspaceDirectories directories) {
        this.directories = directories;
    }

    /**
     * Opens a shallow clone of {@code branch}. When that clone fails and {@code prNumber}
     * is non-null, clones the default branch and checks out {@code refs/pull/<prNumber>/head}
     * as {@code branch} instead (GitHub/Gitea fork-safe ref).
     *
     * @throws WorkspaceException when the checkout cannot be resolved or cloned; no
     *                            directory is left behind
     */
    public Workspace openWorkspace(RepositoryApiClient repositoryClient,
                                   String owner, String repo, String branch, Long prNumber) {
        RepositoryCheckout checkout = resolveCheckout(repositoryClient, owner, repo);
        Workspace workspace = newWorkspace(checkout);
        CommandResult clone;
        try {
            log.info("Cloning repository to {} for workspace", workspace.dir());
            clone = cloneInto(workspace, "--branch", branch);
        } catch (RuntimeException e) {
            workspace.close();
            throw e;
        }
        if (clone.success()) {
            return workspace;
        }
        workspace.close();
        if (prNumber == null) {
            log.error("Failed to clone repository: {}", clone.output());
            throw new WorkspaceException("Failed to clone repository: " + clone.output());
        }

        log.info("Branch clone failed, falling back to PR head ref for PR #{}: {}", prNumber, clone.output());
        Workspace fallback = newWorkspace(checkout);
        try {
            CommandResult defaultClone = cloneInto(fallback);
            if (!defaultClone.success()) {
                throw new WorkspaceException("Failed to clone repository (branch: " + clone.output()
                        + "; default branch: " + defaultClone.output() + ")");
            }
            CommandResult fetch = fallback.remoteGit(60, "fetch", "origin", "refs/pull/" + prNumber + "/head");
            if (!fetch.success()) {
                throw new WorkspaceException("Failed to fetch PR head ref for PR #" + prNumber + ": "
                        + fetch.output());
            }
            CommandResult checkoutHead = fallback.git(15, "checkout", "-B", branch, "FETCH_HEAD");
            if (!checkoutHead.success()) {
                throw new WorkspaceException("Failed to checkout FETCH_HEAD for PR #" + prNumber + ": "
                        + checkoutHead.output());
            }
            return fallback;
        } catch (RuntimeException e) {
            log.error("PR head fallback failed for PR #{}: {}", prNumber, e.getMessage());
            fallback.close();
            throw e;
        }
    }

    /**
     * Opens a workspace that may be pushed back to an existing PR branch. Providers that
     * require authoritative head resolution are cloned from the source repository and fail
     * closed; other providers keep the target-repository and PR-ref fallback.
     */
    public Workspace openWritablePullRequestWorkspace(RepositoryApiClient repositoryClient,
                                                      String owner, String repo,
                                                      String branch, Long prNumber) {
        if (!repositoryClient.requiresAuthoritativePullRequestHead()) {
            return openWorkspace(repositoryClient, owner, repo, branch, prNumber);
        }
        final PullRequestHead head;
        try {
            head = repositoryClient.getPullRequestHead(owner, repo, prNumber, branch);
            if (head == null || head.owner() == null || head.owner().isBlank()
                    || head.repository() == null || head.repository().isBlank()
                    || head.branch() == null || head.branch().isBlank()) {
                throw new IllegalStateException("Repository client returned an incomplete pull-request head");
            }
        } catch (RuntimeException e) {
            String message = messageOf(e);
            log.error("Failed to resolve writable pull-request head for {}/{}#{}: {}",
                    owner, repo, prNumber, message, e);
            throw new WorkspaceException("Failed to resolve writable pull-request head: " + message, e);
        }
        return openWorkspace(repositoryClient, head.owner(), head.repository(), head.branch(), null);
    }

    /**
     * Opens an empty repository ({@code git init}) whose remote commands authenticate
     * against the client's repository, for callers that fetch specific objects.
     */
    Workspace openEmptyWorkspace(RepositoryApiClient repositoryClient, String owner, String repo) {
        Workspace workspace = newWorkspace(resolveCheckout(repositoryClient, owner, repo));
        try {
            CommandResult init = workspace.gitAt(workspace.root(), 15,
                    "init", "-q", WorkspaceDirectories.REPOSITORY_DIRECTORY_NAME);
            if (!init.success()) {
                throw new WorkspaceException("git init failed: " + init.output());
            }
            return workspace;
        } catch (RuntimeException e) {
            workspace.close();
            throw e;
        }
    }

    private RepositoryCheckout resolveCheckout(RepositoryApiClient repositoryClient, String owner, String repo) {
        try {
            return RepositoryCheckout.resolve(repositoryClient, owner, repo);
        } catch (RuntimeException e) {
            String message = messageOf(e);
            log.error("Failed to resolve repository checkout for {}/{}: {}", owner, repo, message, e);
            throw new WorkspaceException("Failed to resolve repository checkout: " + message, e);
        }
    }

    private Workspace newWorkspace(RepositoryCheckout checkout) {
        try {
            return new Workspace(directories.createRoot(), checkout, directories);
        } catch (IOException e) {
            log.error("Failed to prepare workspace: {}", e.getMessage());
            throw new WorkspaceException("Failed to prepare workspace: " + e.getMessage(), e);
        }
    }

    private static CommandResult cloneInto(Workspace workspace, String... options) {
        List<String> args = new ArrayList<>(List.of("clone", "--depth", "1"));
        args.addAll(List.of(options));
        args.add(workspace.checkout().remote());
        args.add(WorkspaceDirectories.REPOSITORY_DIRECTORY_NAME);
        return workspace.remoteGitAt(workspace.root(), 60, args.toArray(String[]::new));
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Returns whether an authoritative PR head belongs to another repository.
     * Providers without authoritative head resolution retain their existing
     * offer-as-PR behaviour and return {@code false}.
     */
    public boolean isAuthoritativePullRequestFromFork(RepositoryApiClient repositoryClient,
                                                      String owner, String repo,
                                                      String branch, Long prNumber) {
        if (!repositoryClient.requiresAuthoritativePullRequestHead()) {
            return false;
        }
        PullRequestHead head = repositoryClient.getPullRequestHead(owner, repo, prNumber, branch);
        return !owner.equalsIgnoreCase(head.owner()) || !repo.equalsIgnoreCase(head.repository());
    }
}
