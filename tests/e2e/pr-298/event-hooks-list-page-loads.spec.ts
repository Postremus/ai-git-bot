import { test, expect } from '@playwright/test';

test('outgoing webhooks admin list page loads', async ({ page }) => {
  const response = await page.goto('/admin/event-hooks');
  expect(response, 'navigation response must exist').not.toBeNull();
  expect(response!.status(), 'page responds with a successful HTTP status').toBeLessThan(400);

  // Wait for the DOM to settle before asserting on headings.
  await page.waitForLoadState('domcontentloaded');

  // The admin outgoing webhooks page exposes a heading. Different Gitea versions
  // use slightly different wording, so accept any heading that mentions webhook
  // (case-insensitive), including the "Outgoing webhooks" / "Webhook Endpoints" variants.
  const heading = page.locator('h1, h2, h3, h4').filter({ hasText: /webhook|event\s*hook/i }).first();
  await expect(heading).toBeVisible({ timeout: 10_000 });
});
