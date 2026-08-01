import { test, expect } from '@playwright/test';

/**
 * Journey: system-settings-issue-section-entry-point
 * Read-only journey.
 */
test('system settings exposes an entry point to issue-assigned workflow configurations', async ({ page }) => {
  // Step 1: Navigate directly to /system-settings.
  await page.goto('/system-settings');
  await page.waitForLoadState('networkidle');

  // Step 2: Wait for the settings sections to render.
  await page.locator('main, body').first().waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForTimeout(1_000);

  // Step 3: Query the DOM for anchors pointing at the issue workflow configurations route.
  const entryPoints = page.locator('a[href*="/system-settings/issue-workflow-configurations"]');

  // Assertion: at least one such anchor is present.
  await expect.poll(async () => entryPoints.count(), { timeout: 20_000 }).toBeGreaterThan(0);
});
