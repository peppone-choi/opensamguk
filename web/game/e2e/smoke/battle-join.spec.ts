// 전투 참가 대기 · 배치(P-C03) — /game/corps/battle/<id>?world=<worldId> 를 백엔드 없이 돈다. 두 프로필(@both).
// join-ticket 과 WS 는 이 시험 안에서만 흉내 낸다(고정 자료 — 제품 화면에는 가짜 전투가 없다. 운영은 티켓 경로가 꺼져 있어 「전투가 열리지 않음」).
// WS 프레임은 C2 v2 계약 초안(lib/battle/protocol.ts) 모양이다 — C2 가 병합되면 그 PR 이 어댑터와 함께 이 고정 자료를 맞춘다.
// 그려진다(남은 시간 · 장수별 목록 · 판 · 전장 서버 대기 · 44 · title 0 · 넘침 0)와 조작된다(칸 누름 → DEPLOYMENT_MOVE · 영수증 → 자리 바뀜 · 거절 사유)를 본다.
import { expect, test, type Page, type Route, type TestInfo, type WebSocketRoute } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
const JOIN = '[data-testid="battle-join"]';
const R = (sourceId: number) => ({ kind: 'RETINUE', sourceId });

const SNAPSHOT = {
    schemaVersion: 2, t: 'SNAPSHOT', battleId: '9001', sessionEpoch: '3', worldId: 7, tick: 0, eventSeq: '12', pacingMode: 'REALTIME',
    joinDeadlineAt: null, authorityRevision: '5', field: { boardId: 4, kind: 'FIELD' },
    units: [
        { sourceKey: R(11), ownerGeneralId: 7, cell: { row: 30, col: 10 }, troops: 800, morale: 100, order: null, rally: null },
        { sourceKey: R(12), ownerGeneralId: 7, cell: { row: 31, col: 10 }, troops: 600, morale: 90, order: null, rally: null },
        { sourceKey: R(21), ownerGeneralId: 8, cell: { row: 32, col: 12 }, troops: 500, morale: 100, order: null, rally: null },
    ],
    deployment: {
        revision: '0', defaultPinned: true, remainingMillis: 42_000,
        allowedCells: [{ row: 30, col: 10 }, { row: 31, col: 10 }, { row: 32, col: 12 }, { row: 33, col: 12 }, { row: 31, col: 12 }],
        ownPositions: [{ sourceKey: R(11), cell: { row: 30, col: 10 } }, { sourceKey: R(12), cell: { row: 31, col: 10 } }, { sourceKey: R(21), cell: { row: 32, col: 12 } }],
    },
    tickHz: null, maxTicks: null, unavailableReason: 'RULE_PIN_MISSING',
    environment: { weather: null, night: null, season: null, objective: null, unavailableReason: 'SOURCE_NOT_PINNED' },
};

interface Socket { sent: Record<string, unknown>[]; verdict: 'ACCEPTED' | 'REJECTED' }

async function serve(page: Page, ticket: 'ok' | 'off', socket?: Socket) {
    const baseURL = test.info().project.use.baseURL ?? 'http://localhost:3001';
    await page.context().addCookies([{ name: 'sam_server', value: 'pep', url: baseURL }]);
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const path = new URL(route.request().url()).pathname.slice(API.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 5, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/battles/7/9001/join-ticket' && route.request().method() === 'POST') {
            return ticket === 'ok' ? json(route, 200, { joinTicket: 'BTJ2.test' }) : json(route, 404, {});
        }
        return json(route, 503, {});
    });
    if (socket) {
        await page.routeWebSocket(/\/ws\/battles\/pep\/7\/9001/, (ws: WebSocketRoute) => {
            ws.send(JSON.stringify(SNAPSHOT));
            ws.onMessage((message) => {
                const frame = JSON.parse(String(message)) as Record<string, unknown>;
                socket.sent.push(frame);
                const receipt = socket.verdict === 'ACCEPTED'
                    ? { verdict: 'ACCEPTED', receiptSessionEpoch: '3', receiptAuthorityRevision: '5', serverTick: 0, deploymentRevisionBefore: '0', deploymentRevisionAfter: '1' }
                    : { verdict: 'REJECTED', reasonCode: 'STALE_DEPLOYMENT', receiptSessionEpoch: '3', receiptAuthorityRevision: '5', serverTick: 0 };
                ws.send(JSON.stringify({ schemaVersion: 2, t: 'ACK', battleId: '9001', sessionEpoch: '3', clientCommandId: frame.clientCommandId, inputId: 'battle.deployment_move', receipt, replayed: false }));
            });
        });
    }
}

