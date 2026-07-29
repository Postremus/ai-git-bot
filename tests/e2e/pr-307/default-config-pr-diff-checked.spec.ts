import {test, expect} from '@playwright/test';

test('Default tool configuration has pr-diff enabled', async ({page}) => {
    // Navigate to /system-settings
    await page.goto('/system-settings');
    await page.waitForLoadState('networkidle');

    // Locate the Default tool configuration entry.
    // We look for a row/card that contains the text "Default" and has a Tools button.
    const toolsButton = page.locator('#section-bot-tools tbody tr').filter({ has: page.locator('td:first-child', { hasText: 'Default' }) }).getByRole('link', { name: 'Tools' })

    const dismissButton = page.getByRole('button', { name: 'Dismiss' });
    if (await dismissButton.count() > 0) {
        await dismissButton.click();
    }
    // Click the Tools button for the Default configuration.
    await toolsButton.click();
    await page.waitForLoadState('networkidle');
    // Wait for the tools selection dialog/panel to open.

    // The tools selection opens as a full page, not a dialog.
    await expect(page.getByRole('heading', { name: 'Select built-in tools' })).toBeVisible();

    // Assert the pr-diff checkbox is checked.
    const prDiffCheckbox = page.locator('tr:has(code:has-text("pr-diff")) input[type="checkbox"]');
    await expect(prDiffCheckbox).toBeVisible();
    await expect(prDiffCheckbox).toBeChecked();

    // Clean-up: close the dialog without changing any state, to keep the test idempotent.
    const closeButton = page
        .getByRole('button', {name: /^(close|cancel|done)$/i})
        .first();
    if ((await closeButton.count()) > 0) {
        await closeButton.click().catch(() => {
        });
    } else {
        await page.keyboard.press('Escape').catch(() => {
        });
    }
});
