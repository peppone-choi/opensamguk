// 작전실 읽기 범위 스모크 — 합성 API(page.route)로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 이동 흐름에서 목적지 지도를 연 채 셸의 실제 턴 신호(SSE turnCompleted)로 front-info 의 순이 바뀌면, 옛 지도는 닫히고
// 이동 선택지를 새로 읽는다. 새 읽기를 붙잡아 두고 그 사이 화면을 남긴다. 누르기는 「지도에서 고르기」 한 번, 예약 POST 는 0.
import { expect, test, type Page } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, press } from '../support/parity';

const destination = (provinceId: string, name: string, available: boolean) => ({
  provinceId, name, available, reason: available ? null : '길이 끊겼습니다', reachability: 'MULTI_TURN', arrivesThisTurn: false, estimatedTurns: 2,
});
const moveOptions = (p2: boolean) => ({ inputId: 'action.move', available: true, destinations: [destination('P-2', '진류', p2), destination('P-4', '허창', true)] });

// 셸의 활동 신호(usePresencePulse → PresenceController)는 같은 /api/command/ 아래의 POST 지만 예약이 아니다.
const PRESENCE = '/api/game/api/command/presence';
const isCommandPost = (method: string, url: string) => method === 'POST' && new URL(url).pathname.startsWith('/api/game/api/command/');
const isReservationPost = (method: string, url: string) => isCommandPost(method, url) && new URL(url).pathname !== PRESENCE;

/** 셸의 턴 SSE — `turn()` 까지 붙잡았다가 turnCompleted 한 번, 다시 붙으면 503. */
async function holdTurnSignal(page: Page) {
  let turn!: () => void;
  const turned = new Promise<void>((resolve) => { turn = resolve; });
  let sent = false;
  await page.route((url) => url.pathname === '/api/game/sse/turn', async (route) => {
    await turned;
    if (sent) return route.fulfill({ status: 503, json: {} });
    sent = true;
    return route.fulfill({ status: 200, contentType: 'text/event-stream', body: 'event: turnCompleted\ndata: {}\n\n' });
  });
  return turn;
}

test('목적지 지도를 연 채 순이 바뀌면 지도가 닫히고 이동 선택지를 새로 읽는다 — 예약 POST 0', { tag: [BOTH] }, async ({ page }, info) => {
  let phase = 1;
  await serveCampaign(page, {
    '/api/front-info': () => ({ ...frontInfo(), global: { ...frontInfo().global, turnPhase: phase, turnPhaseText: phase === 1 ? '상순' : '중순' } }),
    '/api/reserved-commands': { result: true, generalId: 7, slots: [] },
  });
  // 나중에 건 route 가 먼저 받는다 — 이동 선택지는 여기서 센다. 새 순의 읽기는 놓아 줄 때까지 붙잡는다.
  const reads: number[] = [];
  let releaseSecond!: () => void;
  const second = new Promise<void>((resolve) => { releaseSecond = resolve; });
  await page.route((url) => url.pathname === '/api/game/api/commands/move-options', async (route) => {
    const readPhase = phase;
    reads.push(readPhase);
    if (readPhase === 2) await second;
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(moveOptions(readPhase === 1)) });
  });
  // 판별식 자체의 대조 — 이 화면의 실제 이동 예약 URL(api.ts `/api/command/{code}?generalId&turnIdx`)은 세고, presence 는 뺀다.
  expect(isReservationPost('POST', 'http://127.0.0.1:3001/api/game/api/command/action.move?generalId=7&turnIdx=4')).toBe(true);
  expect(isReservationPost('POST', `http://127.0.0.1:3001${PRESENCE}`)).toBe(false);
  const posts: string[] = [];
  const presence: string[] = [];
  page.on('request', (r) => {
    if (isReservationPost(r.method(), r.url())) posts.push(`${r.url()} ${r.postData() ?? ''}`);
    else if (isCommandPost(r.method(), r.url())) presence.push(r.url());
  });
  const turn = await holdTurnSignal(page);

  await page.goto('/game?do=action.move&slot=4', { waitUntil: 'domcontentloaded' });
  const flow = page.getByTestId('command-flow');
  const mapButton = flow.getByRole('button', { name: '지도에서 고르기' });
  await expect(mapButton).toBeVisible({ timeout: 60_000 });
  expect(reads.length).toBeGreaterThan(0);
  expect(reads.every((p) => p === 1)).toBe(true);
  await press(mapButton, info);
  const dialog = page.getByRole('dialog', { name: '목적지 지도에서 고르기' });
  await expect(dialog).toBeVisible();
  await info.attach('1-map-open', { body: await page.screenshot(), contentType: 'image/png' });

  phase = 2;
  turn();
  await expect(dialog).toHaveCount(0, { timeout: 15_000 });
  await expect.poll(() => reads.filter((p) => p === 2).length, { timeout: 15_000 }).toBeGreaterThan(0);
  await expect(mapButton).toHaveCount(0);
  await info.attach('2-rereading', { body: await page.screenshot(), contentType: 'image/png' });

  releaseSecond();
  await expect(mapButton).toBeVisible({ timeout: 15_000 });
  await expect(dialog).toHaveCount(0);
  await info.attach('3-new-options', { body: await page.screenshot(), contentType: 'image/png' });
  // 수집기는 실제로 /api/command/ POST 를 보았고(셸 presence), 그중 예약은 하나도 없다.
  expect(presence.length).toBeGreaterThan(0);
  expect(posts).toEqual([]);
});
