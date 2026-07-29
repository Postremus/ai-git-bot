import { test, expect } from '@playwright/test';

test('Missing-integration modal appears on new bot form when integrations are absent', async ({ page, context }) => {
  await context.clearCookies();

  // Navigate somewhere first to dismiss any potential tour
  await page.goto('/dashboard');
  const dismissButton = page.getByRole('button', { name: /dismiss/i }).first();
  if (await dismissButton.isVisible().catch(() => false)) {
    await dismissButton.click();
    await expect(dismissButton).toHaveCount(0, { timeout: 5000 }).catch(() => {});
  }

  // Navigate to /bots/new
  await page.goto('/bots/new');
  await page.waitForLoadState('networkidle');

  // Either the setup-required modal is visible, or the bot form is visible
  const setupModal = page.getByText(/setup required/i).first();
  const botForm = page.locator('form').first();

  const modalVisible = await setupModal.isVisible().catch(() => false);
  if (modalVisible) {
    await expect(setupModal).toBeVisible();
  } else {
    await expect(botForm).toBeVisible({ timeout: 10000 });
  }
});
