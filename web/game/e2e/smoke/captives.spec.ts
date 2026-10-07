// 포로 · 등용(P-R05) 스모크 — 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 인재 옵션과 실제 구금 읽기를 합성 응답으로 채우고, POST 경로와 본문을 확인한다.
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
  '/api/captives': { available: true, targets: [{ generalId: 52, name: '장합', nationId: 2, nationName: '원소',
    heldProvinceId: 'P-1', actualProvinceId: 'P-1', capturedAt: { year: 200, month: 3, phase: 2 }, expiry: 'NONE',
    persuadeAvailable: true, releaseAvailable: true }] },
  '/api/commands/court/releaseCaptive': { status: 'AVAILABLE', requestId: 'release-52' },
  '/api/command/result/release-52': { status: 'RESOLVED', requestId: 'release-52', ok: true,
    type: 'executionApplied', result: { commandKind: 'COURT_DECISION', actionCode: 'court.releaseCaptive' } },
};
const coveredIn = (root: Locator): Promise<string[]> => coveredTargets(root, 'a, button, [role="option"], [role="radio"]');

async function quality(page: Page, scope = 'main') {
  expect(await coveredIn(page.locator(scope).first())).toEqual([]);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, scope)).toEqual([]);
  expect(await titleOnlyInfo(page, scope)).toEqual([]);
}

test('인재 · 포로 — 불가 사유와 실제 구금행, 설득·석방이 각각 표시된다', { tag: [BOTH] }, async ({ page }, info) => {
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
  await expect(main.getByRole('region', { name: '장합 포로 처분' })).toContainText('구금 위치 P-1');
  await expect(main.getByRole('button', { name: /설득 — 순 고르기/ })).toHaveAttribute('data-input-status', 'AVAILABLE');
  await expect(main.getByRole('button', { name: '석방' })).toHaveAttribute('data-input-status', 'AVAILABLE');
  await expect(main.getByRole('button', { name: /억류/ })).toHaveCount(0);
  await quality(page);
});

test('석방 — 대상 ID로 무순 접수하고 terminal 성공만 성공으로 표시한다', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  await page.goto('/game/retinue/captives', { waitUntil: 'domcontentloaded' });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  if (isMobile(info)) await press(main.getByRole('radio', { name: /포로/ }), info);
  const sent = page.waitForRequest((r) => r.method() === 'POST' &&
    new URL(r.url()).pathname === '/api/game/api/commands/court/releaseCaptive');
  await press(main.getByRole('button', { name: '석방' }), info);
  const request = await sent;
  expect(request.postDataJSON()).toEqual({ targetGeneralId: 52 });
  expect(new URL(request.url()).searchParams.get('generalId')).toBe('7');
  await expect(main.getByText('포로를 석방했습니다.')).toBeVisible();
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
