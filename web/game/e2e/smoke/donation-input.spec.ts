import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, press } from '../support/parity';

async function serve(page: Page, blocked = false) {
    let stored: Record<string, unknown> | null = null;
    const commands: unknown[] = [];
    const json = (route: Route, status: number, body: unknown) =>
        route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', route => route.fulfill({ json: { user: { id: 42, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), route => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), route => {
        const url = new URL(route.request().url());
        const path = url.pathname.slice('/api/game/api'.length);
        if (path === '/front-info') return json(route, 200, {
            result: true,
            global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
            general: { hasGeneral: true, generalId: 7, name: '무영토 기부자', nationId: 2, officerLevel: 1, permission: 0, showSecret: false },
            nation: { id: 2, name: '기부자 세력', color: '#4f7fbf' }, city: null, recentRecord: {},
        });
        if (path === '/reserved-commands') return json(route, 200, { result: true, generalId: 7,
            slots: stored ? [{ turnIdx: 0, action: 'action.donate', brief: '', arg: stored, revision: '00000000-0000-4000-8000-0000000000e0' }] : [] });
        if (path === '/const') return json(route, 200, { result: true, gameUnitConst: [] });
        if (path === '/map/preview') return json(route, 200, { serverName: 'qa', year: 200, month: 3, mapCode: 'qa', width: 1, height: 1, nations: [], cities: [] });
        if (path === '/commands/donate-options') return json(route, 200, {
            inputId: 'action.donate', available: !blocked, code: blocked ? 'STATE_UNAVAILABLE' : null,
            reason: blocked ? '현재 현 창고를 확인할 수 없습니다.' : null,
            resources: [{ resource: 'MONEY', available: !blocked, maxAmount: 1000 },
                { resource: 'GRAIN', available: !blocked, maxAmount: 2000 }], targets: [],
            donationRecipient: blocked ? null : { countyId: 11, countyName: '수령현', nationId: 1, nationName: '현 소유국' },
        });
        if (path === '/command/action.donate' && route.request().method() === 'POST') {
            stored = route.request().postDataJSON(); commands.push(stored);
            return json(route, 202, { status: 'AVAILABLE', requestId: 'donation-request', turnIdx: 0 });
        }
        if (path === '/command/result/donation-request') return json(route, 200, {
            status: 'RESOLVED', requestId: 'donation-request', ok: true, type: 'reservationAccepted',
            result: { commandKind: 'RESERVED_TURN' },
        });
        return json(route, 503, {});
    });
    return commands;
}

test('[action.donate] 금 수량을 골라 현재 현 소유국 창고에 예약한다', { tag: [BOTH] }, async ({ page }, info) => {
    const json = (route: Route, status: number, body: unknown) =>
        route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', route => route.fulfill({ json: { user: { id: 42, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), route => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), (() => {
        let stored: Record<string, unknown> | null = null;
        return (route: Route) => {
            const url = new URL(route.request().url());
            const path = url.pathname.slice('/api/game/api'.length);
            if (path === '/front-info') return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: 7, name: '무영토 기부자', nationId: 2, officerLevel: 1, permission: 0, showSecret: false },
                nation: { id: 2, name: '기부자 세력', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
            if (path === '/reserved-commands') return json(route, 200, { result: true, generalId: 7,
                slots: stored ? [{ turnIdx: 0, action: 'action.donate', brief: '', arg: stored, revision: '00000000-0000-4000-8000-0000000000e0' }] : [] });
            if (path === '/const') return json(route, 200, { result: true, gameUnitConst: [] });
            if (path === '/map/preview') return json(route, 200, { serverName: 'qa', year: 200, month: 3, mapCode: 'qa', width: 1, height: 1, nations: [], cities: [] });
            if (path === '/commands/donate-options') return json(route, 200, {
                inputId: 'action.donate', available: true, code: null,
                reason: null,
                resources: [{ resource: 'MONEY', available: true, maxAmount: 1000 },
                    { resource: 'GRAIN', available: true, maxAmount: 2000 }], targets: [],
                donationRecipient: { countyId: 11, countyName: '수령현', nationId: 1, nationName: '현 소유국' },
            });
            if (path === '/command/action.donate' && route.request().method() === 'POST') {
                stored = route.request().postDataJSON();
                return json(route, 202, { status: 'AVAILABLE', requestId: 'donation-request', turnIdx: 0 });
            }
            if (path === '/command/result/donation-request') return json(route, 200, {
                status: 'RESOLVED', requestId: 'donation-request', ok: true, type: 'reservationAccepted',
                result: { commandKind: 'RESERVED_TURN' },
            });
            return json(route, 503, {});
        };
    })());
    await page.goto('/game?do=action.donate', { waitUntil: 'domcontentloaded' });
    const flow = page.getByTestId('command-flow');
    await expect(flow).toBeVisible({ timeout: 60_000 });
    await expect(flow.getByText('일어나는 곳: 현 소유국 · 수령현 창고')).toBeVisible();
    await press(flow.locator('[data-arg-key="resource"]').getByRole('option', { name: /금/ }), info);
    await flow.getByRole('spinbutton', { name: '얼마나' }).fill('100');
    const submit = flow.locator('[data-input-id="action.donate"][data-input-status]');
    await expect(submit).toHaveAttribute('data-input-status', 'AVAILABLE');
    const sent = page.waitForRequest(request => request.method() === 'POST' &&
        new URL(request.url()).pathname === '/api/game/api/command/action.donate');
    await press(submit, info);
    const request = await sent;
    expect(request.postDataJSON()).toEqual({ resource: 'MONEY', amount: 100 });
    await expect(flow.getByText('「금 100 헌납」 — 01순에 예약했습니다.')).toBeVisible();
});

