import { test, expect } from '@playwright/test';

test('invalid URL is rejected on the new endpoint form', async ({ page }) => {
  await page.goto('/admin/event-hooks/new');
  await page.waitForLoadState('domcontentloaded');
  const dismissButton = page.getByRole('button', { name: 'Dismiss' });
  if (await dismissButton.count() > 0) {
    await dismissButton.click();
  }
  await page.waitForSelector('form', { timeout: 10_000 });

  const uniqueName = `e2e-invalid-${Date.now()}`;

  const nameField = page
    .locator('input[name="name"], input[name="label"], input#name, input#label')
    .first();
  await expect(nameField).toBeVisible({ timeout: 10_000 });
  await nameField.fill(uniqueName);

  const urlField = page
    .locator('input[name="url"], input[name="payload_url"], input[type="url"], input#url, input#payload_url')
    .first();
  await expect(urlField).toBeVisible();
  await urlField.fill('not-a-url');

  const eventCheckbox = page.locator('input[type="checkbox"]').first();
  await expect(eventCheckbox).toBeAttached({ timeout: 10_000 });
  const isChecked = await eventCheckbox.isChecked().catch(() => false);
  if (!isChecked) {
    await eventCheckbox.check({ force: true }).catch(async () => {
      await eventCheckbox.click({ force: true });
    });
  }

  const submit = page.locator('button[type="submit"]:has-text("Save"), input[type="submit"][value="Save"]').first();
  await expect(submit).toBeVisible();
  await submit.click();

  // Give the browser a moment to either show HTML5 validation, an error flash,
  // or bounce back to the same form.
  await page.waitForTimeout(500);
  await page.waitForLoadState('domcontentloaded');

  // Assertion: we must NOT have landed on the endpoints list. In other words,
  // we are still on the new/edit form OR a validation error indicator is visible.
  const currentUrl = page.url();
  const onFormPage = /\/admin\/event-hooks\/(new|edit|\d+)/.test(currentUrl)
    || currentUrl.endsWith('/admin/event-hooks/new');

  // Check the URL input's HTML5 validity, if it is a type=url input.
  const isFieldInvalid = await urlField
    .evaluate((el: HTMLInputElement) => {
      if (typeof el.checkValidity === 'function') return !el.checkValidity();
      return false;
    })
    .catch(() => false);

  // Look for any visible error indicator (flash, .error, .ui.error, aria-invalid).
  const errorIndicator = page
    .locator(
      '.flash-error, .flash.error, .ui.error, .ui.negative.message, [role="alert"], .error, [aria-invalid="true"]'
    )
    .first();
  const errorVisible = await errorIndicator.isVisible().catch(() => false);

  expect(
    onFormPage || isFieldInvalid || errorVisible,
    'expected to remain on the form or see a validation error for an invalid URL'
  ).toBeTruthy();

  // Sanity: the invalid endpoint should NOT appear in the listing.
  await page.goto('/admin/event-hooks');
  await page.waitForLoadState('domcontentloaded');
  await expect(page.getByText(uniqueName, { exact: false })).toHaveCount(0);
});
