// 기록 5분류(P-H01) 스모크 — 백엔드 없이 합성 로그인 · front-info · 사건 피드로 데스크톱 · 모바일 같은 흐름(@both).
// 지도 자료(지형 · 省)는 503 이라 고른 기록의 지도 칸은 「불러오지 못했습니다」로 그린다 — 배치 · 누르기만 본다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, MOBILE_ONLY, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const at = (month: number, phase: number, ordinal = 0) => ({ year: 200, month, phase, ordinal });
const FEEDS: Record<string, unknown[]> = {
  WORLD: [
    { id: 101, kind: 'county.ownerChanged', section: 'WORLD', occurredAt: at(3, 2, 5), refs: { CITY: 12, FROM_NATION: 2, TO_NATION: 1 }, facts: {} },
    { id: 102, kind: 'yuedan.announced', section: 'WORLD', occurredAt: at(3, 1), refs: {}, facts: {} },
  ],
  COURT: [{ id: 201, kind: 'court.dispatchReceived', section: 'COURT', occurredAt: at(3, 2, 3), refs: { REQUEST: 'req-1', ISSUER: 8, TARGET: 7 }, facts: {} }],
  BATTLE: [{ id: 301, kind: 'deploy.started', section: 'BATTLE', occurredAt: at(2, 3), refs: {}, facts: {} }],
  PERSONAL: [{ id: 401, kind: 'yuedan.assessed', section: 'PERSONAL', occurredAt: at(3, 1), refs: { ACTOR: 7 }, facts: { RENOWN_BEFORE: 40, RENOWN_AFTER: 52, RENOWN_CHANGE: 12 } }],
  RETINUE_NATION: [{ id: 501, kind: 'enlist.retainerJoined', section: 'RETINUE_NATION', occurredAt: at(3, 1), refs: { PERSON: 9 }, facts: {} }],
};

async function openRecords(page: Page) {
  const baseURL = test.info().project.use.baseURL ?? 'http://localhost:3001';
  await page.context().addCookies([{ name: 'sam_server', value: 'pep', url: baseURL }]);
  await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
  await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
  await page.route((url) => url.pathname.startsWith('/api/game/'), (r) => {
    const url = new URL(r.request().url());
    if (url.pathname.endsWith('/front-info')) {
      return r.fulfill({ json: {
        result: true,
        global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
        general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
        nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
      } });
    }
    if (url.pathname.endsWith('/api/world-events')) return r.fulfill({ json: { events: FEEDS.WORLD, nextCursor: null } });
    if (url.pathname.endsWith('/api/events')) {
      return r.fulfill({ json: { events: FEEDS[url.searchParams.get('section') ?? ''] ?? [], nextCursor: null } });
    }
    if (url.pathname.endsWith('/api/map/preview')) {
      return r.fulfill({ json: { cities: [{ id: 12, name: '허현', level: 5, nationId: 1, x: 0, y: 0 }], nations: [{ id: 1, name: '조조', color: '#4f7fbf' }, { id: 2, name: '원소', color: '#bf4f4f' }] } });
    }
    return r.fulfill({ status: 503, json: {} });
  });
  await page.goto('/game/records', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '기록' })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole('list', { name: '기록' })).toBeVisible({ timeout: 60_000 });
}

test('기록: 다섯 분류를 합쳐 그린다 — 누를 것 44 · title 전용 0 · 넘침 0', { tag: [BOTH] }, async ({ page }) => {
  await openRecords(page);
  const list = page.getByRole('list', { name: '기록' });
  await expect(list.getByText('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.')).toBeVisible();
  await expect(list.getByText('어느 인물의 발령이 도착했습니다.')).toBeVisible();
  await expect(list.getByRole('link', { name: '응답하기' })).toBeVisible();
  await expect(list.getByText('출병했습니다.')).toBeVisible();
  expect(await smallTouchTargets(page, 'main[aria-label="게임 콘텐츠"]')).toEqual([]);
  expect(await titleOnlyInfo(page, 'main[aria-label="게임 콘텐츠"]')).toEqual([]);
  await expectNoHorizontalOverflow(page);
  // 셸 하단 탭 · 레일에서 「기록」, 하위 탭에서 「기록 5분류」가 켜져 있다(옛 world-log 시험의 「월드 기록」 탭 보장).
  await expect(page.getByRole('navigation', { name: '게임 메뉴' }).first().getByRole('link', { name: '기록' })).toHaveAttribute('aria-current', 'page');
  await expect(page.getByRole('navigation', { name: '하위 화면' }).getByRole('link', { name: '기록 5분류' })).toHaveAttribute('aria-current', 'page');
});

test('기록: 줄을 고르면 데스크톱은 오른쪽 칸, 모바일은 하단 시트', { tag: [BOTH] }, async ({ page }, testInfo) => {
  await openRecords(page);
  await press(page.getByRole('button', { name: /월단평에서 명망이 올랐습니다/ }), testInfo);
  if (isMobile(testInfo)) {
    const sheet = page.getByRole('dialog', { name: '개인 행적 · 200년 3월 상순' });
    await expect(sheet).toBeVisible();
    expect(await smallTouchTargets(page, '[role="dialog"]')).toEqual([]);
    await press(sheet.getByRole('button', { name: '닫기' }), testInfo);
    await expect(sheet).toBeHidden();
  } else {
    const detail = page.getByRole('region', { name: '고른 기록' });
    await expect(detail.getByText('월단평에서 명망이 올랐습니다(40 → 52).')).toBeVisible();
    await expect(detail.getByText('+12')).toBeVisible();
  }
  await expectNoHorizontalOverflow(page);
});

test('기록: 전장 보고는 종류만 — 누구 · 어디는 서버 준비 중', { tag: [BOTH] }, async ({ page }, testInfo) => {
  await openRecords(page);
  await press(page.getByRole('radio', { name: '전장 보고' }), testInfo);
  const list = page.getByRole('list', { name: '기록' });
  await expect(list.getByText('출병했습니다.')).toBeVisible();
  await expect(list.getByText('누구 · 어디 — 서버 준비 중')).toBeVisible();
  await expect(page.getByText(/서버가 아직 사건으로 쓰지 않음: 개인 조우/)).toBeVisible();
  await expect(list.getByText('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.')).toHaveCount(0);
  await expectNoHorizontalOverflow(page);
});

test('기록: 모바일 분류 칩은 줄 안에서 민다 — 화면은 넘치지 않는다', { tag: [MOBILE_ONLY] }, async ({ page }) => {
  await openRecords(page);
  await expect(page.getByRole('radiogroup', { name: '기록 분류' })).toBeVisible();
  await expectNoHorizontalOverflow(page);
});

test('옛 전황 주소는 기록으로 308', { tag: [BOTH] }, async ({ page }) => {
  const res = await page.request.get('/game/world-log', { maxRedirects: 0 });
  expect(res.status()).toBe(308);
  expect(new URL(res.headers()['location'] ?? '', 'http://x').pathname).toBe('/game/records');
});
