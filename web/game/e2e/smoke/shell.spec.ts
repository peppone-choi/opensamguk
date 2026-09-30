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

// ---- v3.1 셸 하나(2026-10-01 셸 통합) ----------------------------------------------------------------
// 합성 로그인 · front-info 로 부 · 월단평을 연다(백엔드 없음 — 게임 읽기는 503, 턴 루프 읽기는 404 → 「운영 상태 확인 중」).
async function openShell(page: import('@playwright/test').Page, path = '/game/retinue/yuedan') {
  await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
  await page.route((url) => url.pathname.endsWith('/front-info'), (r) => r.fulfill({ json: {
    result: true,
    global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
    general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
    nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
  } }));
  await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
  await page.route((url) => url.pathname.startsWith('/api/game/') && !url.pathname.endsWith('/front-info'), (r) => r.fulfill({ status: 503, json: {} }));
  await page.goto(path, { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '월단평' })).toBeVisible({ timeout: 60_000 });
}

/** 누를 것의 가운데를 다른 상자가 덮는지(K10 「덮임」과 같은 방법 — elementFromPoint). */
async function coveredIn(page: import('@playwright/test').Page, selector: string): Promise<string[]> {
  return page.locator(selector).first().evaluate((root) => {
    const out: string[] = [];
    for (const el of Array.from(root.querySelectorAll<HTMLElement>('a, button'))) {
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) continue;
      const cx = r.x + r.width / 2;
      const cy = r.y + r.height / 2;
      if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) continue;
      const hit = document.elementFromPoint(cx, cy);
      if (hit !== el && !el.contains(hit)) out.push(`${(el.textContent ?? '').trim()} ← ${hit?.tagName}.${hit?.className}`);
    }
    return out;
  });
}

test('셸: 데스크톱은 레일, 모바일은 하단 탭 — 누를 것 44 · 덮임 0 · 넘침 0', { tag: [BOTH] }, async ({ page }, testInfo) => {
  await openShell(page);
  const menus = page.getByRole('navigation', { name: '게임 메뉴' });
  await expect(menus).toHaveCount(1); // 보이는 것 하나(다른 하나는 display:none)
  const visible = menus.first();
  if (isMobile(testInfo)) {
    await expect(visible.getByRole('link')).toHaveText(['작전실', '부', '계책', '기록']);
    await expect(visible.getByRole('button', { name: '전체' })).toBeVisible();
  } else {
    await expect(visible.getByRole('link')).toHaveText(['작전실', '부', '계책', '영지', '군단', '조정', '기록', '광장', '도움말']);
  }
  await expect(visible.getByRole('link', { name: '부' })).toHaveAttribute('aria-current', 'page');
  expect(await coveredIn(page, 'body')).toEqual([]);
  expect(await smallTouchTargets(page, 'header')).toEqual([]);
  expect(await smallTouchTargets(page, 'nav[aria-label="게임 메뉴"]:visible')).toEqual([]);
  expect(await smallTouchTargets(page, 'nav[aria-label="하위 화면"]')).toEqual([]);
  expect(await titleOnlyInfo(page, 'header')).toEqual([]);
  await expectNoHorizontalOverflow(page);
  await expect(page.locator('[data-band="unknown"]')).toContainText('운영 상태 확인 중');
});

test('셸: 모바일 「전체」 시트는 모든 묶음을 연다', { tag: ['@mobile-only'] }, async ({ page }) => {
  await openShell(page);
  await page.getByRole('button', { name: '전체' }).tap();
  const sheet = page.getByRole('dialog', { name: '전체 메뉴' });
  await expect(sheet).toBeVisible();
  for (const group of ['작전실', '부', '계책', '영지', '군단', '조정', '기록', '광장']) await expect(sheet.getByText(group, { exact: true }).first()).toBeVisible();
  expect(await coveredIn(page, '[role="dialog"]')).toEqual([]);
  await sheet.getByRole('button', { name: '메뉴 닫기' }).tap();
  await expect(sheet).toBeHidden();
});

test('옮긴 캠페인 화면의 옛 주소는 새 주소로 한 번에 308', { tag: [BOTH] }, async ({ page }) => {
  const cases: Array<[string, string]> = [
    ['/game/yuedan', '/game/retinue/yuedan'], ['/game/hand', '/game/stratagem'], ['/game/posts', '/game/territory'],
    ['/game/supply', '/game/territory/supply'], ['/game/siege?county=7', '/game/corps/siege?county=7'],
    ['/game/orders', '/game/court?tab=orders'], ['/game/war-room', '/game'], ['/game/hwiha/war-room', '/game'],
  ];
  for (const [from, to] of cases) {
    const res = await page.request.get(from, { maxRedirects: 0 });
    expect(res.status(), from).toBe(308);
    const location = new URL(res.headers()['location'] ?? '', 'http://x');
    expect(`${location.pathname}${location.search}`, from).toBe(to);
  }
});
