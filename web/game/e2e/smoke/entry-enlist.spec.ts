import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets } from '../support/parity';
import { frontInfo } from '../support/campaignFixtures';
import { serveHelpApi } from './help-api';

// Standalone CI has no SERVER_ID. Leave sam_server unset so real Next destinations stay serverless.
async function serveEntry(page: Page, kind: 'none' | 'free' | 'affiliated', denied?: 401 | 403) {
  const info = frontInfo();
  info.general.hasGeneral = kind !== 'none';
  info.general.nationId = kind === 'affiliated' ? 1 : 0;
  let resultReads = 0;
  const writes: unknown[] = [];
  const turnIdxs: string[] = [];
  // Owned ring with already-adjusted server metadata; slot 01 is taken so the default is 02.
  const slots: { turnIdx: number; action: string; brief: string; arg: Record<string, unknown> }[] = [
    { turnIdx: 0, action: 'action.train', brief: '훈련', arg: {} },
  ];
  await page.route('**/api/auth/me', r => r.fulfill({ json: { user: { id: 1, username: 'entry-qa', nickname: '장수', role: 'USER' } } }));
  await page.route('**/api/game/**', async r => {
    const url = new URL(r.request().url());
    const path = url.pathname.replace(/^\/api\/game/, '');
    if (path === '/api/front-info') return r.fulfill({ json: info });
    if (path === '/api/commands/enlistment-options') {
      if (denied) return r.fulfill({ status: denied, json: { error: { code: denied === 401 ? 'UNAUTHORIZED' : 'FORBIDDEN' } } });
      return r.fulfill({ json: { result: true, inputId: 'action.enlist', maxReservedTurns: 12, options: [
        { mode: 'NATION', targetId: 2, label: '조조', availability: { status: 'AVAILABLE' } },
        { mode: 'GENERAL', targetId: 101, label: '가상 직접 대상', availability: { status: 'AVAILABLE' } },
        { mode: 'NATION', targetId: 3, label: '원소', availability: { status: 'BLOCKED', code: 'CAPACITY', reason: '해당 주공의 명망 수용량이 부족합니다.' } },
      ] } });
    }
    if (path === '/api/reserved-commands') {
      expect(url.searchParams.get('generalId')).toBe('7');
      return r.fulfill({ json: { result: true, generalId: 7, slots, year: 190, month: 3, turnPhase: 3, turnPhaseText: '하순',
        turnTime: '2026-10-09 22:40:00', turnTerm: 60, date: '2026-10-09 22:10:00', autorunLimit: null } });
    }
    if (path === '/api/command/action.enlist') {
      const body = r.request().postDataJSON();
      const turnIdx = url.searchParams.get('turnIdx') ?? '';
      writes.push(body);
      turnIdxs.push(turnIdx);
      expect(url.searchParams.get('generalId')).toBe('7');
      // The stored row is the canonical argument at the requested slot, as the reservation read-back will see it.
      slots.push({ turnIdx: Number(turnIdx), action: 'action.enlist', brief: '출사', arg: body });
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
  return { writes, turnIdxs, resultReads: () => resultReads };
}

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
  const api = await serveEntry(page, 'free', status);
  await page.goto('/game/join');
  await expect(page.getByText(status === 401 ? '로그인이 만료되었습니다. 다시 로그인해 주세요.' : '이 장수로 출사할 권한이 없습니다.')).toBeVisible();
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
