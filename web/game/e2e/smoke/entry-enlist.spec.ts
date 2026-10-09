import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets } from '../support/parity';
import { frontInfo } from '../support/campaignFixtures';
import { serveHelpApi } from './help-api';

const denial = (status: 401 | 403) => ({ status, json: { error: { code: status === 401 ? 'UNAUTHORIZED' : 'FORBIDDEN' } } });
const deniedText = (status: 401 | 403) => status === 401 ? '로그인이 만료되었습니다. 다시 로그인해 주세요.' : '이 장수로 출사할 권한이 없습니다.';
const CAPACITY = '해당 주공의 명망 수용량이 부족합니다.';

// Standalone CI has no SERVER_ID. Leave sam_server unset so real Next destinations stay serverless.
async function serveEntry(page: Page, kind: 'none' | 'free' | 'affiliated', fixture: {
  readonly denied?: 401 | 403;
  /** Start from an empty ring instead of slot 01 taken by training. */
  readonly empty?: boolean;
  /** Deny the reservation read-back that follows the POST. */
  readonly readbackDenied?: 401 | 403;
} = {}) {
  const { denied, empty, readbackDenied } = fixture;
  const info = frontInfo();
  info.general.hasGeneral = kind !== 'none';
  info.general.nationId = kind === 'affiliated' ? 1 : 0;
  let resultReads = 0;
  let caoBlocked = false;
  let deniedReads = 0;
  const writes: unknown[] = [];
  const bodies: (string | null)[] = [];
  const turnIdxs: string[] = [];
  const queries: string[] = [];
  const holds: { reached: () => void; released: Promise<void> }[] = [];
  // Owned ring with already-adjusted server metadata; slot 01 is taken so the default is 02.
  const slots: { turnIdx: number; action: string; brief: string; arg: Record<string, unknown> }[] = empty ? [] : [
    { turnIdx: 0, action: 'action.train', brief: '훈련', arg: {} },
  ];
  await page.route('**/api/auth/me', r => r.fulfill({ json: { user: { id: 1, username: 'entry-qa', nickname: '장수', role: 'USER' } } }));
  await page.route('**/api/game/**', async r => {
    const url = new URL(r.request().url());
    const path = url.pathname.replace(/^\/api\/game/, '');
    if (path === '/api/front-info') return r.fulfill({ json: info });
    if (path === '/api/commands/enlistment-options') {
      if (denied) return r.fulfill(denial(denied));
      return r.fulfill({ json: { result: true, inputId: 'action.enlist', maxReservedTurns: 12, options: [
        { mode: 'NATION', targetId: 2, label: '조조', availability: caoBlocked ? { status: 'BLOCKED', code: 'CAPACITY', reason: CAPACITY } : { status: 'AVAILABLE' } },
        { mode: 'GENERAL', targetId: 101, label: '가상 직접 대상', availability: { status: 'AVAILABLE' } },
        { mode: 'NATION', targetId: 3, label: '원소', availability: { status: 'BLOCKED', code: 'CAPACITY', reason: CAPACITY } },
      ] } });
    }
    if (path === '/api/reserved-commands') {
      expect(url.searchParams.get('generalId')).toBe('7');
      if (deniedReads > 0 && readbackDenied) { deniedReads--; return r.fulfill(denial(readbackDenied)); }
      // The body is what the server holds when the read arrives; a held read answers with that older ring.
      const json = { result: true, generalId: 7, slots: slots.map(slot => ({ ...slot })), year: 190, month: 3, turnPhase: 3, turnPhaseText: '하순',
        turnTime: '2026-10-09 22:40:00', turnTerm: 60, date: '2026-10-09 22:10:00', autorunLimit: null };
      const hold = holds.shift();
      if (hold) { hold.reached(); await hold.released; }
      return r.fulfill({ json });
    }
    if (path === '/api/command/action.enlist') {
      const body = r.request().postDataJSON();
      const turnIdx = url.searchParams.get('turnIdx') ?? '';
      writes.push(body);
      bodies.push(r.request().postData());
      turnIdxs.push(turnIdx);
      queries.push(url.search);
      expect(url.searchParams.get('generalId')).toBe('7');
      // The stored row is the canonical argument at the requested slot, as the reservation read-back will see it.
      slots.push({ turnIdx: Number(turnIdx), action: 'action.enlist', brief: '출사', arg: body });
      // fetchGame retries a 401 once after /api/auth/me answers, so a 401 read-back denies both attempts.
      if (readbackDenied) deniedReads = readbackDenied === 401 ? 2 : 1;
      return r.fulfill({ status: 202, json: { status: 'AVAILABLE', requestId: 'enlist-qa' } });
    }
    if (path === '/api/command/result/enlist-qa') {
      resultReads++;
      if (resultReads === 1) return r.fulfill({ json: { status: 'PENDING', requestId: 'enlist-qa' } });
      return r.fulfill({ json: { status: 'PENDING', phase: 'reservationAccepted', requestId: 'enlist-qa' } });
    }
    return r.fulfill({ status: 503, json: {} });
  });
  await serveHelpApi(page, { onlyHelp: true });
  return {
    writes, bodies, turnIdxs, queries, slots, resultReads: () => resultReads,
    blockCao: () => { caoBlocked = true; },
    /** Holds the next ring read until released; `reached` settles once the browser has asked for it. */
    holdNextSlots: () => {
      let reached!: () => void;
      let release!: () => void;
      const arrived = new Promise<void>(resolve => { reached = resolve; });
      holds.push({ reached, released: new Promise<void>(resolve => { release = resolve; }) });
      return { arrived, release };
    },
  };
}