/** 판 위 칸을 누른다 — 판이 그린 칸 좌표(data-cells)로. */
async function pressCell(page: Page, info: TestInfo, cell: string) {
    const board = page.getByTestId('battle-board');
    await expect(page.locator('[data-battle-status="ready"]')).toHaveCount(1, { timeout: 60_000 });
    await expect.poll(async () => JSON.parse((await board.getAttribute('data-cells')) ?? '{}')[cell] != null).toBe(true);
    const at = JSON.parse((await board.getAttribute('data-cells'))!)[cell] as { x: number; y: number };
    const box = (await board.boundingBox())!;
    if (isMobile(info)) await page.touchscreen.tap(box.x + at.x, box.y + at.y);
    else await page.mouse.click(box.x + at.x, box.y + at.y);
}

test.describe('전투 참가 · 배치', () => {
    test('전투 세계 번호가 있어도 티켓이 꺼져 있으면(404) 「전투가 열리지 않습니다」 — 가짜 전투 없음', { tag: [BOTH] }, async ({ page }) => {
        await serve(page, 'off');
        await page.goto('/game/corps/battle/9001?world=7', { waitUntil: 'domcontentloaded' });
        const room = page.getByTestId('battle-room');
        await expect(room).toBeVisible({ timeout: 60_000 });
        await expect(room).toContainText('전투가 열리지 않습니다(서버 준비 중)');
        await expect(page.locator(JOIN)).toHaveCount(0);
    });

    test('그려진다 — 남은 시간 · 장수별 부곡 · 판 · 전장은 서버 대기(길이 · 날씨 · 목표), 누를 영역 44 · title 0 · 넘침 0', { tag: [BOTH] }, async ({ page }) => {
        await serve(page, 'ok', { sent: [], verdict: 'ACCEPTED' });
        await page.goto('/game/corps/battle/9001?world=7', { waitUntil: 'domcontentloaded' });
        const join = page.locator(JOIN);
        await expect(join).toBeVisible({ timeout: 60_000 });
        await expect(page.getByRole('timer', { name: '개전까지 남은 시간' })).toHaveText(/^0:(4[0-2]|3\d)$/);
        await expect(join).toContainText('내 군단 부곡 3개가 모두 나간다');
        const list = page.getByRole('listbox', { name: '내 군단 부곡' });
        await expect(list.getByRole('group', { name: '장수 1' }).getByRole('option')).toHaveCount(2);
        await expect(list.getByRole('group', { name: '장수 2' }).getByRole('option')).toHaveCount(1);
        const field = page.getByRole('region', { name: '전장', exact: true });
        await expect(field).toContainText('4번 판');
        // 보드의 「5분 · 3,000틱」은 예시 — 규칙 핀이 없으면 서버 대기(C2 #15).
        await expect(field.getByText('서버 대기')).toHaveCount(3);
        await expect(page.locator('[data-battle-status="ready"]')).toHaveCount(1, { timeout: 60_000 });
        expect(await smallTouchTargets(page, JOIN)).toEqual([]);
        expect(await titleOnlyInfo(page, JOIN)).toEqual([]);
        await expectNoHorizontalOverflow(page);
    });

    test('조작된다 — 칸을 누르면 DEPLOYMENT_MOVE(기대 값 셋), 영수증을 받으면 자리가 바뀐다', { tag: [BOTH] }, async ({ page }, info) => {
        const socket: Socket = { sent: [], verdict: 'ACCEPTED' };
        await serve(page, 'ok', socket);
        await page.goto('/game/corps/battle/9001?world=7', { waitUntil: 'domcontentloaded' });
        await expect(page.locator(JOIN)).toBeVisible({ timeout: 60_000 });
        const first = page.getByRole('group', { name: '장수 1' }).getByRole('option').first();
        await expect(first).toHaveAttribute('aria-selected', 'true');
        await pressCell(page, info, '33:12');
        await expect.poll(() => socket.sent.length).toBe(1);
        expect(socket.sent[0]).toMatchObject({
            schemaVersion: 2, t: 'DEPLOYMENT_MOVE', sourceKey: { kind: 'RETINUE', sourceId: 11 }, targetCell: { row: 33, col: 12 },
            expectedEpoch: '3', expectedAuthorityRevision: '5', expectedDeploymentRevision: '0',
        });
        await expect(first).toContainText('칸 33,12');
    });

    test('거절 — 서버가 STALE_DEPLOYMENT 로 거절하면 쉬운 말 사유, 자리는 그대로', { tag: [BOTH] }, async ({ page }, info) => {
        const socket: Socket = { sent: [], verdict: 'REJECTED' };
        await serve(page, 'ok', socket);
        await page.goto('/game/corps/battle/9001?world=7', { waitUntil: 'domcontentloaded' });
        await expect(page.locator(JOIN)).toBeVisible({ timeout: 60_000 });
        await pressCell(page, info, '33:12');
        await expect(page.getByRole('status').filter({ hasText: '배치가 먼저 바뀌었습니다' })).toBeVisible();
        await expect(page.getByRole('group', { name: '장수 1' }).getByRole('option').first()).toContainText('칸 30,10');
    });
});
