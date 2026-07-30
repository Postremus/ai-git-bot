import { test, expect } from '@playwright/test';

test('Tour highlights a navbar anchor element', async ({ page, context }) => {
  await context.clearCookies();

  await page.goto('/dashboard');

  // Wait for the tour balloon
  const nextButton = page.getByRole('button', { name: /next/i }).first();
  await expect(nextButton).toBeVisible({ timeout: 10000 });

  // An element with [data-tour] should have the highlight class
  const highlighted = page.locator('[data-tour].giteabot-tour-highlight').first();
  await expect(highlighted).toBeVisible({ timeout: 10000 });
});
