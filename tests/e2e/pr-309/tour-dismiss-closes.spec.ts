import { test, expect } from '@playwright/test';

test('Dismiss button closes the tour and removes the overlay', async ({ page, context }) => {
  await context.clearCookies();

  await page.goto('/dashboard');

  // Wait for tour balloon
  const nextButton = page.getByRole('button', { name: /next/i }).first();
  await expect(nextButton).toBeVisible({ timeout: 10000 });

  // Click Dismiss
  const dismissButton = page.getByRole('button', { name: /dismiss/i }).first();
  await expect(dismissButton).toBeVisible({ timeout: 10000 });
  await dismissButton.click();

  // Tour balloon (identified by the presence of Next button in tour) no longer present
  await expect(nextButton).toHaveCount(0, { timeout: 10000 });
});
