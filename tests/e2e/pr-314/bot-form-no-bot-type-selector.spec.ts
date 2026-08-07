import { test, expect } from '@playwright/test';

/**
 * Journey: bot-form-no-bot-type-selector
 * The legacy "Bot Type" selector must be gone from the bot creation form.
 * Read-only journey - no state is mutated, so it is idempotent by construction.
 */
test('bot form no longer exposes a Bot Type selector', async ({ page }) => {
  // Step 1: Navigate directly to the bot creation form URL.
  await page.goto('/bots/new');
  await page.waitForLoadState('networkidle');

  // Step 2: Wait for the bot form element to be attached in the DOM.
  const form = page.locator('form').first();
  await form.waitFor({ state: 'attached', timeout: 30_000 });

  // Give client side hydration a moment so late-rendered controls are counted too.
  await page.waitForTimeout(1_000);

  // Step 3: Inspect the rendered form controls for any legacy bot-type field.
  const botTypeControls = page.locator(
    [
      'input[name*="botType" i]',
      'select[name*="botType" i]',
      'textarea[name*="botType" i]',
      'input[id*="botType" i]',
      'select[id*="botType" i]',
      'textarea[id*="botType" i]',
      'input[name*="bot_type" i]',
      'select[name*="bot_type" i]',
      'input[id*="bot_type" i]',
      'select[id*="bot_type" i]',
      '[data-testid*="botType" i]',
    ].join(', '),
  );

  // Assertion: no form control bound to a botType field exists on the page.
  await expect(botTypeControls).toHaveCount(0);
});
