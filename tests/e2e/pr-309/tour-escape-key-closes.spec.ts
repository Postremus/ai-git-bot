import { test, expect } from '@playwright/test';

test('Pressing Escape closes the tour', async ({ page, context }) => {
  await context.clearCookies();

  await page.goto('/dashboard');

  const nextButton = page.getByRole('button', { name: /next/i }).first();
  await expect(nextButton).toBeVisible({ timeout: 10000 });

  // Press Escape
  await page.keyboard.press('Escape');

  // Tour balloon should no longer be visible
  await expect(nextButton).toBeHidden({ timeout: 10000 });
});
