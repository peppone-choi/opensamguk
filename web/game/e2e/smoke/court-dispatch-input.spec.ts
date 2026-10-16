import { expect, test, type Page, type Route, type TestInfo } from '@playwright/test';
import { BOTH, isMobile, press } from '../support/parity';

// 발령(court.dispatch) — 주공이 「새 발령」에서 내 부 사람 장수와 현을 골라 정확한 ID로 접수하고, 접수 뒤 내린 발령을 다시 읽는다.
// 서버가 거절하면 그 사유를 그대로 보이고, 주공이 아니면 점선 단추와 사유 시트로 막는다.
// 첫 사례만 [court.dispatch] 제목이다 — 입력 증거 게이트가 그 사례의 화면 보내기 · POST 본문을 정적으로 증명한다.
// 모든 응답은 대역이다: 실제 다음 개인 턴의 발령 처리 · 대상의 수락은 이 시험이 보지 않는다.
const DISPATCH_PATH = '/api/game/api/commands/court/dispatch';
const USER = { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } };
const NOT_LORD = '발령은 주공만 할 수 있습니다.';
const REFUSED = '직접 거느린 장수가 아닙니다.';

/** 화면이 읽는 게임 API 대역 — 하후돈(7, 조조 주공) · 내 부 사람 장수 순욱(21). 접수 뒤에는 내린 발령이 대기 1건이 된다. */
function gameRoutes(intake: 'accept' | 'reject' | 'notLord', seen: string[] = []) {
    let queued = false;
    return (route: Route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.slice('/api/game/api'.length);
        seen.push(`${route.request().method()} ${path}`);
        if (path === '/front-info') return route.fulfill({ json: {
            result: true,
            global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
            general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 12, permission: 4, showSecret: true },
            nation: { id: 1, name: '조조', color: '#4f7fbf', capitalCityId: 3 }, city: null, recentRecord: {},
        } });
        if (path === '/commands/dispatches') return route.fulfill({ json: { result: true, dispatches: queued ? [
            { dispatchId: 'dispatch-1', issuerId: 7, targetId: 21, countyId: 129, targetLabel: '순욱', countyLabel: '양성현',
                issuedAt: { year: 200, month: 3, phase: 1 }, dueAt: { year: 200, month: 3, phase: 2 }, status: 'PENDING' },
        ] : [] } });
        if (path === '/commands/dispatch-options' && intake === 'notLord')
            return route.fulfill({ json: { result: false, code: 'NOT_LORD', reason: NOT_LORD, targets: [], counties: [] } });
        if (path === '/commands/dispatch-options') return route.fulfill({ json: {
            result: true, targets: [{ generalId: 21, label: '순욱' }],
            // 사람을 고른 뒤의 현 후보 — 서버가 막은 현은 사유와 함께 온다.
            counties: url.searchParams.get('targetGeneralId') === '21' ? [
                { countyId: 129, label: '양성현', available: true },
                { countyId: 130, label: '허현', available: false, code: 'POST_OCCUPIED', reason: '이미 다른 장수가 맡은 현입니다.' },
            ] : [],
        } });
        if (path === '/commands/political-consent-options') return route.fulfill({ json: [] });
        if (path === '/commands/legacy-court-options')
            return route.fulfill({ json: { inputId: url.searchParams.get('inputId'), available: false, reason: '군주만 할 수 있습니다.', choices: [] } });
        if (path === '/retinue') return route.fulfill({ json: { status: 'READY', renown: 30, costSum: 0, overCapacity: false, people: [], units: [] } });
        if (path === '/map/preview') return route.fulfill({ json: { mapCode: 'qa', width: 1, height: 1, cities: [{ id: 3, name: '허현' }], nations: [] } });
        if (path === '/commands/court/dispatch' && route.request().method() === 'POST') {
            // 행위자는 본문이 아니라 호출자(generalId 쿼리)다 — 다른 장수로 보내면 화면이 실패를 보여야 한다.
            if (url.searchParams.get('generalId') !== '7') return route.fulfill({ status: 400, json: {} });
            if (intake === 'reject') return route.fulfill({ json: { status: 'BLOCKED', code: 'NOT_DIRECT_RETAINER', reason: REFUSED } });
            queued = true;
            return route.fulfill({ status: 202, json: { status: 'AVAILABLE', requestId: 'dispatch-request', inputId: 'court.dispatch' } });
        }
        // 이 시험이 보지 않는 조회(상사 선택지 등)는 없는 것으로 둔다.
        return route.fulfill({ status: 404, json: {} });
    };
}

