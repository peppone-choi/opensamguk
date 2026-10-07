import { expect, test, type Route } from '@playwright/test';
import { BOTH, press } from '../support/parity';

const EQUIPMENT_ID = 'equipment:che_명마_01_노기';
const gameRoute = (side: 'BUY' | 'SELL') => {
    const slots = new Map<number, { turnIdx: number; action: string; brief: string; arg: Record<string, unknown> }>();
    const json = (route: Route, body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    return async (route: Route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.slice('/api/game/api'.length);
        if (path === '/front-info') return json(route, {
            result: true,
            global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
            general: { hasGeneral: true, generalId: 7, name: '검증용 장수', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
            nation: { id: 1, name: '검증용 세력', color: '#4f7fbf' }, city: null, recentRecord: {},
        });
        if (path === '/reserved-commands') return json(route, { result: true, generalId: 7, slots: [...slots.values()] });
        if (path === '/const') return json(route, { result: true, gameUnitConst: [] });
        if (path === '/map/preview') return json(route, { cities: [], nations: [] });
        if (path === '/commands/legacy-direct-options') return json(route, {
            inputId: 'action.tradeEquipment', available: true, equipmentNames: { [EQUIPMENT_ID]: '노기(+1)' },
            choices: [{ label: `노기(+1) ${side === 'BUY' ? '매입' : '매각'} · 전 1000`, available: true, arguments: { equipmentId: EQUIPMENT_ID, side } }],
        });
        if (path === '/command/action.tradeEquipment' && route.request().method() === 'POST') {
            slots.set(0, { turnIdx: Number(url.searchParams.get('turnIdx')), action: 'action.tradeEquipment', brief: '장비매매', arg: route.request().postDataJSON() });
            return json(route, { status: 'AVAILABLE', requestId: 'equipment-r1', turnIdx: 0 }, 202);
        }
        if (path === '/command/result/equipment-r1') return json(route, { status: 'RESOLVED', requestId: 'equipment-r1', ok: true, type: 'reservationAccepted', result: { commandKind: 'RESERVED_TURN' } });
        return json(route, {}, 503);
    };
};

for (const c of [
    { side: 'BUY', label: '노기(+1) 매입', sentence: '「노기(+1) 매입」 — 01순에 예약했습니다.' },
    { side: 'SELL', label: '노기(+1) 매각', sentence: '「노기(+1) 매각」 — 01순에 예약했습니다.' },
] as const) {
    test(`[action.tradeEquipment] ${c.side}: 정본 장비 배타 인자와 실제 이름 예약 readback`, { tag: [BOTH] }, async ({ page }, info) => {
        await page.route(url => url.pathname === '/api/auth/me', route => route.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
        await page.route(url => url.pathname.startsWith('/api/server-basic-info/'), route => route.fulfill({ status: 404, json: {} }));
        await page.route(url => url.pathname.startsWith('/api/game/'), gameRoute(c.side));
        await page.goto('/game?do=action.tradeEquipment', { waitUntil: 'domcontentloaded' });
        const flow = page.getByTestId('command-flow');
        await expect(flow).toBeVisible({ timeout: 60_000 });
        await press(flow.getByRole('option', { name: c.label }), info);
        const submit = flow.locator('[data-input-id="action.tradeEquipment"][data-input-status]');
        await expect(submit).toHaveAttribute('data-input-status', 'AVAILABLE');
        const sent = page.waitForRequest(r => r.method() === 'POST' && new URL(r.url()).pathname === '/api/game/api/command/action.tradeEquipment');
        await press(submit, info);
        const request = await sent;
        expect(request.postDataJSON()).toEqual({ equipmentId: EQUIPMENT_ID, side: c.side });
        await expect(flow.getByText(c.sentence)).toBeVisible();
    });
}
