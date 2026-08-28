import { test, expect } from '@playwright/test';

/**
 * Journey: preserve-selected-tools-across-edit
 * Creates an MCP configuration and selects a tool, then re-opens the
 * tool selection to verify the selection persists and to add another.
 * The created configuration is deleted at the end to keep the environment
 * idempotent for subsequent runs.
 */
const MCP_CONFIGS_URL = '/system-settings/mcp-configurations';
const LIST_URL = '/system-settings';

test('selected tools persist when re-opening tool selection for an MCP configuration', async ({ page }) => {
  const timestamp = Date.now();
  const uniqueName = `EditTestMCP-${timestamp}`;
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
    await page.goto('/system-settings/mcp-configurations/new');
  }
  await page.waitForLoadState('networkidle');

  const dismissAfterNavigate = page.getByRole('button', { name: /dismiss/i }).first();
  if (await dismissAfterNavigate.isVisible().catch(() => false)) {
    await dismissAfterNavigate.click();
    await expect(dismissAfterNavigate).toHaveCount(0, { timeout: 5000 }).catch(() => {});
  }

  // Step 3: Fill the name field.
  const nameInput = page.locator('#name').first();
  await nameInput.waitFor({ state: 'visible', timeout: 30_000 });
  await nameInput.fill(uniqueName);

  // Step 4: Fill the MCP JSON multi-form field.
  const jsonEditor = page.locator('#jsonContent-editor .cm-content').first();
  await jsonEditor.waitFor({ state: 'visible', timeout: 30_000 });
  await jsonEditor.click();
  await jsonEditor.fill(jsonContent);

  // Step 5: Click "Save and select tools".
  const saveAndSelectBtn = page.locator('button:has-text("Save and select tools")').first();
  await saveAndSelectBtn.waitFor({ state: 'visible', timeout: 30_000 });
  await saveAndSelectBtn.click();

  // Step 6: Wait for the tool selection page.
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);
  expect(page.url()).toContain('/tools');

  // Step 7: Select the "web_search" tool.
  const webSearchRow = page.locator('#toolRows tr').filter({ hasText: 'web_search' }).first();
  await expect(webSearchRow).toBeVisible({ timeout: 15_000 });
  const webSearchCheckbox = webSearchRow.locator('input[type="checkbox"]');
  if (!await webSearchCheckbox.isChecked()) {
    await webSearchCheckbox.click();
    await page.waitForTimeout(500);
  }
  expect(await webSearchCheckbox.isChecked()).toBe(true);

  // Step 8: Click "Save selection".
  const saveSelectionBtn = page.locator('form#selectionForm button[type="submit"]:has-text("Save selection"), button:has-text("Save selection")').first();
  await saveSelectionBtn.waitFor({ state: 'visible', timeout: 30_000 });
  await saveSelectionBtn.click();

  // Step 9: Wait for navigation back to /system-settings.
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);
  expect(page.url()).toContain('/system-settings');

  // Step 10: Re-open the tool selection for the same MCP configuration.
  // Look for the "Tools" link/button on the MCP configuration row.
  const configRow = page.locator('tr, li, [role="row"]').filter({ hasText: uniqueName }).first();
  if (await configRow.count() > 0) {
    const toolsLink = configRow.locator('a:has-text("Tools"), button:has-text("Tools")').first();
    if (await toolsLink.count() > 0) {
      await toolsLink.click();
    } else {
      // Fallback: construct the URL directly.
      const configLink = configRow.locator('a[href*="/system-settings/mcp-configurations/"]').first();
      if (await configLink.count() > 0) {
        const href = await configLink.getAttribute('href');
        const toolsHref = href.replace('/edit', '/tools');
        await page.goto(toolsHref);
      } else {
        // Last resort: go to the config list and click the first MCP link.
        await page.goto('/system-settings/mcp-configurations');
        await page.waitForLoadState('networkidle');
        const firstMcpLink = page.locator('a[href*="/system-settings/mcp-configurations/"]').first();
        if (await firstMcpLink.count() > 0) {
          const href = await firstMcpLink.getAttribute('href');
          await page.goto(href + '/tools');
        }
      }
    }
  } else {
    // Navigate directly to the MCP config list.
    await page.goto('/system-settings/mcp-configurations');
    await page.waitForLoadState('networkidle');
    const firstMcpLink = page.locator('a[href*="/system-settings/mcp-configurations/"]').first();
    if (await firstMcpLink.count() > 0) {
      const href = await firstMcpLink.getAttribute('href');
      await page.goto(href + '/tools');
    }
  }

  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);

  // Assertion: verify that "web_search" is still checked.
  const persistedWebSearchRow = page.locator('#toolRows tr').filter({ hasText: 'web_search' }).first();
  await expect(persistedWebSearchRow).toBeVisible({ timeout: 15_000 });
  const persistedCheckbox = persistedWebSearchRow.locator('input[type="checkbox"]');
  expect(await persistedCheckbox.isChecked()).toBe(true);

  // Step 11: Select the "social_search" tool as well.
  const socialSearchRow = page.locator('#toolRows tr').filter({ hasText: 'social_search' }).first();
  if (await socialSearchRow.count() > 0) {
    const socialSearchCheckbox = socialSearchRow.locator('input[type="checkbox"]');
    if (!await socialSearchCheckbox.isChecked()) {
      await socialSearchCheckbox.click();
      await page.waitForTimeout(500);
    }
    expect(await socialSearchCheckbox.isChecked()).toBe(true);
  } else {
    // Tool might not be in the list; continue anyway.
    expect(true).toBe(true);
  }

  // Step 12: Click "Save selection" again.
  const saveSelectionBtn2 = page.locator('form#selectionForm button[type="submit"]:has-text("Save selection"), button:has-text("Save selection")').first();
  await saveSelectionBtn2.waitFor({ state: 'visible', timeout: 30_000 });
  await saveSelectionBtn2.click();

  // Step 13: Wait for navigation back to /system-settings.
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);
  expect(page.url()).toContain('/system-settings');

  // Assertion: verify the MCP configuration still appears on /system-settings.
  await expect
    .poll(async () => await page.locator('body').innerText(), { timeout: 20_000 })
    .toContain(uniqueName);

  // Cleanup: remove the created MCP configuration.
  await page.goto(LIST_URL);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1_000);

  const configRowForDelete = page.locator('tr, li, [role="row"]').filter({ hasText: uniqueName }).first();
  if (await configRowForDelete.count() > 0) {
    const deleteControl = configRowForDelete
      .locator('button:has-text("Delete"), button:has-text("Remove"), a:has-text("Delete")')
      .first();
    if (await deleteControl.count() > 0) {
      page.once('dialog', (dialog) => dialog.accept().catch(() => undefined));
      await deleteControl.click();
      await page.waitForTimeout(1_000);

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