async function openCourt(page: Page, intake: 'accept' | 'reject' | 'notLord', seen: string[]) {
    await page.route((url) => url.pathname === '/api/auth/me', (route) => route.fulfill({ json: USER }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (route) => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), gameRoutes(intake, seen));
    await page.goto('/game/court', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '조정' })).toBeVisible({ timeout: 60_000 });
}

/** 모바일은 조정 결정 목록의 「발령」이 내린 발령 시트를 연다. 데스크톱은 발령 칸이 늘 보인다. */
async function issuedList(page: Page, info: TestInfo) {
    if (isMobile(info)) {
        await press(page.getByRole('list', { name: '조정 결정' }).locator('button[data-input-id="court.dispatch"]'), info);
        return page.getByRole('dialog', { name: '발령' });
    }
    return page.getByRole('region', { name: '발령' });
}

test('[court.dispatch] 새 발령: 사람과 현을 골라 정확한 ID로 접수한다', { tag: [BOTH] }, async ({ page }, info) => {
    await page.route((url) => url.pathname === '/api/auth/me', (route) => route.fulfill({ json: USER }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (route) => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), gameRoutes('accept'));
    await page.goto('/game/court', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '조정' })).toBeVisible({ timeout: 60_000 });
    // 첫 발령 조작: 데스크톱은 발령 칸의 「새 발령」(바로 새 발령 시트), 모바일은 조정 결정 목록의 「발령」(내린 발령 시트).
    await press(page.locator('[data-input-id="court.dispatch"]').first(), info);
    // 열린 시트의 마지막 발령 단추: 모바일은 「새 발령」, 데스크톱은 사람을 고르기 전이라 막힌 「이 현으로 발령」(사유만 연다).
    await press(page.getByRole('dialog').getByRole('button').filter({ hasText: '발령' }).last(), info);
    const sheet = page.getByRole('dialog', { name: '새 발령' });
    await expect(sheet.locator('section[data-input-id="court.dispatch"]')).toBeVisible();
    await press(sheet.getByRole('option', { name: /순욱/ }), info);
    await expect(sheet.getByRole('option', { name: /순욱/ })).toHaveAttribute('aria-selected', 'true');
    await expect(sheet.getByRole('option', { name: /허현/ })).toContainText('이미 다른 장수가 맡은 현입니다.');
    await press(sheet.getByRole('option', { name: /양성현/ }), info);
    const submit = sheet.locator('section[data-input-id="court.dispatch"]').getByRole('button', { name: '이 현으로 발령', exact: true });
    const sent = page.waitForRequest((request) => request.method() === 'POST' &&
        new URL(request.url()).pathname === '/api/game/api/commands/court/dispatch');
    await press(submit, info);
    expect((await sent).postDataJSON()).toEqual({ targetGeneralId: 21, countyId: 129 });
    await expect(sheet).toHaveCount(0);
    await expect(page.getByRole('main', { name: '게임 콘텐츠' }).getByRole('status').filter({ hasText: '발령을 접수했습니다' }))
        .toHaveText('발령을 접수했습니다 — 주공의 다음 개인 턴에 처리합니다.');
});