test('[action.donate] 쌀 수량을 골라 현재 현 소유국 창고에 예약한다', { tag: [BOTH] }, async ({ page }, info) => {
    const json = (route: Route, status: number, body: unknown) =>
        route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', route => route.fulfill({ json: { user: { id: 42, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), route => route.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), (() => {
        let stored: Record<string, unknown> | null = null;
        return (route: Route) => {
            const url = new URL(route.request().url());
            const path = url.pathname.slice('/api/game/api'.length);
            if (path === '/front-info') return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: 7, name: '무영토 기부자', nationId: 2, officerLevel: 1, permission: 0, showSecret: false },
                nation: { id: 2, name: '기부자 세력', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
            if (path === '/reserved-commands') return json(route, 200, { result: true, generalId: 7,
                slots: stored ? [{ turnIdx: 0, action: 'action.donate', brief: '', arg: stored, revision: '00000000-0000-4000-8000-0000000000e0' }] : [] });
            if (path === '/const') return json(route, 200, { result: true, gameUnitConst: [] });
            if (path === '/map/preview') return json(route, 200, { serverName: 'qa', year: 200, month: 3, mapCode: 'qa', width: 1, height: 1, nations: [], cities: [] });
            if (path === '/commands/donate-options') return json(route, 200, {
                inputId: 'action.donate', available: true, code: null,
                reason: null,
                resources: [{ resource: 'MONEY', available: true, maxAmount: 1000 },
                    { resource: 'GRAIN', available: true, maxAmount: 2000 }], targets: [],
                donationRecipient: { countyId: 11, countyName: '수령현', nationId: 1, nationName: '현 소유국' },
            });
            if (path === '/command/action.donate' && route.request().method() === 'POST') {
                stored = route.request().postDataJSON();
                return json(route, 202, { status: 'AVAILABLE', requestId: 'donation-request', turnIdx: 0 });
            }
            if (path === '/command/result/donation-request') return json(route, 200, {
                status: 'RESOLVED', requestId: 'donation-request', ok: true, type: 'reservationAccepted',
                result: { commandKind: 'RESERVED_TURN' },
            });
            return json(route, 503, {});
        };
    })());
    await page.goto('/game?do=action.donate', { waitUntil: 'domcontentloaded' });
    const flow = page.getByTestId('command-flow');
    await expect(flow).toBeVisible({ timeout: 60_000 });
    await expect(flow.getByText('일어나는 곳: 현 소유국 · 수령현 창고')).toBeVisible();
    await press(flow.locator('[data-arg-key="resource"]').getByRole('option', { name: /쌀/ }), info);
    await flow.getByRole('spinbutton', { name: '얼마나' }).fill('600');
    const submit = flow.locator('[data-input-id="action.donate"][data-input-status]');
    await expect(submit).toHaveAttribute('data-input-status', 'AVAILABLE');
    const sent = page.waitForRequest(request => request.method() === 'POST' &&
        new URL(request.url()).pathname === '/api/game/api/command/action.donate');
    await press(submit, info);
    const request = await sent;
    expect(request.postDataJSON()).toEqual({ resource: 'GRAIN', amount: 600 });
    await expect(flow.getByText('「쌀 600 헌납」 — 01순에 예약했습니다.')).toBeVisible();
});

test('헌납 준비되지 않은 실제 현 창고는 예약하지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
    const commands = await serve(page, true);
    await page.goto('/game?do=action.donate', { waitUntil: 'domcontentloaded' });
    const flow = page.getByTestId('command-flow');
    await expect(flow).toBeVisible({ timeout: 60_000 });
    const submit = flow.locator('[data-input-id="action.donate"][data-input-status]');
    await expect(submit).toHaveAttribute('data-input-status', 'BLOCKED');
    await press(submit, info);
    await expect(page.getByRole('dialog', { name: '헌납 — 지금은 할 수 없습니다' })
        .getByText('현재 현 창고를 확인할 수 없습니다.')).toBeVisible();
    expect(commands).toEqual([]);
});
