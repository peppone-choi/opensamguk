import { expect, test } from '@playwright/test';
import { BOTH, press } from '../support/parity';

// Response state belongs to the mock server; the observed POST body stays immutable.
let pending = false;

test('[work.reduce] 완공 성방을 골라 다음 순 감축을 접수하고 대기를 다시 읽는다', { tag: [BOTH] }, async ({ page }, info) => {
    const stock = { money: 1, grain: 2, iron: 3, timber: 4, horses: 5 };
    await page.route('**/api/auth/me', (route) => { pending = false; return route.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }); });
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
        '/api/works': { status: 'READY', counties: [{ countyId: 129, name: '양성현', commanderyName: '영천군',
            warehouse: stock, active: null, completed: [{ work: 'FORTIFICATION', label: '성방', edgeId: null }], startable: [],
            reducible: !pending, reduceBlocked: pending ? { code: 'WORK_IN_PROGRESS', reason: '감축을 접수했습니다.' } : null,
            reduction: pending ? { requestId: 'reduce-129', status: 'PENDING', requestedAt: { year: 200, month: 3, phase: 1 },
                resolvedAt: null, reason: null } : null }] },
        };
        await route.fulfill({ status: path in table ? 200 : 404, json: table[path] ?? {} });
    });
    await page.route('**/api/game/api/commands/work/reduce', async (route) => {
        expect(route.request().method()).toBe('POST');
        expect(route.request().postDataJSON()).toEqual({ countyId: 129, work: 'FORTIFICATION' });
        pending = true;
        await route.fulfill({ status: 202, json: { status: 'AVAILABLE', requestId: 'reduce-129' } });
    });
    await page.goto('/game/territory?view=work', { waitUntil: 'domcontentloaded' });
    await press(page.getByRole('button', { name: '성방 허물기' }), info);
    const sheet = page.getByRole('dialog', { name: '양성현 성방 허물기' });
    await expect(sheet).toContainText('다음 순 경계부터');
    await expect(sheet).toContainText('금 · 쌀 · 철 · 목재 · 군마 비용은 없습니다.');
    const submit = sheet.locator('[data-input-id="work.reduce"][data-input-status="AVAILABLE"]');
    const sent = page.waitForRequest((request) => request.method() === 'POST' &&
        new URL(request.url()).pathname === '/api/game/api/commands/work/reduce');
    await press(submit, info);
    expect((await sent).postDataJSON()).toEqual({ countyId: 129, work: 'FORTIFICATION' });
    await expect(sheet).toHaveCount(0);
    await expect(page.getByRole('button', { name: '성방 허물기' })).toHaveAttribute('data-input-status', 'BLOCKED');
    await expect(page.getByRole('main', { name: '게임 콘텐츠' })).toContainText('성방 감축을 접수했습니다 — 다음 순 경계부터 적용합니다.');
    await expect(page.getByRole('main', { name: '게임 콘텐츠' })).toContainText('현 창고');
});