test('발령 [court.dispatch] 접수 뒤 다시 읽기: 빈 내린 발령이 대기 1건으로 바뀐다', { tag: [BOTH] }, async ({ page }, info) => {
    const seen: string[] = [];
    await openCourt(page, 'accept', seen);
    const before = await issuedList(page, info);
    await expect(before).toContainText('내린 발령이 없습니다.');
    await press(before.getByRole('button', { name: '새 발령', exact: true }), info);
    const sheet = page.getByRole('dialog', { name: '새 발령' });
    await press(sheet.getByRole('option', { name: /순욱/ }), info);
    await press(sheet.getByRole('option', { name: /양성현/ }), info);
    const sent = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === DISPATCH_PATH);
    await press(sheet.getByRole('button', { name: '이 현으로 발령', exact: true }), info);
    expect((await sent).postDataJSON()).toEqual({ targetGeneralId: 21, countyId: 129 });
    await expect(sheet).toHaveCount(0);
    const after = await issuedList(page, info);
    const rows = after.getByRole('list', { name: '내린 발령' });
    await expect(rows).toContainText('순욱 → 양성현');
    await expect(rows).toContainText('응답 대기');
    await expect(after).not.toContainText('내린 발령이 없습니다.');
    // 접수 전 한 번 · 접수 뒤 다시 한 번 읽었다.
    expect(seen.filter((call) => call === 'GET /commands/dispatches').length).toBeGreaterThanOrEqual(2);
    expect(seen.filter((call) => call === 'POST /commands/court/dispatch')).toHaveLength(1);
});

test('발령 [court.dispatch] 거절: 서버 BLOCKED 사유를 그대로 보이고 대기 발령을 만들지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
    const seen: string[] = [];
    await openCourt(page, 'reject', seen);
    const column = await issuedList(page, info);
    await press(column.getByRole('button', { name: '새 발령', exact: true }), info);
    const sheet = page.getByRole('dialog', { name: '새 발령' });
    await press(sheet.getByRole('option', { name: /순욱/ }), info);
    await press(sheet.getByRole('option', { name: /양성현/ }), info);
    const rejected = page.waitForResponse((response) => response.request().method() === 'POST' && new URL(response.url()).pathname === DISPATCH_PATH);
    await press(sheet.getByRole('button', { name: '이 현으로 발령', exact: true }), info);
    expect((await rejected).request().postDataJSON()).toEqual({ targetGeneralId: 21, countyId: 129 });
    // 시트가 열린 동안 본문은 Modal 이 aria-hidden 으로 가린다 — 접근성 이름이 아니라 글로 찾는다.
    await expect(page.getByText(REFUSED, { exact: true })).toBeVisible();
    await expect(page.getByText('발령을 접수했습니다')).toHaveCount(0);
    await expect(sheet).toBeVisible();
    await press(sheet.getByRole('button', { name: '그만두기', exact: true }), info);
    await expect(sheet).toHaveCount(0);
    const after = await issuedList(page, info);
    await expect(after).toContainText('내린 발령이 없습니다.');
    expect(seen.filter((call) => call === 'POST /commands/court/dispatch')).toHaveLength(1);
});

test('발령 [court.dispatch] 주공 아님: 점선 단추와 사유 시트로 막고 보내지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
    const seen: string[] = [];
    await openCourt(page, 'notLord', seen);
    const entry = page.locator('button[data-input-id="court.dispatch"]');
    await expect(entry).toHaveAttribute('aria-disabled', 'true');
    await expect(entry).toHaveAttribute('data-input-status', 'BLOCKED');
    // 보이는 사유 꼬리표도 점선이다 — 데스크톱은 단추 옆(os-ia__why), 모바일은 목록 항목 안(os-opt__why).
    const why = isMobile(info) ? entry.locator('.os-opt__why') : page.getByRole('region', { name: '발령' }).locator('.os-ia__why');
    await expect(why).toHaveText(NOT_LORD);
    await expect(why).toHaveCSS('border-top-style', 'dashed');
    await press(entry, info);
    await expect(page.getByRole('dialog').filter({ hasText: NOT_LORD })).toBeVisible();
    await expect(page.getByRole('dialog', { name: '새 발령' })).toHaveCount(0);
    expect(seen.filter((call) => call.startsWith('POST /commands/court/dispatch'))).toEqual([]);
});
