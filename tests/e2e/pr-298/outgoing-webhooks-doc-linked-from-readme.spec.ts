import { test, expect } from '@playwright/test';

test('outgoing webhooks admin page exposes documentation/help link', async ({ page }) => {
  await page.goto('/admin/event-hooks');
  await page.waitForLoadState('domcontentloaded');

  // Search for a documentation/help link related to outgoing webhooks.
  // Match by href pointing at OUTGOING_WEBHOOKS docs, or by human-readable link text.
  const docLink = page
    .locator(
      'a[href*="OUTGOING_WEBHOOKS" i], ' +
      'a[href*="outgoing-webhooks" i], ' +
      'a[href*="outgoing_webhooks" i], ' +
      'a[href*="docs" i][href*="webhook" i], ' +
      'a[href*="/help" i][href*="webhook" i], ' +
      'a:has-text("Documentation"), ' +
      'a:has-text("Help"), ' +
      'a:has-text("Learn more"), ' +
      'a:has-text("Docs")'
    )
    .first();

  await expect(docLink).toBeAttached({ timeout: 10_000 });

  // Sanity: make sure it has a resolvable href (not just an empty anchor).
  const href = await docLink.getAttribute('href');
  expect(href, 'documentation link should have an href').toBeTruthy();
  expect(href!.length).toBeGreaterThan(0);
});
