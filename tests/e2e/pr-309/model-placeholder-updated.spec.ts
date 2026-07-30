import { test, expect } from '@playwright/test';

test('AI integration model input shows the updated placeholder', async ({ page, context }) => {
  await context.clearCookies();

  await page.goto('/dashboard');
  const dismissButton = page.getByRole('button', { name: /dismiss/i }).first();
  if (await dismissButton.isVisible().catch(() => false)) {
    await dismissButton.click();
  }

  await page.goto('/ai-integrations/new');
  await page.waitForLoadState('networkidle');

  // Look for an input whose placeholder mentions both new model names.
  const placeholderRegex = /gpt-5\.6-sol[\s\S]*claude-sonnet-5|claude-sonnet-5[\s\S]*gpt-5\.6-sol/;
  const inputs = page.locator('input[placeholder], textarea[placeholder]');
  const count = await inputs.count();
  let found = false;
  for (let i = 0; i < count; i++) {
    const ph = (await inputs.nth(i).getAttribute('placeholder')) ?? '';
    if (placeholderRegex.test(ph)) {
      found = true;
      break;
    }
  }
  expect(found).toBe(true);
});
