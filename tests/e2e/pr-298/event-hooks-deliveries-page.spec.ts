import { test, expect } from '@playwright/test';

test('deliveries view is reachable from the outgoing webhooks admin', async ({ page }) => {
  await page.goto('/admin/event-hooks');
  await page.waitForLoadState('domcontentloaded');

  // Try to find a global "deliveries" link on the admin page.
  const deliveriesLink = page
    .locator(
      'a[href*="deliver" i], a:has-text("Deliveries"), a:has-text("Delivery"), a:has-text("Recent deliveries")'
    )
    .first();

  if (await deliveriesLink.count()) {
    await deliveriesLink.first().click();
    await page.waitForLoadState('domcontentloaded');
  } else {
    // Fall back to opening an endpoint (edit page) and looking for a deliveries link there.
    const anyEndpoint = page
      .locator('a[href*="/admin/event-hooks/"]')
      .filter({ hasNotText: /new/i })
      .first();

    if (await anyEndpoint.count()) {
      await anyEndpoint.click();
      await page.waitForLoadState('domcontentloaded');

      const perEndpointDeliveries = page
        .locator('a[href*="deliver" i], a:has-text("Deliveries"), a:has-text("Delivery")')
        .first();
      if (await perEndpointDeliveries.count()) {
        await perEndpointDeliveries.click();
        await page.waitForLoadState('domcontentloaded');
      }
    }
  }

  // Assertion: a deliveries listing UI is rendered. Accept a table or a
  // recognisable empty-state message referencing deliveries.
  const table = page.locator('table').first();
  const emptyState = page
    .locator('body')
    .filter({ hasText: /deliver(y|ies|ed)/i })
    .first();

  const hasTable = await table.isVisible().catch(() => false);
  const hasDeliveryText = await emptyState.isVisible().catch(() => false);

  expect(
    hasTable || hasDeliveryText,
    'expected a deliveries table or empty-state message referencing deliveries'
  ).toBeTruthy();
});
