// 공개 서버 목록(C8 producer · D112) — 로그인 · 로비 · /api/servers 가 공개 원천만 쓰는지(@both).
//  - 원천 불명(gateway-api /servers 503): 「서버 목록을 확인하지 못했습니다」, /api/servers 도 503 — env 표(pep · uni)로 대신하지 않는다.
//  - 검증 중 서버는 공개 목록에 없다 — env 표에 pep 이 있어도 로비 · 로그인에 보이지 않는다(D112 ①).
// 서버 렌더 요청은 page.route 로 못 가로채서 gateway-api 스텁의 모드를 바꾼다(e2e/support/gatewayApiStub.ts).
import { expect, test, type Page } from '@playwright/test';
import { BOTH } from '../../../game/e2e/support/parity';
import { makePublicServersUnavailable, resetPublicServers, setPublicServers } from '../support/publicServers';

const UNI_ONLY = [{ id: 'uni', name: '통일 서버', generation: 3, gameUrl: '/game/uni' }];

async function member(page: Page, baseURL: string | undefined) {
  const json = (body: unknown) => ({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  await page.context().addCookies([{ name: 'sam_access', value: 'smoke', url: baseURL ?? 'http://127.0.0.1:3000' }]);
  await page.route('**/api/auth/me', (route) => route.fulfill(json({ user: { id: 1, username: 'hahoudon', nickname: '원양', role: 'USER', email: null, picture: null, imageServer: 0 } })));
  await page.route('**/api/server-basic-info/**', (route) => route.fulfill(json({ game: { isUnited: 0, status: 'OPEN', year: 200, month: 3, userCnt: 1, maxUserCnt: 30, npcCnt: 0, nationCnt: 1, blockGeneralCreate: 0, turnTerm: 10, scenario: 's' }, me: null })));
  for (const glob of ['**/api/notices', '**/api/server-events/**']) await page.route(glob, (route) => route.fulfill(json({ notices: [], events: [] })));
  await page.route('**/api/server-map/**', (route) => route.fulfill({ status: 404, contentType: 'application/json', body: '{}' }));
  await page.route('**/api/server-imperial/**', (route) => route.fulfill(json({ status: 'NOT_SEEDED', badges: [] })));
}

test.describe('공개 서버 목록', () => {
  test.afterEach(async () => { await resetPublicServers(); });

  test('원천을 모르면 로그인 · 로비 「서버 목록을 확인하지 못했습니다」, /api/servers 503 — env 표로 대신하지 않는다', { tag: BOTH }, async ({ page, baseURL }) => {
    await makePublicServersUnavailable();
    const api = await page.request.get('/api/servers');
    expect(api.status()).toBe(503);
    expect(await api.json()).toEqual({ error: { code: 'SERVER_LIST_UNAVAILABLE' } });

    await page.goto('/login');
    await expect(page.getByText('서버 목록을 확인하지 못했습니다')).toBeVisible();
    await expect(page.getByText('지금 열린 서버가 없습니다')).toHaveCount(0);

    await member(page, baseURL);
    await page.goto('/lobby');
    await expect(page.getByRole('heading', { level: 1, name: '게임 로비' })).toBeVisible();
    await expect(page.getByText('서버 목록을 확인하지 못했습니다')).toBeVisible();
    await expect(page.getByRole('article', { name: 'pep' })).toHaveCount(0);
  });

  test('검증 중 서버(공개 목록에 없음)는 env 표에 있어도 로비에 없다', { tag: BOTH }, async ({ page, baseURL }) => {
    await setPublicServers(UNI_ONLY);
    const api = await page.request.get('/api/servers');
    expect(await api.json()).toEqual({ servers: UNI_ONLY });

    await member(page, baseURL);
    await page.goto('/lobby');
    await expect(page.getByRole('article', { name: '통일 서버' })).toBeVisible();
    await expect(page.getByRole('article', { name: 'pep' })).toHaveCount(0);
  });
});
