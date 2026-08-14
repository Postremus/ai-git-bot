package org.remus.giteabot.issueworkflow.triage;

import lombok.RequiredArgsConstructor;
import org.remus.giteabot.issueworkflow.IssueWorkflow;
import org.remus.giteabot.issueworkflow.IssueWorkflowContext;
import org.remus.giteabot.prworkflow.WorkflowParamField;
import org.remus.giteabot.prworkflow.WorkflowParamsSchema;
import org.springframework.stereotype.Component;

/**
 * Built-in issue-assigned workflow for initial issue triage and routing:
 * reads the issue, chooses exactly one assignee from the configured account
 * names (people, bots, or {@code none}) using the admin-editable system
 * prompt, posts the routing reason as an issue comment, and performs the
 * assignment through the provider API. It never implements the issue itself.
 *
 * <p>The workflow runs on issue-created events (when the bot's
 * {@code runOnIssueCreation} setting is enabled — the creation is treated as
 * a virtual assignment to the bot) and on issue-assigned events, both routed
 * here by the {@code IssueWorkflowOrchestrator}.</p>
 *
 * <p>Follow-up comments are intentionally ignored: triage is a one-shot
 * routing decision, so {@link #onIssueComment} is a conscious no-op per the
 * {@link IssueWorkflow} contract.</p>
 */
@Component
@RequiredArgsConstructor
public class TriageIssueWorkflow implements IssueWorkflow {

    public static final String KEY = "issue-triage";

    /**
     * Default routing prompt for new configurations. Admins MUST review it
     * before use: the role descriptions, account names, and bot names are
     * examples, and every listed account must exist and be assignable in the
     * connected Git provider. The canonical clarification-bot name is
     * {@code issue_hemingway}.
     */
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are an issue triage lead for an engineering team. Your job is to read incoming issues and assign each one correctly.

            Available resources:

            People:
            - Alice — Frontend specialist (UI, CSS, HTML, JavaScript/TypeScript, framework components, client-side logic, browser APIs, state management, theming, accessibility)
            - Bob — Backend specialist (server logic, APIs, authentication, business rules, integrations, microservices, middleware)
            - David — Database expert (schema design, migrations, queries, indexing, ORMs, data modeling, performance tuning)
            - Josh — CI/CD specialist (pipelines, build systems, Docker, CI runners, deployment, testing infrastructure, monitoring)

            Bots:
            - kernel_thompson — Takes over small, well-scoped implementation items and handles them end-to-end
            - issue_hemingway — Writing bot that clarifies, structures, and expands vague or underspecified tickets
            - none — Use when the issue is unclear, contradictory, or you genuinely cannot determine what it is about

            Your job:

            1. Read the issue carefully.
            2. Determine the primary domain of the work.
            3. Assign one of the following outcomes:
               a) Alice, Bob, David, or Josh — when a human should own the work
               b) kernel_thompson — when the issue is small, well-defined, and implementable without human judgment (e.g., "change button color", "add a field to this form", "update a label", "fix a single-line bug")
               c) issue_hemingway — when the issue is too vague, lacks acceptance criteria, or needs clarification before it can be assigned
               d) none — when the issue is unclear, contradictory, or you genuinely cannot determine what it is about

            Rules:

            - One issue, one assignment.
            - If an issue spans domains, assign to the PRIMARY domain — the one that contains the most work or is the gating dependency.
            - If an issue needs human judgment, architecture decisions, or trade-off analysis, assign to a human (not claude-bot).
            - If an issue has no reproduction steps, no expected behavior, or no success criteria, send to issue_hemingway.
            - claude-bot is only for small, unambiguous tasks — never for features, refactors, or anything requiring design decisions.
            - If an issue mentions frontend AND backend, assign to Bob unless the frontend part is the majority.
            - If an issue mentions schema changes, migrations, or data work, assign to David.
            - If an issue is about build, deploy, test, or pipeline changes, assign to Josh.
            - Never assign to a person and a bot simultaneously.
            - If you truly cannot figure out what the issue is about, assign none.
            """;

    /**
     * Default allowed assignees for new configurations — the account names
     * listed in {@link #DEFAULT_SYSTEM_PROMPT}. {@code none} is a reserved
     * built-in value and always allowed; it is never part of this list.
     */
    public static final String DEFAULT_ASSIGNEES = "Alice,Bob,David,Josh,kernel_thompson,issue_hemingway";

    private final IssueTriageService triageService;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String displayName() {
        return "Issue Triage";
    }

    @Override
    public String description() {
        return "Routes a new or newly assigned issue to the right assignee (person, bot, or none)"
                + " using an editable triage prompt, posts the routing reason as a comment, and"
                + " performs the assignment. Never implements the issue itself.";
    }

    @Override
    public WorkflowParamsSchema paramsSchema() {
        return WorkflowParamsSchema.of(
                new WorkflowParamField(TriageParam.SYSTEM_PROMPT,
                        "System prompt",
                        WorkflowParamField.ParamType.TEXT, true,
                        DEFAULT_SYSTEM_PROMPT,
                        "Routing instructions for the triage model. MUST be reviewed before use: check"
                                + " role descriptions, account names and bot names, and make sure every"
                                + " listed account exists and is assignable in the connected Git provider."
                                + " Keep the account names in sync with the allowed assignees below."),
                new WorkflowParamField(TriageParam.ASSIGNEES,
                        "Allowed assignees",
                        WorkflowParamField.ParamType.STRING, true,
                        DEFAULT_ASSIGNEES,
                        "Comma-separated account names the workflow may assign issues to. 'none' is"
                                + " always allowed and means: post the reason but leave the issue"
                                + " unassigned."));
    }

    @Override
    public void onIssueAssigned(IssueWorkflowContext context) {
        triageService.triage(context.bot(), context.payload(), context.params());
    }

    /**
     * Conscious no-op: triage is a one-shot routing decision taken when the
     * issue is opened or assigned. Follow-up conversation on the issue
     * belongs to the coding/writer workflows.
     */
    @Override
    public void onIssueComment(IssueWorkflowContext context) {
        // intentional no-op
    }
}
