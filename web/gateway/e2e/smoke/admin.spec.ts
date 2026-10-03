// P-G09 운영 콘솔(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다(page.route).
// 운영 데이터를 바꾸는 단추(턴 멈추기)는 여기서만 — 합성 응답에 대고 누른다. 실제 서버엔 닿지 않는다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, titleOnlyInfo } from '../../../game/e2e/support/parity';
import { smallHitAreas } from '../support/hitArea';

const ADMIN = { id: 1, username: 'admin', nickname: '운영자', role: 'ADMIN', email: null, picture: null, imageServer: 0 };
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const svc = (tag: string) => ({ reachable: true, version: '0.9.2', imageTag: tag, buildTime: null });
const VERSION = {
  gateway: svc('sha-3aa678b'),
  skew: false,
  servers: [
    { id: 'pep', name: 'pep', generation: 1, scenarioCode: 'scenario_1020', gameApi: svc('sha-3aa678b'), gameEngine: svc('sha-3aa678b'), skew: false },
    { id: 'uni', name: '통일 서버', generation: 3, scenarioCode: 'scenario_9', gameApi: svc('sha-3aa678b'), gameEngine: svc('sha-19c2e0d'), skew: true },
  ],
};

async function open(page: Page, baseURL: string | undefined, user: Record<string, unknown> = ADMIN) {
  const paused: Record<string, boolean> = { pep: false, uni: true };
  const posts: string[] = [];
  await page.context().addCookies([{ name: 'sam_access', value: 'smoke', url: baseURL ?? 'http://127.0.0.1:3000' }]);
  await page.route('**/api/auth/me', (route) => route.fulfill(json({ user })));
  await page.route('**/api/proxy/admin/**', (route) => {
    const url = new URL(route.request().url());
    const id = url.searchParams.get('serverId') ?? '';
    if (url.pathname.endsWith('/admin/version')) return route.fulfill(json(VERSION));
    if (url.pathname.endsWith('/admin/scenarios')) return route.fulfill(json({ scenarios: [{ code: 'scenario_1020', title: '군웅할거' }] }));
    if (url.pathname.endsWith('/admin/deploy/status')) return route.fulfill(json({ configured: true, serverId: id, currentTag: 'sha-3aa678b', availableTags: [], latestTag: id === 'pep' ? 'sha-4f01c77' : null, promotionAvailable: id === 'pep' }));
    if (url.pathname.endsWith('/admin/turn-daemon/status')) {
      return route.fulfill(json({ paused: paused[id], running: !paused[id], statusLabel: '', catchUp: id === 'pep' ? { active: true, multiplier: 2, backlogSeconds: 7200, remainingSeconds: 7200, etaAt: '2026-10-02T06:00:00Z', initialBacklogSeconds: 7200, recoveredSeconds: 0 } : null }));
    }
    if (/turn-daemon\/(pause|resume)$/.test(url.pathname) && route.request().method() === 'POST') {
      posts.push(url.pathname);
      paused[id] = url.pathname.endsWith('pause');
      return route.fulfill(json({ paused: paused[id], changed: true, statusLabel: '' }));
    }
    return route.fulfill(json({}, 404));
  });
  await page.goto('/admin');
  return posts;
}

test.describe('P-G09 운영 콘솔 — 데스크톱 · 모바일 같은 흐름', () => {
  test('개요: 탭 8 · 서버 표(모바일 카드) · 가로 넘침 없음 · 누를 영역 44', { tag: BOTH }, async ({ page, baseURL }, info) => {
    await open(page, baseURL);
    await expect(page.getByRole('heading', { level: 1, name: '개요' })).toBeVisible();
    const rail = page.getByRole('navigation', { name: '운영 콘솔' });
    await expect(rail.getByRole('button')).toHaveCount(8);
    const box = (await rail.boundingBox())!;
    if (isMobile(info)) expect(box.height).toBeLessThan(80); // 가로 탭 칩 한 줄
    else expect(Math.round(box.width)).toBe(200);
    await expect(page.getByText('따라잡는 중 · 2배속')).toBeVisible();
    await expect(page.getByText('새 버전 sha-4f01c77')).toBeVisible();
    await expect(page.getByText('군웅할거')).toBeVisible();
    await expect(page.getByText(/동결중|가동중/)).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'body')).toEqual([]);
    expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);
  });

  test('턴: 멈추기는 확인 대화상자를 거친다(합성 응답)', { tag: BOTH }, async ({ page, baseURL }) => {
    const posts = await open(page, baseURL);
    await expect(page.getByRole('table')).toBeVisible();
    await page.getByRole('navigation', { name: '운영 콘솔' }).getByRole('button', { name: '턴', exact: true }).click();
    await expect(page.getByText('턴 도는 중')).toBeVisible();
    await page.getByRole('button', { name: '턴 멈추기' }).click();
    const dialog = page.getByRole('dialog', { name: '턴 멈추기' });
    await expect(dialog).toContainText('pep 1기 서버의 턴이 멈춥니다.');
    expect(posts).toEqual([]);
    await dialog.getByRole('button', { name: '턴 멈추기' }).click();
    await expect(page.getByText('턴 멈춤')).toBeVisible();
    expect(posts).toEqual(['/api/proxy/admin/turn-daemon/pause']);
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'body')).toEqual([]);
  });

  test('운영자가 아니면 권한 없음 상태와 로비 링크', { tag: BOTH }, async ({ page, baseURL }) => {
    await open(page, baseURL, { ...ADMIN, role: 'USER' });
    await expect(page.getByText('운영자만 볼 수 있습니다')).toBeVisible();
    await expect(page.getByRole('link', { name: '로비로' })).toHaveAttribute('href', '/lobby');
    await expect(page.getByRole('navigation', { name: '운영 콘솔' })).toHaveCount(0);
  });
});
