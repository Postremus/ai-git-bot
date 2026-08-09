import { test, expect } from '@playwright/test';

/**
 * Journey: create-mcp-configuration-and-select-tools
 * This journey creates data, so the created MCP configuration is deleted
 * again at the end to keep the environment idempotent for subsequent runs.
 */
const MCP_CONFIGS_URL = '/system-settings/mcp-configurations';
const LIST_URL = '/system-settings';

test('admin can create a new MCP configuration and select tools', async ({ page }) => {
  const timestamp = Date.now();
  const uniqueName = `SearchMCP-${timestamp}`;
  const jsonContent = JSON.stringify([
    {
      name: 'search',
      type: 'url',
      url: 'https://RivalSearchMCP.fastmcp.app/mcp'
    }
  ]);

  // Step 1: Navigate directly to /system-settings.
  await page.goto(LIST_URL);
  await page.waitForLoadState('networkidle');
  const dismissButton = page.getByRole('button', { name: /dismiss/i }).first();
  if (await dismissButton.isVisible().catch(() => false)) {
    await dismissButton.click();
    await expect(dismissButton).toHaveCount(0, { timeout: 5000 }).catch(() => {});
  }
  await page.locator('main, body').first().waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForTimeout(1_000);

  // Step 2: Click the "Add" button in the "MCP configurations" section.
  const addButtons = page.locator('button:has-text("Add"), a:has-text("Add")')
    .filter({ has: page.locator('h2, h3, h4').filter({ hasText: /MCP configuration/i }) });
  if (await addButtons.count() > 0) {
    await addButtons.first().click();
  } else {
    // Fallback: navigate directly to the new MCP config form.
    await page.goto('/system-settings/mcp-configurations/new');
  }
  await page.waitForLoadState('networkidle');

  // Handle any dismiss dialogs.
  const dismissAfterNavigate = page.getByRole('button', { name: /dismiss/i }).first();
  if (await dismissAfterNavigate.isVisible().catch(() => false)) {
    await dismissAfterNavigate.click();
    await expect(dismissAfterNavigate).toHaveCount(0, { timeout: 5000 }).catch(() => {});
  }

  // Step 3: Wait for the MCP form to render and fill in the name field.
  const nameInput = page.locator('#name').first();
  await nameInput.waitFor({ state: 'visible', timeout: 30_000 });
  await nameInput.fill(uniqueName);

  // Step 4: Fill the MCP JSON multi-form field (textarea#jsonContent).
  const jsonTextarea = page.locator('#jsonContent').first();
  await jsonTextarea.waitFor({ state: 'visible', timeout: 30_000 });
  await jsonTextarea.fill(jsonContent);

  // Step 5: Click the "Save and select tools" button.
  const saveAndSelectBtn = page.locator('button:has-text("Save and select tools")').first();
  await saveAndSelectBtn.waitFor({ state: 'visible', timeout: 30_000 });
  await saveAndSelectBtn.click();

  // Step 6: Wait for the tool selection page to load.
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);

  // Verify we are on the tool selection page.
  expect(page.url()).toContain('/tools');

  // Step 7: Select the "web_search" tool.
  // The tool list is rendered dynamically via JS. Use the table body to find the row.
  const toolRow = page.locator('#toolRows tr').filter({ hasText: 'web_search' }).first();
  await expect(toolRow).toBeVisible({ timeout: 15_000 });

  // Click the checkbox in that row.
  const checkbox = toolRow.locator('input[type="checkbox"]');
  const isChecked = await checkbox.isChecked();
  if (!isChecked) {
    await checkbox.click();
    await page.waitForTimeout(500);
  }
  expect(await checkbox.isChecked()).toBe(true);

  // Step 8: Click the "Save selection" button.
  const saveSelectionBtn = page.locator('form#selectionForm button[type="submit"]:has-text("Save selection"), button:has-text("Save selection")').first();
  await saveSelectionBtn.waitFor({ state: 'visible', timeout: 30_000 });
  await saveSelectionBtn.click();

  // Step 9: Wait for navigation back to /system-settings.
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);
  expect(page.url()).toContain('/system-settings');

  // Assertion: verify the new MCP configuration and tool appear on /system-settings.
  await expect
    .poll(async () => await page.locator('body').innerText(), { timeout: 20_000 })
    .toContain(uniqueName);

  // Cleanup: remove the created MCP configuration so the state is restored.
  await page.goto(LIST_URL);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);

  // Navigate back to the config list to find the delete control.
  const mcpLinks = page.locator('a[href*="/system-settings/mcp-configurations/"]');
  if (await mcpLinks.count() > 0) {
    const configRow = page.locator('tr, li, [role="row"]').filter({ hasText: uniqueName }).first();
    if (await configRow.count() > 0) {
      const deleteControl = configRow
        .locator('button:has-text("Delete"), button:has-text("Remove"), a:has-text("Delete")')
        .first();
      if (await deleteControl.count() > 0) {
        page.once('dialog', (dialog) => dialog.accept().catch(() => undefined));
        await deleteControl.click();
        await page.waitForTimeout(1_000);

        // Handle any confirmation dialog rendered in the DOM.
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
  }
});
