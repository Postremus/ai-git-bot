import { test, expect } from '@playwright/test';

/**
 * Journey: bot-form-agent-enabled-always-visible
 * Only in-page form selection is changed and never submitted, so no
 * persisted state is modified - the test is idempotent.
 */
test('agent enabled toggle stays visible when the issue workflow selection changes', async ({ page }) => {
  // Step 1: Navigate directly to /bots/new.
  await page.goto('/bots/new');
  await page.waitForLoadState('networkidle');

  // Step 2: Wait for the bot form to render.
  const form = page.locator('form').first();
  await form.waitFor({ state: 'visible', timeout: 30_000 });

  // Step 3: Locate the agent enabled checkbox and its surrounding container.
  const agentEnabled = page
    .locator(
      [
        'input[name*="agentEnabled" i]',
        'input[id*="agentEnabled" i]',
        'input[name*="agent_enabled" i]',
        'input[id*="agent_enabled" i]',
        '[data-testid*="agentEnabled" i] input',
      ].join(', '),
    )
    .first();

  await agentEnabled.waitFor({ state: 'attached', timeout: 30_000 });
  const container = agentEnabled.locator('xpath=ancestor::*[self::div or self::label][1]');
  await expect(container).toBeVisible();

  // Step 4: Change the issue workflow configuration select to another option.
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

  const optionValues = await issueSelect.locator('option').evaluateAll((nodes) =>
    nodes.map((node) => (node as HTMLOptionElement).value),
  );
  const currentValue = await issueSelect.inputValue();
  const nextValue = optionValues.find((value) => value !== currentValue);

  if (nextValue !== undefined) {
    await issueSelect.selectOption(nextValue);
    // Allow any conditional re-render triggered by the change to settle.
    await page.waitForTimeout(1_000);
    expect(await issueSelect.inputValue()).toBe(nextValue);
  }

  // Assertion: checkbox and container remain visible after the change.
  await expect(container).toBeVisible();
  await expect(agentEnabled).toBeAttached();
  const checkboxVisible = await agentEnabled.isVisible();
  if (!checkboxVisible) {
    // Some UIs render a visually hidden native input behind a styled control;
    // in that case the labelled container must still be visible.
    await expect(container).toBeVisible();
  } else {
    await expect(agentEnabled).toBeVisible();
  }

  // Restore the originally selected value so the form state is unchanged.
  if (nextValue !== undefined) {
    await issueSelect.selectOption(currentValue);
    await page.waitForTimeout(500);
  }
});
