import { test, expect } from '@playwright/test';

test('existing endpoint can be opened for editing and pre-fills the URL', async ({ page }) => {
  const uniqueName = `e2e-edit-${Date.now()}`;
  const url = 'https://example.com/edit-hook';

  // --- Create an endpoint first. ---
  await page.goto('/admin/event-hooks/new');
  await page.waitForLoadState('domcontentloaded');
  await page.waitForSelector('form', { timeout: 10_000 });

  const nameField = page
    .locator('input[name="name"], input[name="label"], input#name, input#label')
    .first();
  await expect(nameField).toBeVisible({ timeout: 10_000 });
  await nameField.fill(uniqueName);

  const urlField = page
    .locator('input[name="url"], input[name="payload_url"], input[type="url"], input#url, input#payload_url')
    .first();
  await expect(urlField).toBeVisible();
  await urlField.fill(url);

  const eventCheckbox = page.locator('input[type="checkbox"]').first();
  const isChecked = await eventCheckbox.isChecked().catch(() => false);
  if (!isChecked) {
    await eventCheckbox.check({ force: true }).catch(async () => {
      await eventCheckbox.click({ force: true });
    });
  }

  const submit = page.locator('button[type="submit"]:has-text("Save"), input[type="submit"][value="Save"]').first();
  await submit.click();
  await page.waitForLoadState('domcontentloaded');

  // --- Return to the listing. ---
  await page.goto('/admin/event-hooks');
  await page.waitForLoadState('domcontentloaded');

  // If we did not land directly on an edit form, look for an explicit "Edit" action.
  const editButton = page
      .locator('tr')
      .filter({ hasText: uniqueName })
      .getByRole('link', { name: 'Edit', exact: false });

  await editButton.click();

  if (await editButton.count()) {
    const editable = page.locator('input[name="url"], input[name="payload_url"], input[type="url"]').first();
    if (!(await editable.isVisible().catch(() => false))) {
      await editButton.click().catch(() => undefined);
      await page.waitForLoadState('domcontentloaded');
    }
  }

  // Assert the URL field pre-fills the previously entered value.
  const editUrlField = page
    .locator('input[name="url"], input[name="payload_url"], input[type="url"], input#url, input#payload_url')
    .first();
  await expect(editUrlField).toBeVisible({ timeout: 10_000 });
  await expect(editUrlField).toHaveValue(url);

  // --- Cleanup ---
  try {
    const deleteBtn = page
      .locator(
        'button:has-text("Delete"), a:has-text("Delete"), button:has-text("Remove"), a:has-text("Remove")'
      )
      .first();
    if (await deleteBtn.count()) {
      page.once('dialog', (d) => d.accept().catch(() => undefined));
      await deleteBtn.click({ force: true }).catch(() => undefined);
      const confirm = page
        .locator('button:has-text("Delete"), button:has-text("Yes"), button:has-text("Confirm")')
        .last();
      if (await confirm.count()) {
        await confirm.click({ force: true }).catch(() => undefined);
      }
      await page.waitForLoadState('domcontentloaded').catch(() => undefined);
    }
  } catch {
    // best-effort cleanup
  }
});
