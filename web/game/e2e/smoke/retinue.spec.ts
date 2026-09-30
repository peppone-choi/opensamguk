// 부 편성(P-R01) 스모크 — 합성 자료(e2e/support/campaignFixtures)로 백엔드 없이 돈다. @both = 데스크톱 · 모바일 같은 흐름.
import { expect, test } from '@playwright/test';
import { retinueTable, serveCampaign } from '../support/campaignFixtures';
import { BOTH } from '../support/parity';

test('합성 자료로 부 편성이 뜬다(인물 있음)', { tag: [BOTH] }, async ({ page }) => {
  const served = await serveCampaign(page, retinueTable('full'));
  await page.goto('/game/retinue');
  await expect(page.getByText('허저').first()).toBeVisible({ timeout: 90_000 });
  await expect(page.getByText('무명 공조').first()).toBeVisible();
  expect(served.unknown).toEqual([]);
});
