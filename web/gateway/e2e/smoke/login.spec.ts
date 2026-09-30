// P-G02 로그인(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다: 서버 현황 자료는 page.route 로 대 준다.
// 게이트웨이 틀(K3): web/gateway/playwright.config.ts 의 desktop · mobile 프로젝트, baseURL = E2E_GATEWAY_URL.
// 서버 목록은 SERVER_REGISTRY_JSON='[{"id":"pep","name":"pep","generation":1},{"id":"uni","name":"통일 서버","generation":3}]' 로 띄운다.
// 목록이 비어 있으면 조용히 건너뛰지 않고 실패한다(0건이 「검사가 안 돌았다」를 뜻하면 안 된다).
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, titleOnlyInfo } from '../../../game/e2e/support/parity';

const PREVIEW = {
  serverName: 'pep', year: 200, month: 3, turnPhaseText: '중순',
  // 지도 판은 일부러 모르는 판 — 지형을 받지 않고 「지도를 불러오지 못했습니다」로 끝난다(캔버스 그림은 지도 레인 스모크가 본다).
  mapCode: 'smoke-unsupported', width: 1, height: 1,
  cities: [
    { id: 12, name: '허', displayName: '허현', level: 5, nationId: 1, x: 0, y: 0 },
    { id: 13, name: '업', level: 5, nationId: 2, x: 0, y: 0 },
    { id: 14, name: '영음', level: 5, nationId: 2, x: 0, y: 0 },
  ],
  nations: [{ id: 1, name: '조조', color: '#4a6fa5' }, { id: 2, name: '원소', color: '#a14b8c' }],
};
const EVENTS = {
  events: [
    { id: 7, kind: 'county.ownerChanged', section: 'WORLD', occurredAt: { year: 200, month: 3, phase: 2, ordinal: 1 }, refs: { CITY: 12, FROM_NATION: 2, TO_NATION: 1 }, facts: {} },
    { id: 6, kind: 'county.ownerChanged', section: 'WORLD', occurredAt: { year: 200, month: 3, phase: 1, ordinal: 0 }, refs: { CITY: 13, FROM_NATION: 0, TO_NATION: 2 }, facts: {} },
    { id: 5, kind: 'yuedan.announced', section: 'WORLD', occurredAt: { year: 200, month: 2, phase: 1, ordinal: 0 }, refs: {}, facts: {} },
  ],
  nextCursor: null,
};

async function serveStatus(page: Page) {
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  await page.route('**/api/server-map/**', (route) => route.fulfill(route.request().url().includes('/pep') ? json(PREVIEW) : json({ error: 'down' }, 502)));
  await page.route('**/api/server-events/**', (route) => route.fulfill(route.request().url().includes('/pep') ? json(EVENTS) : json({ events: [], nextCursor: null })));
  await page.route('**/api/server-imperial/**', (route) => route.fulfill(json({ status: 'NOT_SEEDED', badges: [] })));
  await page.route('**/api/notices', (route) => route.fulfill(json({ notices: [{ id: 1, title: '공개 알파 안내', body: '월드는 초기화될 수 있습니다.', pinned: true, publishedAt: '2026-09-05T00:00:00Z', deleted: false }] })));
}

async function open(page: Page) {
  await serveStatus(page);
  await page.goto('/login');
  await expect(page.getByRole('button', { name: /^pep/ }), '서버 목록이 비었다 — SERVER_REGISTRY_JSON 으로 게이트웨이를 띄웠는지 확인').toBeVisible();
}

/** 누를 영역(가운데에서 훑은 elementFromPoint 적중 범위, K10 board-lint 과 같은 뜻)이 44 미만이거나 가운데가 덮인 것.
 *  parity.smallTouchTargets 는 상자 크기를 잰다 — 지도 위에 떠 있는 패널은 겹침까지 봐야 해서 적중 범위로 잰다. */
