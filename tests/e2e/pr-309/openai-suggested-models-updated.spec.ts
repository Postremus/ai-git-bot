import { test, expect } from '@playwright/test';

test('OpenAI AI integration form suggests the new gpt-5.6 models', async ({ page, context }) => {
  await context.clearCookies();

  await page.goto('/dashboard');
  const dismissButton = page.getByRole('button', { name: /dismiss/i }).first();
  if (await dismissButton.isVisible().catch(() => false)) {
    await dismissButton.click();
  }

  await page.goto('/ai-integrations/new');
  await page.waitForLoadState('networkidle');

  // Try to select OpenAI provider if a selector is present
  const openaiOption = page.getByText(/openai/i).first();
  if (await openaiOption.isVisible().catch(() => false)) {
    await openaiOption.click().catch(() => {});
  }

  // Attempt to open model suggestions dropdown
  const modelField = page.locator('input[name*="model" i], [placeholder*="model" i], [aria-label*="model" i]').first();
  if (await modelField.isVisible().catch(() => false)) {
    await modelField.click().catch(() => {});
    await modelField.focus().catch(() => {});
  }
  // Also try clicking any "suggestions" trigger
  const suggestionsTrigger = page.getByText(/suggest/i).first();
  if (await suggestionsTrigger.isVisible().catch(() => false)) {
    await suggestionsTrigger.click().catch(() => {});
  }

  // Assert the three new gpt-5.6 model names are present somewhere in the DOM
  await expect(page.getByText('gpt-5.6-sol').first()).toBeVisible({ timeout: 10000 });
  await expect(page.getByText('gpt-5.6-terra').first()).toBeVisible({ timeout: 10000 });
  await expect(page.getByText('gpt-5.6-luna').first()).toBeVisible({ timeout: 10000 });
});
