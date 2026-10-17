import { expect, test } from '@playwright/test';
import { BOTH, press } from '../support/parity';

// Response state belongs to the mock server; the observed POST body stays immutable.
let started = false;
let startPosts = 0;

test('[work.start] 새 공사에서 수리를 골라 접수하고 진행 중 공사를 다시 읽는다', { tag: [BOTH] }, async ({ page }, info) => {
    const stock = { money: 1, grain: 2, iron: 3, timber: 4, horses: 5 };
    const cost = { money: 100, grain: 0, iron: 0, timber: 0, horses: 0 };
    await page.route('**/api/auth/me', (route) => { started = false; return route.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }); });
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (route) => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/api/'), async (route) => {
        const path = new URL(route.request().url()).pathname.replace(/^\/api\/game/, '');
        const table: Record<string, unknown> = {
        '/api/front-info': { result: true,
            global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60,
                scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
            general: { hasGeneral: true, generalId: 7, name: '검증용 장수', nationId: 1, officerLevel: 12, permission: 0, showSecret: false },
            nation: { id: 1, name: '검증용 세력', color: '#4f7fbf' }, city: null, recentRecord: {} },
        '/api/posts': { status: 'READY', cards: [], posts: [] },
        '/api/policies': { status: 'READY', counties: [], corps: [], countyOptions: [], corpsOptions: [] },
        '/api/retinue': { status: 'READY', people: [], units: [] },
        '/api/warehouses': { status: 'READY', warehouses: [] },
        '/api/road-forts': { status: 'READY', roadMode: true, forts: [], gates: [] },
        '/api/works': { status: 'READY', counties: [{ countyId: 129, provinceId: null, provinceIds: [], name: '양성현', commanderyName: '영천군',
            warehouse: stock, completed: [], reducible: false, reduceBlocked: null, reduction: null,
            active: started ? { work: 'IRRIGATION', label: '수리', percent: 0, remainingPhases: 1, remainingCost: cost,
                stopReasonText: null, startsAtNextBoundary: true, stopReason: null } : null,
            startable: [
                { work: 'IRRIGATION', label: '수리', available: !started, cost, estimatedPhases: 1,
                    blocked: started ? { code: 'WORK_IN_PROGRESS', reason: '이 현에는 진행 중인 공사가 있습니다.' } : null },
                { work: 'BARRACKS', label: '병영', available: false, cost, estimatedPhases: 3,
                    blocked: { code: 'WAREHOUSE_NOT_READY', reason: '현의 창고를 확인할 수 없습니다.' } },
            ] }] },
        };
        await route.fulfill({ status: path in table ? 200 : 404, json: table[path] ?? {} });
    });
    await page.route((url) => url.pathname === '/api/game/api/commands/work/start', async (route) => {
        expect(route.request().method()).toBe('POST');
        expect(new URL(route.request().url()).searchParams.get('generalId')).toBe('7');
        expect(route.request().postDataJSON()).toEqual({ countyId: 129, work: 'IRRIGATION' });
        started = true;
        await route.fulfill({ status: 202, json: { status: 'AVAILABLE', requestId: 'work-129', inputId: 'work.start' } });
    });
    await page.goto('/game/territory?view=work', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('main', { name: '게임 콘텐츠' })).toContainText('진행 중인 공사 없음');
    await press(page.getByRole('button', { name: '새 공사', exact: true }), info);
    const sheet = page.getByRole('dialog', { name: '양성현 공사' });
    const blockedChoice = sheet.getByRole('option', { name: /^병영/ });
    await expect(blockedChoice).toHaveAttribute('aria-disabled', 'true');
    await expect(blockedChoice).toContainText('현의 창고를 확인할 수 없습니다.');
    await expect(blockedChoice.locator('.os-opt__why')).toHaveCSS('border-top-style', 'dashed');
    await press(sheet.getByRole('option', { name: /^수리/ }), info);
    await expect(sheet.getByRole('option', { name: /^수리/ })).toHaveAttribute('aria-selected', 'true');
    const submit = sheet.locator('section[data-input-id="work.start"]').getByRole('button', { name: '이 공사로', exact: true });
    const sent = page.waitForRequest((request) => request.method() === 'POST' &&
        new URL(request.url()).pathname === '/api/game/api/commands/work/start');
    await press(submit, info);
    expect((await sent).postDataJSON()).toEqual({ countyId: 129, work: 'IRRIGATION' });
    await expect(sheet).toHaveCount(0);
    await expect(page.getByRole('main', { name: '게임 콘텐츠' })).toContainText('공사를 접수했습니다 — 다음 순 경계부터 진척합니다.');
    await expect(page.getByRole('meter', { name: '양성현 수리 진척' })).toHaveAttribute('aria-valuenow', '0');
    await expect(page.getByRole('main', { name: '게임 콘텐츠' })).toContainText('남은 1순');
    await expect(page.getByRole('main', { name: '게임 콘텐츠' })).toContainText('다음 순 경계부터');
    await expect(page.getByText('진행 중인 공사 없음')).toHaveCount(0);
});

