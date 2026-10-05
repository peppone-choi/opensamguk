// 실시간 전투(P-C05) — /game/corps/battle/<id>?world=<worldId> 진행 중 단계를 백엔드 없이 돈다. 두 프로필(@both).
// join-ticket 과 WS(진행 중 SNAPSHOT · AUTHORITY · ACK)는 이 시험 안에서만 흉내 낸다(고정 자료 — 제품 화면에는 가짜 전투가 없다).
// WS 프레임은 C2 v2 계약 초안(lib/battle/protocol.ts) 모양이다 — C2 가 병합되면 그 PR 이 어댑터와 함께 이 고정 자료를 맞춘다.
// 그려진다(목록 · 판 · 명령 막대 · 서버 대기 표지 · 44 · title 0 · 넘침 0 · AI 표지)와 조작된다(고르기 → COMMAND scope · 영수증 · 거절)를 본다.
// 판 조작(D24 세부 1 · 2): 마우스 끌기 사각형(데스크톱) · 「판에서 고르기」 두 점 · 터치 끌기 판 움직이기(모바일) · 작게 보면 묶음 → 누르면 2배로 다가감.
import { expect, test, type Page, type Route, type TestInfo, type WebSocketRoute } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, MOBILE_ONLY, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
// 서버 대기 표지 읽기(#1335) — 의존 없는 도우미 파일만 가져온다.
import { serverWaitRows } from '../../../shared/src/serverWaitTesting';

const API = '/api/game/api';
const LIVE = '[data-testid="battle-live"]';
const R = (sourceId: number) => ({ kind: 'RETINUE', sourceId });

const SNAPSHOT = {
    schemaVersion: 2, t: 'SNAPSHOT', battleId: '9001', sessionEpoch: '3', worldId: 7, tick: 120, eventSeq: '40', pacingMode: 'REALTIME',
    joinDeadlineAt: null, authorityRevision: '6', field: { boardId: 4, kind: 'FIELD' },
    units: [
        { sourceKey: R(11), ownerGeneralId: 7, cell: { row: 30, col: 14 }, troops: 780, morale: 96, order: 'CHARGE', rally: 'HOME' },
        { sourceKey: R(12), ownerGeneralId: 7, cell: { row: 31, col: 14 }, troops: 590, morale: 88, order: 'CHARGE', rally: 'HOME' },
        { sourceKey: R(21), ownerGeneralId: 8, cell: { row: 32, col: 15 }, troops: 500, morale: 100, order: 'DEFEND', rally: 'CENTER' },
    ],
    deployment: null, tickHz: null, maxTicks: null, unavailableReason: 'RULE_PIN_MISSING',
    environment: { weather: null, night: null, season: null, objective: null, unavailableReason: 'SOURCE_NOT_PINNED' },
};

interface Socket { sent: Record<string, unknown>[]; verdict: 'ACCEPTED' | 'REJECTED'; authority?: boolean }

async function serve(page: Page, socket: Socket) {
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
        if (path === '/battles/7/9001/join-ticket' && route.request().method() === 'POST') return json(route, 200, { joinTicket: 'BTJ2.test' });
        return json(route, 503, {});
    });
    await page.routeWebSocket(/\/ws\/battles\/pep\/7\/9001/, (ws: WebSocketRoute) => {
        ws.send(JSON.stringify(SNAPSHOT));
        if (socket.authority) {
            ws.send(JSON.stringify({ schemaVersion: 2, t: 'AUTHORITY', battleId: '9001', sessionEpoch: '3', sourceKey: R(21), controller: 'AI', reason: 'DEADLINE_PASSED', authorityRevision: '7' }));
        }
        ws.onMessage((message) => {
            const frame = JSON.parse(String(message)) as Record<string, unknown>;
            socket.sent.push(frame);
            const receipt = socket.verdict === 'ACCEPTED'
                ? { verdict: 'ACCEPTED', receiptSessionEpoch: '3', receiptAuthorityRevision: '6', serverTick: 121, acceptedTick: 121, effectiveTick: 122, eventSeq: '41' }
                : { verdict: 'REJECTED', reasonCode: 'INVALID_SCOPE', receiptSessionEpoch: '3', receiptAuthorityRevision: '6', serverTick: 121 };
            ws.send(JSON.stringify({ schemaVersion: 2, t: 'ACK', battleId: '9001', sessionEpoch: '3', clientCommandId: frame.clientCommandId, inputId: 'battle.command', receipt, replayed: false }));
        });
    });
}

