import { test, expect } from '@playwright/test';

/**
 * Journey: create-issue-workflow-configuration
 * This journey creates data, so the created configuration is deleted again
 * at the end to keep the environment idempotent for subsequent runs.
 */
const LIST_URL = '/system-settings/issue-workflow-configurations';

test('admin can create a new issue-assigned workflow configuration', async ({ page }) => {
  const uniqueName = `E2E Issue Config ${Date.now()}`;

  // Step 1: Navigate directly to the issue workflow configurations list.
  await page.goto(LIST_URL);
  await page.waitForLoadState('networkidle');
  await page.locator('main, body').first().waitFor({ state: 'visible', timeout: 30_000 });

  // Step 2: Open the create form via the route referenced by the create control.
  const createLink = page.locator(`a[href*="${LIST_URL}/new"], a[href$="/new"]`).first();
  if (await createLink.count() > 0) {
    await createLink.waitFor({ state: 'visible', timeout: 30_000 });
    await createLink.click();
  } else {
    await page.goto(`${LIST_URL}/new`);
  }
  await page.waitForLoadState('networkidle');

  const form = page.locator('form').first();
  await form.waitFor({ state: 'visible', timeout: 30_000 });

  // Step 3: Fill the configuration name input with a unique generated name.
  const nameInput = form
    .locator(
      [
        'input[name*="name" i]',
        'input[id*="name" i]',
        'input[type="text"]',
      ].join(', '),
    )
    .first();
  await nameInput.waitFor({ state: 'visible', timeout: 30_000 });
  await nameInput.fill(uniqueName);

  // Step 4: Submit the form.
  const submit = form
    .locator('button[type="submit"], input[type="submit"], button:has-text("Create"), button:has-text("Save")')
    .first();
  await submit.waitFor({ state: 'visible', timeout: 30_000 });
  await submit.click();

  // Wait for the navigation back to the list to settle.
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);

  if (!page.url().includes(LIST_URL) || page.url().includes('/new')) {
    await page.goto(LIST_URL);
    await page.waitForLoadState('networkidle');
  }

  // Assertion: the list now contains the newly created configuration name.
  await expect
    .poll(async () => await page.locator('body').innerText(), { timeout: 20_000 })
    .toContain(uniqueName);

  // Cleanup: remove the created configuration so the state is restored.
  await page.goto(LIST_URL);
  await page.waitForLoadState('networkidle');

  const createdRow = page.locator('tr, li, [role="row"]').filter({ hasText: uniqueName }).first();
  if (await createdRow.count() > 0) {
    const deleteControl = createdRow
      .locator('button:has-text("Delete"), button:has-text("Remove"), a:has-text("Delete")')
      .first();
    if (await deleteControl.count() > 0) {
      page.once('dialog', (dialog) => dialog.accept().catch(() => undefined));
      await deleteControl.click();
      await page.waitForTimeout(1_000);

      // A confirmation dialog rendered in the DOM may still need confirming.
      const confirm = page
        .locator('[role="dialog"] button:has-text("Delete"), [role="dialog"] button:has-text("Confirm")')
        .first();
      if (await confirm.count() > 0 && await confirm.isVisible()) {
        await confirm.click();
      }
      await page.waitForLoadState('networkidle');
      await page.waitForTimeout(1_000);
    }
  }
});
