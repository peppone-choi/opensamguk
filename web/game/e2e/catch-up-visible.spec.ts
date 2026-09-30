import { expect, test } from '@playwright/test';

for (const viewport of [
  { name: 'desktop', width: 1280, height: 800 },
  { name: 'mobile', width: 390, height: 844 },
]) {
  test(`catch-up status stays visible and clears on ${viewport.name}`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    let active = true;
    await page.route('**/api/game/api/front-info**', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          result: true,
          global: {
            year: 200, month: 1, turnPhaseText: '상순', turnterm: 5,
            scenarioText: '테스트 세계', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0,
            catchUp: {
              active, multiplier: 2, backlogSeconds: 72000,
              remainingSeconds: 72000, etaAt: '2026-09-27T20:00:00Z',
            },
          },
          general: { hasGeneral: false },
          nation: null, city: null,
        }),
      });
    });
    await page.goto('/');
    const notice = page.getByRole('status', { name: '밀린 턴 따라잡기' });
    await expect(notice).toBeVisible();
    await expect(notice).toContainText('2배속');
    await expect(notice).toContainText('한국 시간');
    const box = await notice.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(viewport.width + 1);

    active = false;
    await page.reload();
    await expect(page.getByRole('status', { name: '밀린 턴 따라잡기' })).toHaveCount(0);
  });
}