async function openLive(page: Page, socket: Socket) {
    await serve(page, socket);
    await page.goto('/game/corps/battle/9001?world=7', { waitUntil: 'domcontentloaded' });
    await expect(page.locator(LIVE)).toBeVisible({ timeout: 60_000 });
}

const picker = (page: Page) => page.getByRole('group', { name: '내 군단 부곡 고르기' });

interface BoardRead {
    cells: Record<string, { x: number; y: number }>;
    clusters: { group: number; count: number; x: number; y: number }[];
    view: string;
    scale: string;
    box: { x: number; y: number; width: number; height: number };
}

/** 판이 그려지면 칸 · 묶음 · 판 위치 · 배율을 읽는다(캔버스 data-*). */
async function readBoard(page: Page): Promise<BoardRead> {
    const canvas = page.getByTestId('battle-board');
    await expect(page.locator('[data-battle-status="ready"]')).toHaveCount(1, { timeout: 60_000 });
    await expect.poll(async () => JSON.parse((await canvas.getAttribute('data-cells')) ?? '{}')['30:14'] != null).toBe(true);
    return {
        cells: JSON.parse((await canvas.getAttribute('data-cells'))!),
        clusters: JSON.parse((await canvas.getAttribute('data-clusters')) ?? '[]'),
        view: (await canvas.getAttribute('data-view'))!,
        scale: (await canvas.getAttribute('data-scale'))!,
        box: (await canvas.boundingBox())!,
    };
}

/** 판 위 한 점(캔버스 안 좌표)을 누른다 — 데스크톱은 마우스, 모바일은 터치. */
async function tapBoard(page: Page, info: TestInfo, box: BoardRead['box'], x: number, y: number) {
    if (isMobile(info)) await page.touchscreen.tap(box.x + x, box.y + y);
    else await page.mouse.click(box.x + x, box.y + y);
}

