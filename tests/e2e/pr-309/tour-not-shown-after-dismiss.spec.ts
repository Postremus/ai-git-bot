import { test, expect } from '@playwright/test';

test('Tour is not shown again after being dismissed', async ({ page, context }) => {
  await context.clearCookies();

  await page.goto('/dashboard');

  const nextButton = page.getByRole('button', { name: /next/i }).first();
  await expect(nextButton).toBeVisible({ timeout: 10000 });

  const dismissButton = page.getByRole('button', { name: /dismiss/i }).first();
  await dismissButton.click();
  await expect(nextButton).toHaveCount(0, { timeout: 10000 });

  // Reload
  await page.reload();

  // Give the app a moment to rehydrate, then assert no tour balloon is present
  await page.waitForLoadState('networkidle');
  await expect(page.getByRole('button', { name: /next/i })).toHaveCount(0);
  await expect(page.getByRole('button', { name: /dismiss/i })).toHaveCount(0);
});
