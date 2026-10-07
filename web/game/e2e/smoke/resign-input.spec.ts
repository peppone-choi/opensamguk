import { expect, test, type Route } from '@playwright/test';
import { BOTH, press } from '../support/parity';

function json(route: Route, status: number, body: unknown) {
    return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

test('[action.resign] 하야를 대상 없이 골라 예약한다', { tag: [BOTH] }, async ({ page }, info) => {
    await page.route((url) => url.pathname === '/api/auth/me', (route) => route.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (route) => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), (route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.slice('/api/game/api'.length);
        if (path === '/front-info') return json(route, 200, {
            result: true,
            global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
            general: { hasGeneral: true, generalId: 7, name: '검증용 장수', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
            nation: { id: 1, name: '검증용 세력', color: '#4f7fbf' }, city: null, recentRecord: {},
        });
        if (path === '/reserved-commands') return json(route, 200, { result: true, generalId: 7, slots: [] });
        if (path === '/const') return json(route, 200, { result: true, gameUnitConst: [] });
        if (path === '/map/preview') return json(route, 200, { serverName: 'qa', year: 200, month: 3, mapCode: 'qa', width: 1, height: 1, nations: [], cities: [] });
        if (path === '/commands/political-options' && route.request().method() === 'GET')
            return json(route, 200, [{ inputId: 'action.resign', available: true }]);
        if (path === '/command/action.resign' && route.request().method() === 'POST')
            return json(route, 202, { status: 'AVAILABLE', requestId: 'resign-request', turnIdx: 0 });
        if (path === '/command/result/resign-request')
            return json(route, 200, { status: 'RESOLVED', requestId: 'resign-request', ok: true, type: 'reservationAccepted', result: { commandKind: 'RESERVED_TURN' } });
        return json(route, 503, {});
    });
    await page.goto('/game?do=action.resign', { waitUntil: 'domcontentloaded' });
    const flow = page.getByTestId('command-flow');
    await expect(flow).toBeVisible({ timeout: 60_000 });
    const submit = flow.locator('[data-input-id="action.resign"][data-input-status]');
    await expect(submit).toHaveAttribute('data-input-status', 'AVAILABLE');
    const sent = page.waitForRequest((request) => request.method() === 'POST' &&
        new URL(request.url()).pathname === '/api/game/api/command/action.resign');
    await press(submit, info);
    const request = await sent;
    expect(request.postDataJSON()).toEqual({});
});
