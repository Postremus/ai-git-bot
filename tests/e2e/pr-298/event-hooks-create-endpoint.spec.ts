import { test, expect } from '@playwright/test';

test('create an outgoing webhook endpoint and see it in the list', async ({ page }) => {
  const uniqueName = `e2e-hook-${Date.now()}-${Math.floor(Math.random() * 10_000)}`;

  await page.goto('/admin/event-hooks/new');
  await page.waitForLoadState('domcontentloaded');
  const dismissButton = page.getByRole('button', { name: 'Dismiss' });
  if (await dismissButton.count() > 0) {
    await dismissButton.click();
  }
  await page.waitForSelector('form', { timeout: 10_000 });

  // Fill the name field. Different builds label it "name" or "label" or similar.
  const nameField = page
    .locator('input[name="name"], input[name="label"], input#name, input#label')
    .first();
  await expect(nameField).toBeVisible({ timeout: 10_000 });
  await nameField.fill(uniqueName);

  // Fill the URL field.
  const urlField = page
    .locator('input[name="url"], input[name="payload_url"], input[type="url"], input#url, input#payload_url')
    .first();
  await expect(urlField).toBeVisible();
  await urlField.fill('https://example.com/hook');

  // Check the first available event type checkbox.
  const eventCheckbox = page.locator('input[type="checkbox"]').first();
  await expect(eventCheckbox).toBeAttached({ timeout: 10_000 });
  const isChecked = await eventCheckbox.isChecked().catch(() => false);
  if (!isChecked) {
    await eventCheckbox.check({ force: true }).catch(async () => {
      await eventCheckbox.click({ force: true });
    });
  }
// Submit the form.
  const submit = page.locator('button[type="submit"]:has-text("Save"), input[type="submit"][value="Save"]').first();
  await expect(submit).toBeVisible();

  await Promise.all([
    page.waitForLoadState('networkidle').catch(() => undefined),
    submit.click(),
  ]);

  // Ensure we can see the endpoints list containing the new endpoint name.
  await page.waitForLoadState('domcontentloaded');

  const listing = page.getByText(uniqueName, { exact: false }).first();
  await expect(listing).toBeVisible({ timeout: 10_000 });

  // --- Cleanup: try to delete the created endpoint to keep state idempotent. ---
  try {
    // Try to open the edit page for the created endpoint by clicking its row.
    const editLink = page.locator(`a:has-text("${uniqueName}")`).first();
    if (await editLink.count()) {
      await editLink.click();
      await page.waitForLoadState('domcontentloaded');
      const deleteBtn = page
        .locator(
          'button:has-text("Delete"), a:has-text("Delete"), button:has-text("Remove"), a:has-text("Remove")'
        )
        .first();
      if (await deleteBtn.count()) {
        page.once('dialog', (d) => d.accept().catch(() => undefined));
        await deleteBtn.click({ force: true }).catch(() => undefined);
        // Confirm dialog if a modal confirmation appears.
        const confirm = page
          .locator('button:has-text("Delete"), button:has-text("Yes"), button:has-text("Confirm")')
          .last();
        if (await confirm.count()) {
          await confirm.click({ force: true }).catch(() => undefined);
        }
        await page.waitForLoadState('domcontentloaded').catch(() => undefined);
      }
    }
  } catch {
    // Cleanup is best-effort; do not fail the test on cleanup issues.
  }
});
