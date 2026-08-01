import { test, expect } from '@playwright/test';

/**
 * Journey: issue-workflow-configurations-list
 * Read-only journey.
 */
test('issue-assigned workflow configurations list shows the seeded configurations', async ({ page }) => {
  // Step 1: Navigate directly to the issue workflow configurations route.
  await page.goto('/system-settings');
  await page.waitForLoadState('networkidle');
  const dismissButton = page.getByRole('button', { name: /dismiss/i }).first();
  if (await dismissButton.isVisible().catch(() => false)) {
    await dismissButton.click();
    await expect(dismissButton).toHaveCount(0, { timeout: 5000 }).catch(() => {});
  }
  // Step 2: Wait for the configuration table/list markup to render.
  const listMarkup = page.locator('table, ul, [role="table"], [role="list"], main').first();
  await listMarkup.waitFor({ state: 'visible', timeout: 30_000 });

  // Assertion: both seeded configurations are listed.
  await expect
    .poll(async () => (await page.locator('body').innerText()), { timeout: 20_000 })
    .toContain('Issue: Coding Agent');

  await expect
    .poll(async () => (await page.locator('body').innerText()), { timeout: 20_000 })
    .toContain('Issue: Writer Agent');
});