test.describe('실시간 전투', () => {
    test('그려진다 — 목록 · 판 · 6명령 · 집결 결정 대기 · 서버 대기 표지, 누를 영역 44 · title 0 · 넘침 0, AUTHORITY 가 온 부곡만 AI', { tag: [BOTH] }, async ({ page }) => {
        await openLive(page, { sent: [], verdict: 'ACCEPTED', authority: true });
        const live = page.locator(LIVE);
        await expect(page.getByRole('timer', { name: '남은 시간' })).toHaveText('서버 대기');
        await expect(picker(page).getByRole('checkbox', { name: /^부곡/ })).toHaveCount(3);
        await expect(page.getByRole('group', { name: '명령' }).getByRole('button')).toHaveCount(6);
        await expect(live).toContainText('[결정 대기] 집결 1 · 2 · 3');
        // AI 표지는 서버 AUTHORITY 가 온 부곡(장수 2의 부곡 1)에만.
        await expect(picker(page).getByText('AI', { exact: true })).toHaveCount(1);
        expect([...new Set(await live.evaluate(serverWaitRows))].sort()).toEqual(['K6-14 · DELTA', 'K6-14 · rulePin', 'K6-14 · units', 'K6-14 · visibleEnemy']);
        await expect(page.locator('[data-battle-status="ready"]')).toHaveCount(1, { timeout: 60_000 });
        expect(await smallTouchTargets(page, LIVE)).toEqual([]);
        expect(await titleOnlyInfo(page, LIVE)).toEqual([]);
        await expectNoHorizontalOverflow(page);
    });

    test('조작된다 — 장수 1 묶음(모두 HOME) → 돌격: COMMAND scope sourceKeys · 기대 값 · 틱, 영수증 「명령 받음」', { tag: [BOTH] }, async ({ page }, info) => {
        const socket: Socket = { sent: [], verdict: 'ACCEPTED' };
        await openLive(page, socket);
        await press(picker(page).getByRole('checkbox', { name: /^장수 1/ }), info);
        await expect(page.getByText('고른 부곡 2 / 3')).toBeVisible();
        const charge = page.getByRole('group', { name: '명령' }).getByRole('button', { name: '돌격' });
        await charge.evaluate((el) => el.scrollIntoView({ block: 'center' }));
        await press(charge, info);
        await expect.poll(() => socket.sent.length).toBe(1);
        expect(socket.sent[0]).toMatchObject({
            schemaVersion: 2, t: 'COMMAND', expectedEpoch: '3', expectedAuthorityRevision: '6', issuedTick: 120,
            scope: { sourceKeys: [R(11), R(12)] }, intentType: 'CHARGE', intentPayload: { rally: 'HOME' },
        });
        await expect(page.getByRole('status').filter({ hasText: '명령 받음 — 고른 부곡 2개' })).toBeVisible();
    });

    test('막힘 · 거절 — 집결점이 섞이면 보내지 않고 사유, 서버가 INVALID_SCOPE 로 거절하면 쉬운 말', { tag: [BOTH] }, async ({ page }, info) => {
        const socket: Socket = { sent: [], verdict: 'REJECTED' };
        await openLive(page, socket);
        await press(page.getByRole('button', { name: '내 부곡 전부' }), info);
        const defend = page.getByRole('group', { name: '명령' }).getByRole('button', { name: '수비' });
        await defend.evaluate((el) => el.scrollIntoView({ block: 'center' }));
        await press(defend, info);
        await expect(page.getByRole('status').filter({ hasText: '집결점이 서로 달라' })).toBeVisible();
        expect(socket.sent).toEqual([]);
        await press(page.getByRole('button', { name: '다 풀기' }), info);
        await press(picker(page).getByRole('checkbox', { name: /^장수 2/ }), info);
        await defend.evaluate((el) => el.scrollIntoView({ block: 'center' }));
        await press(defend, info);
        await expect.poll(() => socket.sent.length).toBe(1);
        expect(socket.sent[0]).toMatchObject({ scope: { sourceKeys: [R(21)] }, intentType: 'DEFEND', intentPayload: { rally: 'CENTER' } });
        await expect(page.getByRole('status').filter({ hasText: '명령 거절 — 고른 부곡으로는 이 명령을 보낼 수 없습니다' })).toBeVisible();
    });
    test('판에서 고르기(데스크톱) — 마우스로 판을 끌면 사각형 안 내 부곡만 고른다(장수 1 부곡 둘)', async ({ page }) => {
        await openLive(page, { sent: [], verdict: 'ACCEPTED' });
        const { cells, box } = await readBoard(page);
        const [a, b, c] = [cells['30:14'], cells['31:14'], cells['32:15']];
        const x0 = Math.min(a.x, b.x) - 6, x1 = Math.max(a.x, b.x) + 6, y0 = Math.min(a.y, b.y) - 6, y1 = Math.max(a.y, b.y) + 6;
        // 고정 자료 확인 — 장수 2 부곡은 사각형 밖이어야 한다.
        expect(c.x < x0 || c.x > x1 || c.y < y0 || c.y > y1).toBe(true);
        await page.mouse.move(box.x + x0, box.y + y0);
        await page.mouse.down();
        await page.mouse.move(box.x + (x0 + x1) / 2, box.y + (y0 + y1) / 2, { steps: 4 });
        await page.mouse.move(box.x + x1, box.y + y1, { steps: 4 });
        await page.mouse.up();
        await expect(page.getByText('고른 부곡 2 / 3')).toBeVisible();
        await expect(picker(page).getByRole('checkbox', { name: /^장수 1/ })).toHaveAttribute('aria-checked', 'true');
        await expect(picker(page).getByRole('checkbox', { name: /^장수 2/ })).toHaveAttribute('aria-checked', 'false');
        await expect(page.getByRole('status')).toContainText('판에서 고름 — 부곡 2개');
    });

    test('판에서 고르기 — 켜고 두 점을 누르면 그 사각형 안 부곡만 고르고 꺼진다', { tag: [BOTH] }, async ({ page }, info) => {
        await openLive(page, { sent: [], verdict: 'ACCEPTED' });
        const toggle = page.getByRole('button', { name: '판에서 고르기' });
        await press(toggle, info);
        await expect(toggle).toHaveAttribute('aria-pressed', 'true');
        await expect(page.getByRole('status')).toContainText('사각형의 첫 점을 누르세요');
        const { cells, box } = await readBoard(page);
        const p = cells['30:14'];
        await tapBoard(page, info, box, p.x - 6, p.y - 6);
        await expect(page.getByRole('status')).toContainText('두 번째 점을 누르세요');
        await tapBoard(page, info, box, p.x + 6, p.y + 6);
        await expect(page.getByRole('status')).toContainText('판에서 고름 — 부곡 1개');
        await expect(toggle).toHaveAttribute('aria-pressed', 'false');
        await expect(page.getByText('고른 부곡 1 / 3')).toBeVisible();
        await expect(picker(page).getByRole('checkbox', { name: /^장수 1/ })).toHaveAttribute('aria-checked', 'mixed');
    });

    test('판 움직이기(모바일) — 터치로 끌면 판이 끈 만큼 따라 움직이고 고르기는 그대로', { tag: [MOBILE_ONLY] }, async ({ page }) => {
        await openLive(page, { sent: [], verdict: 'ACCEPTED' });
        const before = await readBoard(page);
        const canvas = page.getByTestId('battle-board');
        const at = (dx: number, dy: number) => ({
            pointerId: 7, pointerType: 'touch', isPrimary: true, button: 0,
            clientX: before.box.x + before.box.width / 2 + dx, clientY: before.box.y + before.box.height / 2 + dy,
        });
        await canvas.dispatchEvent('pointerdown', at(0, 0));
        await canvas.dispatchEvent('pointermove', at(30, 10));
        await canvas.dispatchEvent('pointermove', at(60, 20));
        await canvas.dispatchEvent('pointerup', at(60, 20));
        await expect.poll(async () => (await readBoard(page)).view).not.toBe(before.view);
        const [x0, y0] = before.view.split(',').map(Number);
        const [x1, y1] = (await readBoard(page)).view.split(',').map(Number);
        expect(x1 - x0).toBeCloseTo(60, 0);
        expect(y1 - y0).toBeCloseTo(20, 0);
        await expect(page.getByText('고른 부곡 0 / 3')).toBeVisible();
    });

    test('많을 때 묶기 — 작게 보면 장수 1 부곡 둘이 깃발 하나(2)로 묶이고, 깃발을 누르면 2배로 다가가 갈라진다', { tag: [BOTH] }, async ({ page }, info) => {
        await openLive(page, { sent: [], verdict: 'ACCEPTED' });
        expect((await readBoard(page)).clusters).toEqual([]);
        await press(page.getByRole('button', { name: '작게' }), info);
        await expect.poll(async () => (await readBoard(page)).scale).toBe('1.600');
        const zoomed = await readBoard(page);
        expect(zoomed.clusters).toEqual([expect.objectContaining({ group: 1, count: 2 })]);
        await tapBoard(page, info, zoomed.box, zoomed.clusters[0].x, zoomed.clusters[0].y);
        await expect.poll(async () => (await readBoard(page)).scale).toBe('2.000');
        expect((await readBoard(page)).clusters).toEqual([]);
        // 깃발 누르기는 고르기가 아니다.
        await expect(page.getByText('고른 부곡 0 / 3')).toBeVisible();
    });
});
