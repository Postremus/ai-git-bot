import { test, expect } from '@playwright/test';

test('new endpoint form exposes at least one event type checkbox', async ({ page }) => {
  await page.goto('/admin/event-hooks/new');
  await page.waitForLoadState('domcontentloaded');
  const dismissButton = page.getByRole('button', { name: 'Dismiss' });
  if (await dismissButton.count() > 0) {
    await dismissButton.click();
  }

  // Give the form time to render.
  await page.waitForSelector('form', { timeout: 10_000 });

  // Look for checkboxes whose name/id/label references prworkflow or issueassignment
  // (or common variants like "pull_request" / "issue_assign").
  const eventCheckbox = page
    .locator(
      'input[type="checkbox"][name*="pr" i], input[type="checkbox"][name*="pull" i], ' +
      'input[type="checkbox"][name*="issue" i], input[type="checkbox"][name*="workflow" i], ' +
      'input[type="checkbox"][id*="pr" i], input[type="checkbox"][id*="issue" i], ' +
      'input[type="checkbox"][id*="workflow" i]'
    )
    .first();

  await expect(eventCheckbox).toBeAttached({ timeout: 10_000 });
});
