import { test, expect } from '@playwright/test';

/**
 * Journey: bot-form-issue-workflow-selector
 * Read-only journey - the form is never submitted, so no state is changed.
 */
test('bot form offers an issue-assigned workflow configuration selector', async ({ page }) => {
  // Step 1: Navigate directly to /bots/new.
  await page.goto('/bots/new');
  await page.waitForLoadState('networkidle');

  // Step 2: Wait for the bot form to render.
  const form = page.locator('form').first();
  await form.waitFor({ state: 'visible', timeout: 30_000 });

  // Step 3: Locate the select bound to the issue workflow configuration field.
  const issueSelect = page
    .locator(
      [
        'select[name*="issueWorkflowConfiguration" i]',
        'select[id*="issueWorkflowConfiguration" i]',
        'select[name*="issue_workflow_configuration" i]',
        'select[id*="issue_workflow_configuration" i]',
      ].join(', '),
    )
    .first();

  await issueSelect.waitFor({ state: 'visible', timeout: 30_000 });
  await expect(issueSelect).toBeVisible();

  // Assertion: it offers the seeded 'Issue: Coding Agent' configuration.
  await expect
    .poll(async () => (await issueSelect.locator('option').allTextContents()).join('|'), {
      timeout: 15_000,
    })
    .toContain('Issue: Coding Agent');
});