async function smallHitAreas(page: Page, root: string): Promise<string[]> {
  // 화면 밖(아래)에 있는 것은 elementFromPoint 가 못 본다 — 한 화면씩 내려가며 가운데가 화면 안에 든 것만 한 번씩 잰다.
  return page.locator(root).first().evaluate(async (node) => {
    const out: string[] = [];
    const seen = new Set<Element>();
    const targets = Array.from(node.querySelectorAll<HTMLElement>('button, a[href], input:not([type="hidden"])'));
    const step = Math.max(200, Math.floor(innerHeight * 0.8));
    for (let y = 0; ; y += step) {
      window.scrollTo(0, y);
      await new Promise((resolve) => requestAnimationFrame(() => resolve(null)));
      for (const el of targets) {
        if (seen.has(el)) continue;
        const r = el.getBoundingClientRect();
        if (r.width === 0 || r.height === 0) { seen.add(el); continue; }
        const cx = r.left + r.width / 2;
        const cy = r.top + r.height / 2;
        if (cy < 0 || cy >= innerHeight || cx < 0 || cx >= innerWidth) continue;
        seen.add(el);
        const mine = (x: number, yy: number) => {
          if (x < 0 || yy < 0 || x >= innerWidth || yy >= innerHeight) return false;
          const hit = document.elementFromPoint(x, yy);
          return !!hit && (hit === el || el.contains(hit));
        };
        const name = (el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 20);
        if (!mine(cx, cy)) {
          // 가운데가 다른 요소에 덮였다 — 이 화면엔 열린 층(대화상자 · 시트)이 없으니 결함이다(K10 board-lint 「덮인 누를 것」).
          const top = document.elementFromPoint(cx, cy);
          out.push(`덮임 ${el.tagName.toLowerCase()} "${name}" ← ${top ? top.className || top.tagName : '없음'}`);
          continue;
        }
        const reach = (dx: number, dy: number) => { let d = 0; while (d < 64 && mine(cx + dx * (d + 1), cy + dy * (d + 1))) d += 1; return d; };
        const w = reach(-1, 0) + reach(1, 0) + 1;
        const h = reach(0, -1) + reach(0, 1) + 1;
        if (w < 44 || h < 44) out.push(`${el.tagName.toLowerCase()} "${name}" ${w}×${h}`);
      }
      if (y + innerHeight >= document.documentElement.scrollHeight) break;
    }
    window.scrollTo(0, 0);
    const missed = targets.filter((el) => !seen.has(el)).map((el) => `못 잼 ${el.tagName.toLowerCase()} "${(el.textContent ?? '').trim().slice(0, 20)}"`);
    return [...out, ...missed];
  });
}

test.describe('P-G02 로그인 — 데스크톱 · 모바일 같은 흐름', () => {
  test('그려진다: 소개 · 로그인 · 서버 현황 · 공지 · 정책, 가로 넘침 없음, 누를 영역 44', { tag: BOTH }, async ({ page }) => {
    await open(page);
    await expect(page.getByRole('heading', { level: 1, name: '로그인' })).toBeVisible();
    await expect(page.getByRole('region', { name: '소개' })).toBeVisible();
    await expect(page.getByRole('region', { name: '세력 현황' })).toBeVisible();
    await expect(page.getByRole('region', { name: '천하 정세' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: '정책' }).getByRole('link', { name: '이용약관' })).toHaveAttribute('href', '/terms');
    await expect(page.getByAltText('오픈삼국')).toHaveCount(1);
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, '.gw31-login__stage')).toEqual([]);
    expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);
  });

  test('서버 현황: 현 수 · 알림체 문장 · 서버 바꾸기', { tag: BOTH }, async ({ page }) => {
    await open(page);
    const nations = page.getByRole('region', { name: '세력 현황' });
    await expect(nations.getByRole('listitem')).toHaveText(['원소현 2', '조조현 1']);
    const events = page.getByRole('region', { name: '천하 정세' });
    await expect(events.getByText('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.')).toBeVisible();
    await expect(events.getByText('주인 없던 업을 원소가 차지했습니다.')).toBeVisible();
    await expect(events.getByText('200년 2월 월단평 결과가 발표됐습니다.')).toBeVisible();
    await page.getByRole('button', { name: /통일 서버/ }).click();
    await expect(page.getByRole('button', { name: /통일 서버/ })).toHaveAttribute('aria-pressed', 'true');
    await expect(nations.getByText('세력 현황을 불러오지 못했습니다')).toBeVisible();
    await expect(events.getByText('아직 공개된 사건이 없습니다')).toBeVisible();
  });

  test('로그인 폼: 빈 칸 오류 · 비밀번호 표시', { tag: BOTH }, async ({ page }) => {
    await open(page);
    // Next 경로 알림(__next-route-announcer__)도 role=alert 라 로그인 패널 안에서만 찾는다.
    const card = page.getByRole('region', { name: '로그인' });
    await card.getByRole('button', { name: '로그인', exact: true }).click();
    await expect(card.getByRole('alert')).toHaveText('계정명을 입력하세요');
    await card.getByLabel('계정명').fill('tester');
    await card.getByRole('button', { name: '로그인', exact: true }).click();
    await expect(card.getByRole('alert')).toHaveText('비밀번호를 입력하세요');
    await card.getByRole('button', { name: '표시' }).click();
    await expect(card.getByLabel('비밀번호', { exact: true })).toHaveAttribute('type', 'text');
  });

  test('지도 위 빈 곳은 지도가 받는다(떠 있는 층이 누르기를 먹지 않는다)', { tag: BOTH }, async ({ page }, testInfo) => {
    await open(page);
    // 데스크톱: 소개 판과 로그인 패널 사이 가운데 빈 곳. 모바일: 위 지도 띠의 머리줄 아래.
    const point = isMobile(testInfo) ? { x: 195, y: 150 } : { x: 800, y: 420 };
    const onMap = await page.evaluate(({ x, y }) => {
      const hit = document.elementFromPoint(x, y);
      return !!hit && !!hit.closest('.gw31-login__map');
    }, point);
    expect(onMap, `(${point.x}, ${point.y}) 는 지도 층이어야 한다`).toBe(true);
  });
});
