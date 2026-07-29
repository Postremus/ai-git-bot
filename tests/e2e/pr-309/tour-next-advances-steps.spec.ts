import { test, expect } from '@playwright/test';

test('Clicking Next advances the tour step counter', async ({ page, context }) => {
  await context.clearCookies();

  await page.goto('/dashboard');

  // Wait for the tour balloon
  const nextButton = page.getByRole('button', { name: /next/i }).first();
  await expect(nextButton).toBeVisible({ timeout: 10000 });

  // Capture the total steps N from the initial "1 / N" indicator
  const counterLocator = page.locator('text=/\\d+\\s*\\/\\s*\\d+/').first();
  await expect(counterLocator).toBeVisible({ timeout: 10000 });
  const initialText = (await counterLocator.textContent()) ?? '';
  const match = initialText.match(/(\d+)\s*\/\s*(\d+)/);
  expect(match).not.toBeNull();
  const total = match ? match[2] : '';

  // Click Next
  await nextButton.click();

  // Assert the counter advanced to "2 / N"
  await expect(page.locator(`text=/2\\s*\\/\\s*${total}/`).first()).toBeVisible({ timeout: 10000 });
});