/** 진행 중 공사가 있고 창고가 없는 현 — 서버가 준 막힘 사유만 쓴다. */
const BUSY_WORKS = { status: 'READY', counties: [{ countyId: 129, provinceId: null, provinceIds: [], name: '양성현', commanderyName: '영천군',
    warehouse: null, completed: [], reducible: false, reduceBlocked: null, reduction: null,
    active: { work: 'MARKET_WATERWAY', label: '시장 수로', percent: 40, remainingPhases: 3, remainingCost: { money: 50, grain: 0, iron: 0, timber: 0, horses: 0 },
        stopReasonText: null, startsAtNextBoundary: false, stopReason: null },
    startable: [
        { work: 'IRRIGATION', label: '수리', available: false, cost: { money: 100, grain: 0, iron: 0, timber: 0, horses: 0 }, estimatedPhases: 1,
            blocked: { code: 'WORK_IN_PROGRESS', reason: '이 현에는 진행 중인 공사가 있습니다.' } },
        { work: 'BARRACKS', label: '병영', available: false, cost: { money: 100, grain: 0, iron: 0, timber: 0, horses: 0 }, estimatedPhases: 3,
            blocked: { code: 'WAREHOUSE_NOT_READY', reason: '현의 창고를 확인할 수 없습니다.' } },
    ] }] };

const FRONT = { result: true,
    global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60,
        scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
    general: { hasGeneral: true, generalId: 7, name: '검증용 장수', nationId: 1, officerLevel: 12, permission: 0, showSecret: false },
    nation: { id: 1, name: '검증용 세력', color: '#4f7fbf' }, city: null, recentRecord: {} };

test('공사 시작 불가 — 진행 중 공사 · 창고 미비 사유를 점선과 사유 시트로 보이고 보내지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
    await page.route('**/api/auth/me', (route) => { startPosts = 0; return route.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }); });
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (route) => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/api/'), async (route) => {
        const path = new URL(route.request().url()).pathname.replace(/^\/api\/game/, '');
        const reads: Record<string, unknown> = {
            '/api/front-info': FRONT,
            '/api/posts': { status: 'READY', cards: [], posts: [] },
            '/api/policies': { status: 'READY', counties: [], corps: [], countyOptions: [], corpsOptions: [] },
            '/api/retinue': { status: 'READY', people: [], units: [] },
            '/api/warehouses': { status: 'READY', warehouses: [] },
            '/api/road-forts': { status: 'READY', roadMode: true, forts: [], gates: [] },
            '/api/works': BUSY_WORKS,
        };
        await route.fulfill({ status: path in reads ? 200 : 404, json: reads[path] ?? {} });
    });
    await page.route((url) => url.pathname === '/api/game/api/commands/work/start', async (route) => {
        startPosts += 1;
        await route.fulfill({ status: 202, json: { status: 'AVAILABLE', requestId: 'unexpected' } });
    });
    await page.goto('/game/territory?view=work', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('meter', { name: '양성현 시장 수로 진척' })).toHaveAttribute('aria-valuenow', '40');
    await press(page.getByRole('button', { name: '새 공사', exact: true }), info);
    const busySheet = page.getByRole('dialog', { name: '양성현 공사' });
    for (const [label, reason] of [['수리', '이 현에는 진행 중인 공사가 있습니다.'], ['병영', '현의 창고를 확인할 수 없습니다.']]) {
        const choice = busySheet.getByRole('option', { name: new RegExp(`^${label}`) });
        await expect(choice).toHaveAttribute('aria-disabled', 'true');
        await expect(choice).toHaveAttribute('aria-selected', 'false');
        await expect(choice.locator('.os-opt__why')).toHaveText(reason);
        await expect(choice.locator('.os-opt__why')).toHaveCSS('border-top-style', 'dashed');
    }
    // 막힌 공사를 누르면 사유 시트가 열리고 고르지 않는다.
    await press(busySheet.getByRole('option', { name: /^수리/ }), info);
    const why = page.getByRole('dialog', { name: '수리 — 지금 시작할 수 없습니다' });
    await expect(why).toContainText('이 현에는 진행 중인 공사가 있습니다.');
    await press(why.getByRole('button', { name: '닫기', exact: true }), info);
    await expect(busySheet.getByRole('option', { name: /^수리/ })).toHaveAttribute('aria-selected', 'false');
    const blockedSubmit = busySheet.getByRole('button', { name: '이 공사로', exact: true });
    await expect(blockedSubmit).toHaveAttribute('aria-disabled', 'true');
    await press(blockedSubmit, info);
    await expect(page.getByRole('tooltip').filter({ hasText: '공사를 고르세요.' })).toBeVisible();
    expect(startPosts).toBe(0);
});

