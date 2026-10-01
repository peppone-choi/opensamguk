// P-G09 운영 콘솔 · 서버 탭(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다(page.route).
// 리셋 · 게임 설정 저장은 합성 응답에만 대고 누른다. 실제 서버엔 닿지 않는다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, titleOnlyInfo } from '../../../game/e2e/support/parity';
import { smallHitAreas } from '../support/hitArea';

const ADMIN = { id: 1, username: 'admin', nickname: '운영자', role: 'ADMIN', email: null, picture: null, imageServer: 0 };
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const svc = (tag: string) => ({ reachable: true, version: '0.9.2', imageTag: tag, buildTime: null });
const VERSION = {
  gateway: svc('sha-3aa678b'),
  skew: true,
  servers: [{ id: 'pep', name: 'pep', generation: 1, scenarioCode: 'scenario_1020', gameApi: svc('sha-3aa678b'), gameEngine: svc('sha-19c2e0d'), skew: true }],
};
const SCENARIOS = [{ code: 'scenario_1020', title: '군웅할거' }, { code: 'scenario_1030', title: '반동탁연합' }];
const SETTINGS = {
  msg: '', logWritable: true, scenarioCode: 'scenario_1020', scenarioText: '군웅할거', mapCode: 'han', year: 200, month: 3, turnPhaseText: '중순',
  status: '열림', startyear: 190, turnterm: 10, turnOptions: [5, 10], blockedWrites: [],
  editableFields: [
    { key: 'msg', label: '운영자 메세지', type: 'text', value: '' },
    { key: 'npcmode', label: 'NPC 빙의', type: 'select', value: 0, options: [{ value: '0', label: '불가' }] },
    { key: 'maxgeneral', label: '최대 장수', type: 'number', value: 30 },
    { key: 'maxnation', label: '최대 국가', type: 'number', value: 55 },
    { key: 'starttime', label: '시작 시간', type: 'text', value: '2026-09-30 20:00' },
    { key: 'turnterm', label: '턴 시간(분)', type: 'select', value: 10, options: [{ value: '5', label: '5분' }, { value: '10', label: '10분' }] },
  ],
};
const envField = (key: string, value: string | null, extra: Record<string, unknown> = {}) => ({ key, value, configured: true, writeOnly: false, masked: false, ...extra });
const SHARED_ENV = { scope: 'shared', configured: true, fields: { ADMIN_PASSWORD: envField('ADMIN_PASSWORD', null, { writeOnly: true, masked: true }), COOKIE_SECURE: envField('COOKIE_SECURE', 'true') } };
const SERVER_ENV = { scope: 'server', configured: true, fields: { IMAGE_TAG: envField('IMAGE_TAG', 'sha-3aa678b'), RESET_TURNTERM: envField('RESET_TURNTERM', '60'), RESET_SYNC: envField('RESET_SYNC', 'Y') } };

async function open(page: Page, baseURL: string | undefined) {
  const writes: { method: string; path: string; body: Record<string, unknown> }[] = [];
  await page.context().addCookies([{ name: 'sam_access', value: 'smoke', url: baseURL ?? 'http://127.0.0.1:3000' }]);
  await page.route('**/api/auth/me', (route) => route.fulfill(json({ user: ADMIN })));
  await page.route(/\/api\/game\/api\/admin\/game-settings/, (route) => {
    if (route.request().method() === 'PATCH') {
      writes.push({ method: 'PATCH', path: new URL(route.request().url()).pathname, body: route.request().postDataJSON() });
      return route.fulfill(json({ result: true, restartRequired: false }));
    }
    return route.fulfill(json(SETTINGS));
  });
  await page.route('**/api/proxy/admin/**', (route) => {
    const url = new URL(route.request().url());
    const method = route.request().method();
    if (method !== 'GET') {
      const body = (route.request().postDataJSON() ?? {}) as Record<string, unknown>;
      writes.push({ method, path: url.pathname, body });
      // 리셋은 바로 끝난 것으로 답한다(작업 ID 는 보낸 것을 되돌려 준다).
      return route.fulfill(json({
        ok: true, id: 'pep', operationId: body.operationId, operationStatus: 'succeeded', completed: true, retryable: false, resubmitRequired: false, publicMessage: '서버 리셋이 완료되었습니다.',
      }));
    }
    if (url.pathname.endsWith('/admin/version')) return route.fulfill(json(VERSION));
    if (url.pathname.endsWith('/admin/scenarios')) return route.fulfill(json({ scenarios: SCENARIOS }));
    if (url.pathname.endsWith('/admin/deploy/status')) {
      return route.fulfill(json({ configured: true, serverId: 'pep', currentTag: 'sha-3aa678b', availableTags: ['sha-4f01c77', 'sha-3aa678b'], latestTag: 'sha-4f01c77', promotionAvailable: true }));
    }
    if (url.pathname.endsWith('/admin/env/shared')) return route.fulfill(json(SHARED_ENV));
    if (url.pathname.endsWith('/admin/env/servers/pep')) return route.fulfill(json(SERVER_ENV));
    return route.fulfill(json({}, 404));
  });
  await page.goto('/admin');
  await page.getByRole('navigation', { name: '운영 콘솔' }).getByRole('button', { name: '서버', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1, name: '서버' })).toBeVisible();
  await expect(page.getByText('지금 sha-3aa678b')).toBeVisible();
  await expect(page.getByText('사람 장수 상한')).toBeVisible();
  return writes;
}

