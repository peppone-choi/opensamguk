// 셸 스모크 — 백엔드 없이 Next 서버만으로 도는 데스크톱 · 모바일 같은 흐름(@both).
// CI web (game) 잡이 `next start` 뒤 e2e/smoke 전체를 두 프로필로 돌린다. 화면 규칙(44 · title · 넘침) 도우미가 실제로 돈다는 것도 여기서 확인한다.
import { expect, test } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, smallTouchTargets, titleOnlyInfo } from '../support/parity';

test('옛 휘하 주소는 도메인 경로로 308', { tag: [BOTH] }, async ({ page }) => {
  const res = await page.request.get('/game/hwiha/retinue?tab=bonds', { maxRedirects: 0 });
  expect(res.status()).toBe(308);
  const location = new URL(res.headers()['location'] ?? '', 'http://x');
  expect(location.pathname).toBe('/game/retinue');
  expect(location.searchParams.get('tab')).toBe('bonds');
});

test('삼모 은퇴 경로는 셸을 그리기 전에 404', { tag: [BOTH] }, async ({ page }) => {
  for (const path of ['/game/auction', '/game/rankings/hall-of-fame']) {
    const res = await page.request.get(path, { maxRedirects: 0 });
    expect(res.status(), path).toBe(404);
  }
});

test('없는 화면은 넘침 없이 그린다 — 두 프로필의 창 크기가 실제로 다르다', { tag: [BOTH] }, async ({ page }, testInfo) => {
  const res = await page.goto('/game/does-not-exist-k3', { waitUntil: 'domcontentloaded' });
  expect(res?.status()).toBe(404);
  const width = await page.evaluate(() => window.innerWidth);
  if (isMobile(testInfo)) expect(width).toBe(390);
  else expect(width).toBeGreaterThanOrEqual(1200);
  expect(await page.evaluate(() => navigator.maxTouchPoints > 0)).toBe(isMobile(testInfo));
  await expectNoHorizontalOverflow(page);
});

test('화면 규칙 도우미가 어긴 것을 실제로 찾는다', { tag: [BOTH] }, async ({ page }) => {
  await page.setContent(`<main>
    <button style="width:44px;height:44px">큰</button>
    <button style="width:30px;height:20px">작은</button>
    <span title="이유는 호버로만">비활성</span>
  </main>`);
  expect(await smallTouchTargets(page, 'main')).toEqual(['button "작은" 30×20']);
  expect(await titleOnlyInfo(page, 'main')).toEqual(['span title="이유는 호버로만"']);
});
