import { test, expect } from '@playwright/test';

test('Anthropic AI integration form suggests the new claude-5 models', async ({ page, context }) => {
  await context.clearCookies();

  await page.goto('/dashboard');
  const dismissButton = page.getByRole('button', { name: /dismiss/i }).first();
  if (await dismissButton.isVisible().catch(() => false)) {
    await dismissButton.click();
  }

  await page.goto('/ai-integrations/new');
  await page.waitForLoadState('networkidle');

  // Select Anthropic provider type if a selector is present
  const anthropicOption = page.getByText(/anthropic/i).first();
  if (await anthropicOption.isVisible().catch(() => false)) {
    await anthropicOption.click().catch(() => {});
  }

  // Attempt to open the model suggestions dropdown
  const modelField = page.locator('input[name*="model" i], [placeholder*="model" i], [aria-label*="model" i]').first();
  if (await modelField.isVisible().catch(() => false)) {
    await modelField.click().catch(() => {});
    await modelField.focus().catch(() => {});
  }
  const suggestionsTrigger = page.getByText(/suggest/i).first();
  if (await suggestionsTrigger.isVisible().catch(() => false)) {
    await suggestionsTrigger.click().catch(() => {});
  }

  await expect(page.getByText('claude-opus-5').first()).toBeVisible({ timeout: 10000 });
  await expect(page.getByText('claude-sonnet-5').first()).toBeVisible({ timeout: 10000 });
  await expect(page.getByText('claude-fable-5').first()).toBeVisible({ timeout: 10000 });
});
