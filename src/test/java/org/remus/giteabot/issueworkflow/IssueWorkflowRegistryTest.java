package org.remus.giteabot.issueworkflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IssueWorkflowRegistryTest {

    private static IssueWorkflow stub(String key) {
        return new IssueWorkflow() {
            @Override public String key() { return key; }
            @Override public String displayName() { return key; }
            @Override public void onIssueAssigned(IssueWorkflowContext context) { }
            @Override public void onIssueComment(IssueWorkflowContext context) { }
        };
    }

    @Test
    void findAndRequire_returnRegisteredWorkflow() {
        IssueWorkflowRegistry registry = new IssueWorkflowRegistry(List.of(stub("issue-a")));

        assertTrue(registry.find("issue-a").isPresent());
        assertEquals("issue-a", registry.require("issue-a").key());
        assertEquals(1, registry.all().size());
    }

    @Test
    void require_unknownKey_throws() {
        IssueWorkflowRegistry registry = new IssueWorkflowRegistry(List.of(stub("issue-a")));

        assertThrows(IllegalArgumentException.class, () -> registry.require("issue-missing"));
        assertTrue(registry.find("issue-missing").isEmpty());
        assertTrue(registry.find(null).isEmpty());
    }

    @Test
    void duplicateKey_rejectedOnStartup() {
        assertThrows(IllegalStateException.class,
                () -> new IssueWorkflowRegistry(List.of(stub("issue-a"), stub("issue-a"))));
    }

    @Test
    void blankKey_rejectedOnStartup() {
        assertThrows(IllegalStateException.class,
                () -> new IssueWorkflowRegistry(List.of(stub(" "))));
    }

    @Test
    void nonLowerCaseKey_rejectedOnStartup() {
        assertThrows(IllegalStateException.class,
                () -> new IssueWorkflowRegistry(List.of(stub("Issue-A"))));
    }
}