test.describe('P-G09 운영 콘솔 · 서버 — 데스크톱 · 모바일 같은 흐름', () => {
  test('그려진다: 경고 아이콘 · 지금 버전 사유 · 뺀 칸 없음 · 가로 넘침 없음 · 누를 영역 44', { tag: BOTH }, async ({ page, baseURL }) => {
    await open(page, baseURL);
    await expect(page.getByRole('alert').filter({ hasText: '버전 불일치' }).locator('svg[data-icon="alert"]')).toHaveCount(1);
    await expect(page.getByText(/⚠|입장 설정|운영자 메세지|NPC 빙의|최대 국가|RESET_SYNC/)).toHaveCount(0);
    await expect(page.getByRole('button', { name: '이 버전으로 배포' })).toHaveAttribute('data-reason', '지금 버전입니다');
    await expect(page.getByText('RESET_TURNTERM')).toBeVisible();
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'body')).toEqual([]);
    expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);
  });

  test('리셋: 대화상자(모바일 아래 시트) → 한 순 · 시나리오 고르기 → 본문은 넷 + 확인뿐(합성 응답)', { tag: BOTH }, async ({ page, baseURL }, info) => {
    const writes = await open(page, baseURL);
    await page.getByRole('button', { name: '리셋', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: 'pep 리셋' });
    await expect(dialog).toBeVisible();
    if (isMobile(info)) {
      const box = (await dialog.boundingBox())!;
      expect(Math.round(box.y + box.height)).toBe(page.viewportSize()!.height);
    }
    await expect(dialog).toContainText('이 서버를 아래 설정으로 처음부터 다시 시작합니다. 되돌릴 수 없습니다.');
    expect(await smallHitAreas(page, '[role="dialog"]')).toEqual([]);
    await dialog.getByRole('radio', { name: '10', exact: true }).click();
    await dialog.getByRole('radio', { name: /반동탁연합/ }).check();
    await dialog.getByRole('button', { name: '리셋 실행' }).click();
    await expect(page.getByText('서버 리셋이 완료되었습니다.')).toBeVisible();
    const reset = writes.filter((w) => w.path === '/api/proxy/admin/servers/pep/reset');
    expect(reset).toHaveLength(1);
    const { operationId, ...body } = reset[0].body;
    expect(typeof operationId).toBe('string');
    expect(body).toEqual({ generation: '1', turnTerm: '10', scenarioCode: 'scenario_1030', scenarioSeedEnabled: true, confirm: 'RESET pep' });
  });

  test('게임 설정: 저장은 바뀐 칸을 보여 준 뒤 고른 서버에만(합성 응답)', { tag: BOTH }, async ({ page, baseURL }) => {
    const writes = await open(page, baseURL);
    const panel = page.getByRole('region', { name: '게임 설정' });
    await panel.getByRole('spinbutton', { name: '사람 장수 상한' }).fill('24');
    await panel.getByRole('button', { name: '저장', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '게임 설정 저장' });
    await expect(dialog).toContainText('사람 장수 상한: 30 → 24');
    expect(writes).toEqual([]);
    expect(await smallHitAreas(page, '[role="dialog"]')).toEqual([]);
    await dialog.getByRole('button', { name: '저장', exact: true }).click();
    await expect(panel.getByText('저장했습니다.')).toBeVisible();
    expect(writes).toEqual([{ method: 'PATCH', path: '/api/game/api/admin/game-settings', body: { values: { maxgeneral: 24 } } }]);
  });
});
