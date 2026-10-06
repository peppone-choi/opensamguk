// 포로 · 등용(P-R05) 스모크 — 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 인재는 등용 · 인재탐색 옵션으로 채우고, 포로 목록 읽기(K4-12)가 오기 전이라 「잡은 포로」는 서버 대기다.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { retinueTable, serveCampaign } from '../support/campaignFixtures';
import { BOTH, coveredTargets, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const table = {
  ...retinueTable('full'),
  '/api/commands/employ-options': {
    inputId: 'action.employ', available: true,
    targets: [{ generalId: 41, name: '석도', available: true }, { generalId: 42, name: '곽도', available: false, code: 'NOT_FREE', reason: '이미 소속이 있습니다.' }],
  },
  '/api/commands/search-options': { inputId: 'action.search', available: true, undiscoveredCount: 2, targets: [] },
};
const coveredIn = (root: Locator): Promise<string[]> => coveredTargets(root, 'a, button, [role="option"], [role="radio"]');

async function quality(page: Page, scope = 'main') {
  expect(await coveredIn(page.locator(scope).first())).toEqual([]);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, scope)).toEqual([]);
  expect(await titleOnlyInfo(page, scope)).toEqual([]);
}

test('인재 · 포로 — 찾지 못한 인물 · 불가 사유 · 등용은 대상 미리 채운 명령 흐름, 포로는 서버 대기 · 설득 준비 중', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  await page.goto('/game/retinue/captives', { waitUntil: 'domcontentloaded' });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main.getByText('찾지 못한 인물 2명')).toBeVisible({ timeout: 60_000 });
  await expect(main.getByRole('option', { name: /곽도/ })).toHaveAttribute('aria-disabled', 'true');
  await quality(page);
  if (isMobile(info)) {
    await press(main.getByRole('option', { name: /석도/ }), info);
    const sheet = page.getByRole('dialog', { name: '석도 — 등용' });
    await expect(sheet).toContainText('서버 대기');
    await quality(page, '[role="dialog"]');
    await press(sheet.getByRole('button', { name: '닫기' }), info);
    await expect(sheet).toBeHidden();
    await press(main.getByRole('radio', { name: /포로/ }), info);
  } else {
    await press(main.getByRole('option', { name: /석도/ }), info);
    await expect(main.getByRole('button', { name: '석도 등용 — 명령 목록에 넣기' })).toBeVisible();
  }
  await expect(main.getByText('포로 목록 — 준비 중')).toBeVisible();
  await expect(main.getByRole('button', { name: /설득/ })).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
  // 원장 행이 없는 석방 · 억류는 그리지 않는다
  await expect(main.getByRole('button', { name: /석방|억류/ })).toHaveCount(0);
});

test('등용 → 명령 흐름(대상 미리 채움)', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  await page.goto('/game/retinue/captives', { waitUntil: 'domcontentloaded' });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await press(main.getByRole('option', { name: /석도/ }).first(), info);
  const go = isMobile(info)
    ? page.getByRole('dialog', { name: '석도 — 등용' }).getByRole('button', { name: '등용 — 명령 흐름에서 순 고르기' })
    : main.getByRole('button', { name: '석도 등용 — 명령 목록에 넣기' });
  await press(go, info);
  await page.waitForURL((u) => u.searchParams.get('do') === 'action.employ' && u.searchParams.get('target') === 'general:41');
});
