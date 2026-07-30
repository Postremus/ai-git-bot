import { test, expect } from '@playwright/test';

test('First-run balloon-help tour appears on dashboard', async ({ page, context }) => {
  // Clear cookies to simulate first visit
  await context.clearCookies();
  await context.clearPermissions();

  // Navigate to /dashboard
  await page.goto('/dashboard');

  // Wait for tour balloon with a Next button to appear
  const nextButton = page.getByRole('button', { name: /next/i }).first();
  await expect(nextButton).toBeVisible({ timeout: 10000 });
});
