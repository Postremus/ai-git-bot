import { test, expect } from '@playwright/test';

/**
 * Journey: bot-form-pr-workflow-selector-independent
 * Read-only journey - nothing is submitted, so state stays untouched.
 */
test('bot form keeps a separate PR workflow configuration selector', async ({ page }) => {
  // Step 1: Navigate directly to /bots/new.
  await page.goto('/bots/new');
  await page.waitForLoadState('networkidle');

  // Step 2: Wait for the bot form to render.
  const form = page.locator('form').first();
  await form.waitFor({ state: 'visible', timeout: 30_000 });

  // Step 3: Locate both workflow configuration selects by their form field names.
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

  const allWorkflowSelects = page.locator(
    [
      'select[name*="workflowConfiguration" i]',
      'select[id*="workflowConfiguration" i]',
      'select[name*="workflow_configuration" i]',
      'select[id*="workflow_configuration" i]',
    ].join(', '),
  );

  await issueSelect.waitFor({ state: 'visible', timeout: 30_000 });

  // The PR select is any workflow-configuration select that is not the issue one.
  const prSelect = page
    .locator(
      [
        'select[name*="workflowConfiguration" i]:not([name*="issue" i])',
        'select[id*="workflowConfiguration" i]:not([id*="issue" i])',
        'select[name*="workflow_configuration" i]:not([name*="issue" i])',
        'select[id*="workflow_configuration" i]:not([id*="issue" i])',
      ].join(', '),
    )
    .first();

  await prSelect.waitFor({ state: 'visible', timeout: 30_000 });

  // Assertion: two distinct workflow configuration selects are present.
  await expect(issueSelect).toBeVisible();
  await expect(prSelect).toBeVisible();
  await expect.poll(async () => allWorkflowSelects.count(), { timeout: 15_000 }).toBeGreaterThanOrEqual(2);

  const issueName = await issueSelect.getAttribute('name');
  const prName = await prSelect.getAttribute('name');
  expect(issueName).not.toBe(prName);
});
