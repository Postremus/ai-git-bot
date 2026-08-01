import { test, expect } from '@playwright/test';

/**
 * Journey: issue-configuration-not-listed-under-pr-selector
 * Read-only journey.
 */
test('bot form PR selector does not offer issue-kind configurations', async ({ page }) => {
  // Step 1: Navigate directly to /bots/new.
  await page.goto('/bots/new');
  await page.waitForLoadState('networkidle');

  // Step 2: Wait for the bot form to render.
  const form = page.locator('form').first();
  await form.waitFor({ state: 'visible', timeout: 30_000 });

  // Step 3: Read all option labels of the PR workflow configuration select.
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

  await expect
    .poll(async () => (await prSelect.locator('option').count()), { timeout: 15_000 })
    .toBeGreaterThan(0);

  const optionLabels = (await prSelect.locator('option').allTextContents()).map((label) => label.trim());

  // Assertion: no ISSUE-kind configuration is offered by the PR selector.
  expect(optionLabels).not.toContain('Issue: Coding Agent');
  expect(optionLabels).not.toContain('Issue: Writer Agent');
  for (const label of optionLabels) {
    expect(label.startsWith('Issue:')).toBe(false);
  }
});