/** The shell's real turn SSE: held until `turn()`, then exactly one `turnCompleted` frame; reconnects get 503. */
async function holdTurnSignal(page: Page) {
  let turn!: () => void;
  const turned = new Promise<void>(resolve => { turn = resolve; });
  let sent = false;
  await page.route(url => url.pathname === '/api/game/sse/turn', async route => {
    await turned;
    if (sent) return route.fulfill({ status: 503, json: {} });
    sent = true;
    return route.fulfill({ status: 200, contentType: 'text/event-stream', body: 'event: turnCompleted\ndata: {}\n\n' });
  });
  return turn;
}

const reserveButton = (page: Page) => page.getByRole('button', { name: '출사 예약', exact: true }).and(page.locator('[data-input-id="action.enlist"]'));

test('E01 no-general entry and E02/E03 waiting pages have no loop or 404 @both', { tag: BOTH }, async ({ page }, info) => {
  await serveEntry(page, 'none');
  const response = await page.goto('/game/join');
  expect(response?.status()).toBe(200);
  await expect(page).toHaveURL(/\/game$/);
  await expect(page.getByTestId('game-entry-screen')).toBeVisible();
  await expect(page.getByRole('navigation', { name: '게임 메뉴' })).toHaveCount(0);
  const entry = page.getByRole('region', { name: '시작 방식 선택', exact: true });
  await expect(entry).toBeVisible();
  await expect(entry.getByRole('link', { name: '생성 화면 보기', exact: true })).toHaveAttribute('href', '/game/create');
  await expect(entry.getByRole('link', { name: '역사 인물 화면 보기', exact: true })).toHaveAttribute('href', '/game/create/historical');
  const lobbyLink = page.getByRole('banner').getByRole('link', { name: '로비로', exact: true });
  await expect(lobbyLink).toBeVisible();
  await expect(lobbyLink).toHaveAttribute('href', '/lobby');
  for (const [label, anchor, path] of [
    ['생성 화면 보기', 'creation-waiting', '/game/create'],
    ['역사 인물 화면 보기', 'historical-waiting', '/game/create/historical'],
  ]) {
    await press(page.getByRole('link', { name: label }), info);
    await expect(page).toHaveURL(new RegExp(`${path}$`));
    await expect(page.getByTestId(anchor)).toBeVisible();
    await expect(page.getByRole('navigation', { name: '게임 메뉴' })).toHaveCount(0);
    await expect(page.getByRole('textbox')).toHaveCount(0);
    await press(page.getByRole('link', { name: '입구로' }), info);
    await expect(page.getByTestId('game-entry-screen')).toBeVisible();
    await expect(page).toHaveURL(/\/game$/);
    await expect(page.getByRole('navigation', { name: '게임 메뉴' })).toHaveCount(0);
  }
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, '[data-testid="game-entry-screen"]')).toEqual([]);
});

