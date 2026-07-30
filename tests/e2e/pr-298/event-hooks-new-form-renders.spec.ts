import { test, expect } from '@playwright/test';

test('new outgoing webhook form renders with a URL input', async ({ page }) => {
  const response = await page.goto('/admin/event-hooks/new');
  expect(response, 'navigation response must exist').not.toBeNull();
  expect(response!.status()).toBeLessThan(400);

  await page.waitForLoadState('domcontentloaded');
  const dismissButton = page.getByRole('button', { name: 'Dismiss' });
  if (await dismissButton.count() > 0) {
    await dismissButton.click();
  }

  // Look for a URL input for the webhook endpoint. Match common attribute names
  // used by webhook creation forms: name="url" / name="payload_url" / type="url".
  const urlInput = page
    .locator('input[name="url"], input[name="payload_url"], input[type="url"], input#url, input#payload_url')
    .first();

  await expect(urlInput).toBeVisible({ timeout: 10_000 });
});
