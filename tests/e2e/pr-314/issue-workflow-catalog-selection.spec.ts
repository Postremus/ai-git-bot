import { test, expect } from '@playwright/test';

/**
 * Journey: issue-workflow-catalog-selection
 * Read-only journey - the workflow screen is only inspected, never saved.
 */
test('issue configuration workflow screen offers the issue workflow catalog', async ({ page }) => {
  // Step 1: Navigate directly to the issue workflow configurations list.
  await page.goto('/system-settings');
  await page.waitForLoadState('networkidle');
  const dismissButton = page.getByRole('button', { name: /dismiss/i }).first();
  if (await dismissButton.isVisible().catch(() => false)) {
    await dismissButton.click();
    await expect(dismissButton).toHaveCount(0, { timeout: 5000 }).catch(() => {});
  }

  const codingAgentRow = page
    .locator('tr, li, [role="row"]')
    .filter({ hasText: 'Issue: Coding Agent' })
    .first();
  await codingAgentRow.waitFor({ state: 'visible', timeout: 30_000 });

  // Step 2: Follow the row action href leading to the workflows route.
  const workflowsLink = codingAgentRow.locator('a[href*="workflow"]').first();
  await workflowsLink.waitFor({ state: 'visible', timeout: 30_000 });

  const href = await workflowsLink.getAttribute('href');
  expect(href, 'the configuration row must expose a workflows route').toBeTruthy();

  await workflowsLink.click();
  await page.waitForLoadState('networkidle');

  // Step 3: Wait for the workflow selection rows to render.
  await page.locator('main, body').first().waitFor({ state: 'visible', timeout: 30_000 });
  await expect
    .poll(async () => await page.locator('body').innerText(), { timeout: 20_000 })
    .toContain('Coding Agent');

  const bodyText = await page.locator('body').innerText();

  // Assertion: built-in issue workflows are listed.
  expect(bodyText).toContain('Coding Agent');
  expect(bodyText).toContain('Writer Agent');

  // Assertion: no PR-only workflow entries are offered here.
  expect(bodyText).not.toContain('No PR workflows');
});