test('E04 candidate reason and 202 to reservation result use the real input anchor @both', { tag: BOTH }, async ({ page }, info) => {
  const api = await serveEntry(page, 'free');
  await page.goto('/game/join');
  await expect(page.getByTestId('enlist-screen')).toBeVisible();
  await expect(page.getByRole('navigation', { name: '게임 메뉴' })).toHaveCount(0);
  await press(page.getByRole('option', { name: /원소/ }), info);
  await expect(page.getByRole('dialog')).toBeVisible();
  await expect(page.getByRole('dialog')).toContainText('해당 주공의 명망 수용량이 부족합니다.');
  await press(page.getByRole('button', { name: '닫기', exact: true }), info);
  await press(page.getByRole('option', { name: '조조', exact: true }), info);
  const ring = page.getByTestId('turn-slots-column');
  await expect(ring.getByRole('button')).toHaveCount(12);
  await expect(ring.getByRole('button', { name: /^01순 — / })).toHaveAttribute('data-state', 'reserved');
  await expect(ring.getByRole('button', { name: '02순 — 빈 순', exact: true })).toHaveAttribute('aria-pressed', 'true');
  await expect(ring.getByText('190년 3월 하순 · 22:40', { exact: true })).toBeVisible();
  await press(ring.getByRole('button', { name: '06순 — 빈 순', exact: true }), info);
  await expect(page.getByText('몇 번째 순에: 06순', { exact: true })).toBeVisible();
  await expect(ring.getByText('190년 5월 중순 · 03:40', { exact: true })).toBeVisible();
  await press(page.getByRole('button', { name: '출사 예약', exact: true }).and(page.locator('[data-input-id="action.enlist"]')), info);
  await expect(page.locator('[data-command-outcome="reserved"]')).toHaveText('출사 명령이 06순에 예약되었습니다.');
  expect(api.resultReads()).toBeGreaterThan(1);
  expect(api.writes).toEqual([{ mode: 'NATION', targetId: 2 }]);
  expect(api.turnIdxs).toEqual(['5']);
  await expect(ring.getByRole('button', { name: /^06순 — / })).toHaveAttribute('data-state', 'reserved');
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, '[data-testid="enlist-screen"]')).toEqual([]);
  await info.attach('E04-current-source', { body: await page.screenshot(), contentType: 'image/png' });
});

for (const status of [401, 403] as const) test(`E04 exact HTTP ${status} denies candidates and writes @both`, { tag: BOTH }, async ({ page }) => {
  const api = await serveEntry(page, 'free', { denied: status });
  await page.goto('/game/join');
  await expect(page.getByText(deniedText(status))).toBeVisible();
  await expect(page.getByRole('option')).toHaveCount(0);
  await expect(page.getByRole('button', { name: '출사 예약', exact: true })).toHaveCount(0);
  expect(api.writes).toEqual([]);
});

// Synthetic reservation fixture: this checks exact target preservation, not executed relationships.
test('D164 GENERAL selection preserves the selected general in one reservation @both', { tag: BOTH }, async ({ page }, info) => {
  const api = await serveEntry(page, 'free');
  await page.goto('/game/join');
  await expect(page.getByTestId('enlist-screen')).toBeVisible();
  await page.evaluate(() => document.fonts.ready);
  await press(page.getByRole('radio', { name: /장수/ }), info);
  await press(page.getByRole('option', { name: '가상 직접 대상', exact: true }), info);
  await press(page.getByRole('button', { name: '출사 예약', exact: true }).and(page.locator('[data-input-id="action.enlist"]')), info);
  await expect(page.locator('[data-command-outcome="reserved"]')).toHaveText('출사 명령이 02순에 예약되었습니다.');
  expect(api.writes).toEqual([{ mode: 'GENERAL', targetId: 101 }]);
  expect(api.turnIdxs).toEqual(['1']);
  expect(api.resultReads()).toBeGreaterThan(1);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, '[data-testid="enlist-screen"]')).toEqual([]);
});

