import { expect, test } from '@playwright/test';
import { BOTH, press } from '../support/parity';

test('[court.offerPeace] 종전 제의: 군주가 교전 세력을 골라 정확한 대상 ID로 접수한다', { tag: [BOTH] }, async ({ page }, info) => {
    await page.route((url) => url.pathname === '/api/auth/me', (route) => route.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (route) => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), (route) => {
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
            diplomacyList: { 1: { 2: 0 } },
        } });
        if (path === '/commands/legacy-court-options' && url.searchParams.get('inputId') === 'court.offerPeace')
            return route.fulfill({ json: { inputId: 'court.offerPeace', available: true,
                choices: [{ label: '원소 (2)', arguments: { targetNationId: 2 }, available: true }] } });
        if (path === '/commands/court/offerPeace' && route.request().method() === 'POST')
            return route.fulfill({ status: 202, json: { status: 'AVAILABLE', requestId: 'peace-request' } });
        if (path === '/command/result/peace-request')
            return route.fulfill({ json: { status: 'RESOLVED', requestId: 'peace-request', ok: true,
                type: 'reservationAccepted', result: { commandKind: 'RESERVED_TURN' } } });
        return route.fulfill({ status: 503, json: {} });
    });
    await page.goto('/game/court/diplomacy', { waitUntil: 'domcontentloaded' });
    const list = page.getByRole('list', { name: '세력별 관계' });
    await expect(list).toBeVisible({ timeout: 60_000 });
    const row = list.locator('li[data-nation-id="2"]');
    const action = row.locator('[data-input-id="court.offerPeace"][data-input-status]');
    await expect(action).toHaveAttribute('data-input-status', 'AVAILABLE');
    const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/game/api/commands/court/offerPeace');
    await press(action, info);
    const request = await sent;
    expect(request.postDataJSON()).toEqual({ targetNationId: 2 });
});
