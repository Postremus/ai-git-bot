import { test, expect } from '@playwright/test';

test('outgoing webhooks is reachable from the system settings area', async ({ page }) => {
  // Try common Gitea admin/system settings entry points until one loads.
  const candidateSettingsPaths = ['/system-settings'];

  let settingsLoaded = false;
  for (const path of candidateSettingsPaths) {
    const resp = await page.goto(path).catch(() => null);
    if (resp && resp.status() < 400) {
      settingsLoaded = true;
      break;
    }
  }

  expect(settingsLoaded, 'a system settings page should be reachable').toBeTruthy();
  await page.waitForLoadState('domcontentloaded');

  // Look for a link to outgoing webhooks. Match by href or link text.
  const link = page
    .locator(
      'a[href*="/admin/event-hooks"], a:has-text("Outgoing webhooks"), a:has-text("Outgoing Webhooks"), a:has-text("Event Hooks"), a:has-text("Webhooks")'
    )
    .first();

  await expect(link).toBeVisible({ timeout: 10_000 });

  await Promise.all([
    page.waitForURL(/\/admin\/event-hooks(\/|$|\?)/, { timeout: 10_000 }),
    link.click(),
  ]);

  expect(page.url()).toMatch(/\/admin\/event-hooks(\/|$|\?)/);
});
