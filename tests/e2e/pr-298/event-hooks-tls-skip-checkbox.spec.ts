import { test, expect } from '@playwright/test';

test('skip TLS verification checkbox is present and unchecked by default', async ({ page }) => {
  await page.goto('/admin/event-hooks/new');
  await page.waitForLoadState('domcontentloaded');
  const dismissButton = page.getByRole('button', { name: 'Dismiss' });
  if (await dismissButton.count() > 0) {
    await dismissButton.click();
  }
  await page.waitForSelector('form', { timeout: 10_000 });

  // Look for a "skip TLS verification" style checkbox by any of the common
  // attribute/label patterns used across Gitea/Forgejo builds.
  const tlsCheckbox = page
    .locator(
      'input[type="checkbox"][name*="ssl" i], ' +
      'input[type="checkbox"][name*="tls" i], ' +
      'input[type="checkbox"][name*="insecure" i], ' +
      'input[type="checkbox"][name*="skip" i], ' +
      'input[type="checkbox"][id*="ssl" i], ' +
      'input[type="checkbox"][id*="tls" i], ' +
      'input[type="checkbox"][id*="insecure" i], ' +
      'input[type="checkbox"][id*="skip" i]'
    )
    .first();

  await expect(tlsCheckbox).toBeAttached({ timeout: 10_000 });
  await expect(tlsCheckbox).not.toBeChecked();
});