test('공사 시작 거절 — 서버 BLOCKED 사유를 그대로 보이고 진행 중 공사를 만들지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
    const idle = { ...BUSY_WORKS.counties[0], warehouse: { money: 1, grain: 2, iron: 3, timber: 4, horses: 5 }, active: null,
        startable: [{ work: 'IRRIGATION', label: '수리', available: true, blocked: null,
            cost: { money: 100, grain: 0, iron: 0, timber: 0, horses: 0 }, estimatedPhases: 1 }] };
    await page.route('**/api/auth/me', (route) => route.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (route) => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/api/'), async (route) => {
        const path = new URL(route.request().url()).pathname.replace(/^\/api\/game/, '');
        const reads: Record<string, unknown> = {
            '/api/front-info': FRONT,
            '/api/posts': { status: 'READY', cards: [], posts: [] },
            '/api/policies': { status: 'READY', counties: [], corps: [], countyOptions: [], corpsOptions: [] },
            '/api/retinue': { status: 'READY', people: [], units: [] },
            '/api/warehouses': { status: 'READY', warehouses: [] },
            '/api/road-forts': { status: 'READY', roadMode: true, forts: [], gates: [] },
            '/api/works': { status: 'READY', counties: [idle] },
        };
        await route.fulfill({ status: path in reads ? 200 : 404, json: reads[path] ?? {} });
    });
    await page.route((url) => url.pathname === '/api/game/api/commands/work/start', (route) => route.fulfill({
        status: 200, json: { status: 'BLOCKED', code: 'WORK_IN_PROGRESS', reason: '이 현에는 진행 중인 공사가 있습니다.' } }));
    await page.goto('/game/territory?view=work', { waitUntil: 'domcontentloaded' });
    await press(page.getByRole('button', { name: '새 공사', exact: true }), info);
    const rejectSheet = page.getByRole('dialog', { name: '양성현 공사' });
    await press(rejectSheet.getByRole('option', { name: /^수리/ }), info);
    const rejected = page.waitForResponse((response) => response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/api/game/api/commands/work/start');
    await press(rejectSheet.getByRole('button', { name: '이 공사로', exact: true }), info);
    expect((await rejected).request().postDataJSON()).toEqual({ countyId: 129, work: 'IRRIGATION' });
    const main = page.getByRole('main', { name: '게임 콘텐츠' });
    await expect(main.getByRole('status').filter({ hasText: '이 현에는 진행 중인 공사가 있습니다.' })).toBeVisible();
    await expect(main).not.toContainText('공사를 접수했습니다');
    await expect(rejectSheet).toBeVisible();
    await press(rejectSheet.getByRole('button', { name: '그만두기', exact: true }), info);
    await expect(rejectSheet).toHaveCount(0);
    await expect(main).toContainText('진행 중인 공사 없음');
    await expect(page.getByRole('meter')).toHaveCount(0);
});
