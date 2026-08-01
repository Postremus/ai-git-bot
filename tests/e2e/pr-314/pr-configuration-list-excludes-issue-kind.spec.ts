import { test, expect } from '@playwright/test';

/**
 * Journey: pr-configuration-list-excludes-issue-kind
 * Read-only journey.
 */
test('PR workflow configuration list only shows PR-kind configurations', async ({ page }) => {
  // Step 1: Navigate directly to the PR workflow configurations route.
  await page.goto('/system-settings/workflow-configurations');
  await page.waitForLoadState('networkidle');

  // Step 2: Wait for the configuration table/list markup to render.
  const listMarkup = page.locator('table, ul, [role="table"], [role="list"], main').first();
  await listMarkup.waitFor({ state: 'visible', timeout: 30_000 });

  // Step 3: Read the rendered configuration names.
  await expect
    .poll(async () => await page.locator('body').innerText(), { timeout: 20_000 })
    .toContain('No PR workflows');

  const bodyText = await page.locator('body').innerText();

  // Assertion: no ISSUE-kind configuration leaks into the PR list.
  expect(bodyText).not.toContain('Issue: Coding Agent');
  expect(bodyText).not.toContain('Issue: Writer Agent');
});
