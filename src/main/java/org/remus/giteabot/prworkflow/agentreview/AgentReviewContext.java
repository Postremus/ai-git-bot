package org.remus.giteabot.prworkflow.agentreview;

import org.remus.giteabot.ai.AiClient;
import org.remus.giteabot.mcp.McpToolCatalog;
import org.remus.giteabot.repository.RepositoryConnection;
import org.remus.giteabot.systemsettings.McpConfiguration;

import java.util.Set;

public record AgentReviewContext(
        RepositoryConnection repositoryConnection,
        AiClient aiClient,
        String reviewAgentSystemPrompt,
        String botUsername,
        McpConfiguration mcpConfiguration,
        McpToolCatalog mcpToolCatalog,
        Set<String> allowedBuiltinTools,
        int contextWindowTokens
) {

    public AgentReviewContext {
        mcpToolCatalog = mcpToolCatalog != null ? mcpToolCatalog : McpToolCatalog.empty();
    }
}
