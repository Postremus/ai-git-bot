package org.remus.giteabot.agent.loop;

import lombok.Setter;
import org.remus.giteabot.agent.session.AgentSession;
import org.remus.giteabot.agent.validation.Workspace;
import org.remus.giteabot.prworkflow.agentreview.DiffSummary;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Mutable, per-run context shared between {@link AgentLoop} and
 * {@link AgentStrategy} implementations.
 */
public final class AgentRunContext {

    private final AgentSession session;
    private final String owner;
    private final String repo;
    private final Long issueNumber;
    private final Workspace workspace;
    @Setter
    private String baseBranch;
    @Setter
    private ToolingMode toolingMode;
    @Setter
    private DiffSummary diffSummary;
    @Setter
    private Consumer<ToolCallRecord> auditToolCallConsumer;

    public AgentRunContext(AgentSession session, String owner, String repo,
                           Long issueNumber, Workspace workspace, String baseBranch) {
        this.session = session;
        this.owner = owner;
        this.repo = repo;
        this.issueNumber = issueNumber;
        this.workspace = workspace;
        this.baseBranch = baseBranch;
    }

    public AgentSession session() { return session; }
    public String owner() { return owner; }
    public String repo() { return repo; }
    public Long issueNumber() { return issueNumber; }
    /** The workspace of this run; owned (and closed) by whoever created the context. */
    public Workspace workspace() { return workspace; }
    public Path workspaceDir() { return workspace == null ? null : workspace.dir(); }
    public String baseBranch() { return baseBranch; }
    public ToolingMode toolingMode() { return toolingMode; }
    public DiffSummary diffSummary() { return diffSummary; }
    public Consumer<ToolCallRecord> auditToolCallConsumer() { return auditToolCallConsumer; }

    public record ToolCallRecord(
            String toolName,
            String arguments,
            String resultExcerpt,
            boolean success,
            long durationMs,
            Long inputTokens,
            Long outputTokens,
            int round
    ) {}
}