for (const [no, turnIdx] of [['01', 0], ['12', 11]] as const) {
  test(`E04 explicit ${no}순 posts exactly turnIdx=${turnIdx} with the original target @both`, { tag: BOTH }, async ({ page }, info) => {
    const api = await serveEntry(page, 'free', { empty: true });
    await page.goto('/game/join');
    await press(page.getByRole('option', { name: '조조', exact: true }), info);
    const ring = page.getByTestId('turn-slots-column');
    await press(ring.getByRole('button', { name: `${no}순 — 빈 순`, exact: true }), info);
    await expect(page.getByText(`몇 번째 순에: ${no}순`, { exact: true })).toBeVisible();
    await press(reserveButton(page), info);
    await expect(page.locator('[data-command-outcome="reserved"]')).toHaveText(`출사 명령이 ${no}순에 예약되었습니다.`);
    expect(api.queries).toEqual([`?generalId=7&turnIdx=${turnIdx}`]);
    expect(api.bodies).toEqual(['{"mode":"NATION","targetId":2}']);
    await expect(ring.getByRole('button', { name: new RegExp(`^${no}순 — `) })).toHaveAttribute('data-state', 'reserved');
  });
}

// The click's own preflight read is held while a real turn signal refreshes the screen underneath it.
for (const race of ['occupied', 'blocked', 'loading'] as const) {
  test(`E04 a turn refresh that overtakes the held preflight (${race}) posts nothing @both`, { tag: BOTH }, async ({ page }, info) => {
    const api = await serveEntry(page, 'free');
    const turn = await holdTurnSignal(page);
    await page.goto('/game/join');
    await press(page.getByRole('option', { name: '조조', exact: true }), info);
    const ring = page.getByTestId('turn-slots-column');
    await expect(ring.getByRole('button', { name: '02순 — 빈 순', exact: true })).toHaveAttribute('aria-pressed', 'true');
    const preflight = api.holdNextSlots();
    await press(reserveButton(page), info);
    await preflight.arrived;
    const refreshRead = race === 'loading' ? api.holdNextSlots() : null;
    if (race === 'occupied') api.slots.push({ turnIdx: 1, action: 'action.train', brief: '훈련', arg: {} });
    if (race === 'blocked') api.blockCao();
    turn();
    if (refreshRead) await refreshRead.arrived;
    else if (race === 'occupied') await expect(ring.getByRole('button', { name: /^02순 — / })).toHaveAttribute('data-state', 'reserved');
    else await expect(page.getByRole('option', { name: /조조/ })).toHaveAttribute('aria-disabled', 'true');
    // The held read answers with the ring from before the refresh: on its own it would still allow 02순.
    preflight.release();
    const reason = race === 'occupied' ? '02순에는 이미' : race === 'blocked' ? CAPACITY : '새로 불러오는 중';
    await expect(page.locator('[data-command-outcome="rejected"]')).toContainText(reason);
    expect(api.writes).toEqual([]);
    refreshRead?.release();
  });
}

for (const status of [401, 403] as const) {
  test(`E04 read-back HTTP ${status} after the POST shows the denial and keeps the unknown receipt @both`, { tag: BOTH }, async ({ page }, info) => {
    const api = await serveEntry(page, 'free', { readbackDenied: status });
    await page.goto('/game/join');
    await press(page.getByRole('option', { name: '조조', exact: true }), info);
    await press(reserveButton(page), info);
    await expect(page.getByText(deniedText(status))).toBeVisible();
    await expect(page.locator('[data-command-outcome="unknown"]')).toHaveText('02순 출사 예약 결과를 확인하지 못했습니다. 작전실 12순에서 확인해 주세요.');
    await expect(page.getByRole('button', { name: '출사 예약', exact: true })).toHaveCount(0);
    await expect(page.locator('[data-command-outcome="reserved"]')).toHaveCount(0);
    expect(api.writes).toEqual([{ mode: 'NATION', targetId: 2 }]);
    expect(api.turnIdxs).toEqual(['1']);
  });
}
