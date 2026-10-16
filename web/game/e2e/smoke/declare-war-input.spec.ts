import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, press } from '../support/parity';

// 선전포고(court.declareWar) — 군주가 서버 옵션으로 고른 세력에 위험 확인을 거쳐 정확한 대상 ID로 접수한다.
// 접수(202) 뒤 결과를 다시 읽어 알리고, 그만두면 보내지 않고, 서버가 거절하면 그 code · reason 그대로 BLOCKED로 막는다.
// 첫 사례만 [court.declareWar] 제목이다 — 입력 증거 게이트가 그 사례의 화면 보내기 · POST 본문을 정적으로 증명한다.
const DECLARE_WAR_PATH = '/api/game/api/commands/court/declareWar';

/** 화면이 읽는 게임 API 대역 — 조조(1, 군주 하후돈)와 원소(2) 관계 없음. 제출 응답은 사례마다 다르다. */
function gameRoutes(intake: 'accept' | 'reject') {
    return (route: Route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.slice('/api/game/api'.length);
        if (path === '/front-info') return route.fulfill({ json: {
            result: true,
            global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
            general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 12, permission: 4, showSecret: true },
            nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
        } });
        if (path === '/reserved-commands') return route.fulfill({ json: { result: true, generalId: 7, slots: [] } });
        if (path === '/const') return route.fulfill({ json: { result: true, gameUnitConst: [] } });
        if (path === '/map/preview') return route.fulfill({ json: { serverName: 'qa', year: 200, month: 3,
            mapCode: 'qa', width: 1, height: 1, nations: [], cities: [] } });
        if (path === '/diplomacy/conflict') return route.fulfill({ json: {
            result: true, conflict: [], myNationID: 1,
            nations: [
                { nation: 1, name: '조조', color: '#4f7fbf', type: '', level: 1, capital: 0, gennum: 1, cities: [], power: 0 },
                { nation: 2, name: '원소', color: '#b04a3c', type: '', level: 1, capital: 0, gennum: 1, cities: [], power: 0 },
            ],
            diplomacyList: { 1: { 2: 2 } },
        } });
        if (path === '/commands/legacy-court-options' && url.searchParams.get('inputId') === 'court.declareWar')
            return route.fulfill({ json: { inputId: 'court.declareWar', available: true,
                choices: [{ label: '원소 (2)', arguments: { targetNationId: 2 }, available: true }] } });
        if (path === '/commands/legacy-court-options')
            return route.fulfill({ json: { inputId: url.searchParams.get('inputId'), available: false, choices: [] } });
        if (path === '/commands/court/declareWar' && route.request().method() === 'POST') {
            if (intake === 'reject') return route.fulfill({ json: { status: 'BLOCKED', code: 'ALREADY_AT_WAR', reason: '이미 교전 중이거나 선포한 세력입니다.' } });
            return route.fulfill({ status: 202, json: { status: 'AVAILABLE', requestId: 'declare-war-request' } });
        }
        if (path === '/command/result/declare-war-request')
            return route.fulfill({ json: { status: 'RESOLVED', requestId: 'declare-war-request', ok: true,
                type: 'reservationAccepted', result: { commandKind: 'RESERVED_TURN' } } });
        return route.fulfill({ status: 503, json: {} });
    };
}

async function openDiplomacy(page: Page, intake: 'accept' | 'reject') {
    await page.route((url) => url.pathname === '/api/auth/me', (route) => route.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (route) => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), gameRoutes(intake));
    await page.goto('/game/court/diplomacy', { waitUntil: 'domcontentloaded' });
    const list = page.getByRole('list', { name: '세력별 관계' });
    await expect(list).toBeVisible({ timeout: 60_000 });
    const action = list.locator('li[data-nation-id="2"]').locator('[data-input-id="court.declareWar"][data-input-status]');
    await expect(action).toHaveAttribute('data-input-status', 'AVAILABLE');
    return action;
}

/** 이 화면에서 나간 선전포고 POST 수 — 그만두기 · 거절 뒤 추가 전송이 없음을 센다. */
function declareWarPosts(page: Page): string[] {
    const sent: string[] = [];
    page.on('request', (r) => {
        if (r.method() === 'POST' && new URL(r.url()).pathname === DECLARE_WAR_PATH) sent.push(r.postData() ?? '');
    });
    return sent;
}

test('[court.declareWar] 선전포고: 군주가 관계 없는 세력을 골라 위험 확인 뒤 정확한 대상 ID로 접수하고 결과를 알린다', { tag: [BOTH] }, async ({ page }, info) => {
    await page.route((url) => url.pathname === '/api/auth/me', (route) => route.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (route) => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), gameRoutes('accept'));
    await page.goto('/game/court/diplomacy', { waitUntil: 'domcontentloaded' });
    const list = page.getByRole('list', { name: '세력별 관계' });
    await expect(list).toBeVisible({ timeout: 60_000 });
    const row = list.locator('li[data-nation-id="2"]');
    const action = row.locator('[data-input-id="court.declareWar"][data-input-status]');
    await expect(action).toHaveAttribute('data-input-status', 'AVAILABLE');
    const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/game/api/commands/court/declareWar');
    await press(action, info);
    const dialog = page.getByRole('dialog', { name: '선전포고합니다' });
    await expect(dialog).toContainText('원소에 선전포고합니다. 게임 시간 24개월 뒤 교전이 시작됩니다.');
    const confirm = dialog.getByRole('button', { name: '선전포고' });
    await press(confirm, info);
    const request = await sent;
    expect(request.postDataJSON()).toEqual({ targetNationId: 2 });
    await expect(page.getByRole('status').filter({ hasText: '선전포고를 예약했습니다' })).toHaveText('선전포고를 예약했습니다. 다음 개인 턴에 선포합니다.');
    await expect(dialog).toBeHidden();
});

test('선전포고 [court.declareWar] 그만두기: 위험 확인에서 그만두면 POST를 보내지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
    const sent = declareWarPosts(page);
    const action = await openDiplomacy(page, 'accept');
    await press(action, info);
    const dialog = page.getByRole('dialog', { name: '선전포고합니다' });
    await expect(dialog).toBeVisible();
    await press(dialog.getByRole('button', { name: '그만두기' }), info);
    await expect(dialog).toBeHidden();
    await expect(action).toHaveAttribute('data-input-status', 'AVAILABLE');
    expect(sent).toEqual([]);
});

test('선전포고 [court.declareWar] 거절: 서버 code · reason 그대로 BLOCKED로 막고 다시 눌러도 더 보내지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
    const sent = declareWarPosts(page);
    const action = await openDiplomacy(page, 'reject');
    await press(action, info);
    await press(page.getByRole('dialog', { name: '선전포고합니다' }).getByRole('button', { name: '선전포고' }), info);
    await expect(page.getByRole('alert').filter({ hasText: '이미 교전 중이거나' })).toHaveText('이미 교전 중이거나 선포한 세력입니다.');
    await expect(action).toHaveAttribute('data-input-status', 'BLOCKED');
    const row = page.getByRole('list', { name: '세력별 관계' }).locator('li[data-nation-id="2"]');
    await expect(row).toContainText('이미 교전 중이거나 선포한 세력입니다.');
    expect(sent).toEqual([JSON.stringify({ targetNationId: 2 })]);
    await press(action, info);
    await expect(page.getByRole('dialog', { name: '선전포고합니다' })).toBeHidden();
    expect(sent).toHaveLength(1);
});
