// 도움말 독립 페이지(/game/help, P-A01) 스모크 — 도움말 API 대역(help-api.ts, 저장소 data/help)으로 백엔드 없이 돈다.
// e2e/smoke 규칙(support/parity.ts): @both = 데스크톱 · 모바일(390 × 844 터치) 같은 흐름, @mobile-only. 누르기는 press(모바일 = 탭).
import { expect, test, type Page } from '@playwright/test';
import { BOTH, MOBILE_ONLY, expectCenterHitsMap, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
import { serveHelpApi, type HelpApiOptions } from './help-api';
import type { DispatchOptionsResponse } from '../../lib/types';

const PANEL = '[data-help-panel="page"]';

async function open(page: Page, view = '', options: HelpApiOptions = {}) {
    await serveHelpApi(page, options);
    const url = `/game/help?from=war-room${view ? `&view=${encodeURIComponent(view)}` : ''}`;
    // 개발 서버가 첫 요청에 화면을 컴파일하며 이동을 한 번 끊을 수 있다(ERR_ABORTED) — 한 번만 다시 간다.
    await page.goto(url).catch(async (e: Error) => {
        if (!/ERR_ABORTED|frame was detached/.test(e.message)) throw e;
        await page.goto(url);
    });
    const panel = page.locator(PANEL);
    await expect(panel).toBeVisible({ timeout: 60_000 });
    return panel;
}

test.describe('도움말', () => {
    test('이 화면 → 주제 → 안 되는 경우 → 사유 → 뒤로', { tag: [BOTH] }, async ({ page }, info) => {
        const panel = await open(page);
        await expect(panel.getByRole('button', { name: /현장 행동\s*26/ })).toHaveAttribute('aria-pressed', 'true');
        await expect(panel.getByText('현의 농지를 개간해 농업 기반을 키우는 직접 행동입니다.')).toBeVisible();
        await press(panel.getByRole('button', { name: /이동\s*5/ }), info);
        await press(panel.getByRole('button', { name: /^출병/ }), info);
        await expect(panel.getByRole('heading', { name: '출병' })).toBeVisible();
        await expect(page).toHaveURL(/view=input%3Aaction\.deploy/);
        const rules = panel.getByRole('region', { name: '이 명령의 규칙' });
        await expect(rules).toContainText('명령 목록 12순 · 한 순에 하나 · 이동 단계');
        await expect(panel.getByText('초안').first()).toBeVisible();
        await press(panel.getByRole('button', { name: /안 되는 경우 \d+가지/ }), info);
        const firstFail = panel.locator('li button').filter({ hasText: /\S/ }).first();
        await expect(firstFail).not.toHaveText('…');
        await press(firstFail, info);
        await expect(panel.getByText('이렇게 하면 됩니다')).toBeVisible();
        await page.goBack();
        await expect(panel.getByRole('heading', { name: '출병' })).toBeVisible();
        await expect(panel).not.toContainText(/action\.|commands\.|HANDLER_DEFINED|NOT_LORD/);
        expect(await titleOnlyInfo(page, PANEL)).toEqual([]);
        await expectNoHorizontalOverflow(page);
    });

    test('찾기: 한 글자는 보내지 않고, 두 글자부터 찾는다', { tag: [BOTH] }, async ({ page }, info) => {
        const log: string[] = [];
        const panel = await open(page, '', { log });
        const box = panel.getByRole('searchbox');
        await box.fill('인');
        await expect(panel.getByText('두 글자 이상 적어 주세요.')).toBeVisible();
        await page.waitForTimeout(500);
        expect(log.filter((l) => l.startsWith('/api/help/search'))).toEqual([]);
        await box.fill('인물');
        await expect(panel.getByRole('status').filter({ hasText: /결과 \d+개/ })).toBeVisible();
        await expect(page).toHaveURL(/view=search%3A/);
        await press(panel.getByRole('list', { name: '찾은 도움말' }).getByRole('button').first(), info);
        await expect(panel.getByRole('heading').first()).toBeVisible();
        await box.fill('화계없음');
        await expect(panel.getByText('"화계없음"에 맞는 도움말이 없습니다')).toBeVisible();
    });

    test('승인된 새 명령 이름으로 보인다(원문 교체는 C7)', { tag: [BOTH] }, async ({ page }) => {
        const panel = await open(page, 'input:action.tradeGrain');
        await expect(panel.getByRole('heading', { name: '쌀 사고팔기' })).toBeVisible();
        // 승인된 이름만 바꾼다 — 원문의 다른 말(「군량을 사고파는」)은 C7 원문 교체(K7-COPY-06/07) 몫이라 그대로 둔다.
        await expect(panel).not.toContainText(/군량매매|숙련전환/);
        await expect(panel.getByText('쌀 사고팔기를 선택하고', { exact: false })).toBeVisible();
    });

    test('서버 대기(503)는 오류와 다른 모양', { tag: [BOTH] }, async ({ page }) => {
        const panel = await open(page, 'input:action.enlist', { forceStatus: { status: 503, code: 'WORLD_UNAVAILABLE' } });
        await expect(panel.getByText('서버가 준비 중이라 도움말도 잠시 쉽니다')).toBeVisible();
    });

    test('첫걸음 탭 — 8단계 설명만, 진행 기록 · 진척 요청 없음(D21)', { tag: [BOTH] }, async ({ page }) => {
        const log: string[] = [];
        const panel = await open(page, 'start', { log });
        await expect(panel.getByRole('tab', { name: '첫걸음' })).toHaveAttribute('aria-selected', 'true');
        await expect(panel.getByRole('list', { name: '첫걸음 8단계' }).getByRole('heading', { level: 3 })).toHaveText([
            '1단계 · 가입', '2단계 · 장수 생성', '3단계 · 출사', '4단계 · 발령', '5단계 · 공사', '6단계 · 등용', '7단계 · 행군', '8단계 · 전투']);
        await expect(panel.getByText('서버가 아직 전투를 열지 않아 참가 대기 · 진행 중인 전투는 볼 수 없습니다.')).toBeVisible();
        expect(log.filter((l) => l.includes('tutorial'))).toEqual([]);
    });

    test('모바일: 누를 것은 모두 44 이상 · 가운데를 누르면 그 단추', { tag: [MOBILE_ONLY] }, async ({ page }, info) => {
        const panel = await open(page, 'input:action.enlist');
        await press(panel.getByRole('button', { name: /안 되는 경우/ }), info);
        await expect(panel.locator('li button').first()).toBeVisible();
        expect(await smallTouchTargets(page, PANEL)).toEqual([]);
        const toggle = panel.getByRole('button', { name: /안 되는 경우/ });
        const box = (await toggle.boundingBox())!;
        const hit = await page.evaluate(([x, y]) => document.elementFromPoint(x, y)?.closest('button')?.textContent ?? '', [box.x + box.width / 2, box.y + box.height / 2]);
        expect(hit).toContain('안 되는 경우');
        await expectNoHorizontalOverflow(page);
    });
});

// ---- 셸 도움말 서랍(`?help=`, components/shell/HelpDrawer) ----------------------------------------------------------
// 셸 스모크(shell.spec.ts)와 같은 합성 로그인 · front-info 로 부 · 월단평을 열고, 머리줄 「?」로 서랍을 연다.
async function openShellWithHelp(page: Page, path = '/game/retinue/yuedan') {
    await shellRoutes(page);
    await page.goto(path, { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '월단평' })).toBeVisible({ timeout: 60_000 });
}

async function shellRoutes(page: Page, options: { serverScoped?: boolean; hasGeneral?: boolean } = {}) {
    const baseURL = test.info().project.use.baseURL ?? 'http://localhost:3001';
    // The normal smoke app has no SERVER_ID; only address assertions need a server cookie.
    await page.context().addCookies([{ name: 'sam_server', value: options.serverScoped === false ? '' : 'pep', url: baseURL,
        ...(options.serverScoped === false ? { expires: 1 } : {}) }]);
    await serveHelpApi(page);
    // 뒤에 건 route 가 먼저 받는다 — front-info · 턴 루프 읽기만 셸 몫으로 가로챈다.
    await page.route((url) => url.pathname.endsWith('/front-info'), (r) => r.fulfill({ json: {
        result: true,
        global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
        general: { hasGeneral: options.hasGeneral ?? true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
        nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
    } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
}

/** 누를 것의 가운데를 다른 상자가 덮는지(셸 스모크 · K10 「덮임」과 같은 방법 — elementFromPoint). */
async function coveredIn(page: Page, selector: string): Promise<string[]> {
    return page.locator(selector).first().evaluate((root) => {
        const out: string[] = [];
        for (const el of Array.from(root.querySelectorAll<HTMLElement>('a, button, input'))) {
            const r = el.getBoundingClientRect();
            if (r.width === 0 || r.height === 0) continue;
            const cx = r.x + r.width / 2;
            const cy = r.y + r.height / 2;
            if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) continue;
            const hit = document.elementFromPoint(cx, cy);
            if (hit !== el && !el.contains(hit) && !hit?.contains(el)) out.push(`${(el.textContent ?? '').trim()} ← ${hit?.tagName}.${hit?.className}`);
        }
        return out;
    });
}

const DRAWER = 'aside[aria-label="도움말"]';

test.describe('도움말 서랍', () => {
    test('머리줄 「?」로 열고(문서를 다시 받지 않음) 주제 · 뒤로 · 닫기 — 44 · 덮임 0 · 넘침 0', { tag: [BOTH] }, async ({ page }, info) => {
        await openShellWithHelp(page);
        await page.evaluate(() => { (window as unknown as { __k7: number }).__k7 = 1; });
        await press(page.getByRole('link', { name: '이 화면 도움말' }), info);
        const drawer = page.locator(DRAWER);
        await expect(drawer).toBeVisible();
        await expect(page).toHaveURL(/[?&]help=home/);
        expect(await page.evaluate(() => (window as unknown as { __k7?: number }).__k7)).toBe(1);
        await expect(drawer.getByText('부에서 하는 일')).toBeVisible();
        const box = (await drawer.boundingBox())!;
        if (isMobile(info)) {
            expect(Math.round(box.x)).toBe(0);
            expect(Math.round(box.width)).toBe(390);
        } else {
            expect(Math.round(box.width)).toBe(400);
        }
        expect(await smallTouchTargets(page, DRAWER)).toEqual([]);
        expect(await coveredIn(page, DRAWER)).toEqual([]);
        expect(await titleOnlyInfo(page, DRAWER)).toEqual([]);
        await expectNoHorizontalOverflow(page);

        await press(drawer.getByRole('button', { name: /^인재탐색/ }), info);
        await expect(drawer.getByRole('heading', { name: '인재탐색' })).toBeVisible();
        await expect(page).toHaveURL(/help=input%3Aaction\.search/);
        await expect(page.getByRole('heading', { level: 2, name: '월단평' })).toBeAttached(); // 본문은 그대로 — 모달 아님
        await press(drawer.getByRole('button', { name: '앞 보기로' }), info);
        await expect(drawer.getByText('부에서 하는 일')).toBeVisible();
        await press(drawer.getByRole('button', { name: '도움말 닫기(Esc)' }), info);
        await expect(drawer).toBeHidden();
        await expect(page).not.toHaveURL(/help=/);
        expect(await page.evaluate(() => (window as unknown as { __k7?: number }).__k7)).toBe(1);
    });

    test('데스크톱: 레일 「도움말」로 열면 본문 옆에 서고 찾기칸에 포커스, Esc 로 닫힌다', { tag: ['@desktop-only'] }, async ({ page }) => { // 레일은 데스크톱 · 태블릿만(모바일 프로젝트의 grep 에 안 걸린다)
        await openShellWithHelp(page);
        await page.getByRole('navigation', { name: '게임 메뉴' }).getByRole('link', { name: '도움말' }).click();
        const drawer = page.locator(DRAWER);
        await expect(drawer.getByRole('searchbox')).toBeFocused();
        const main = (await page.getByRole('main', { name: '게임 콘텐츠' }).boundingBox())!;
        const side = (await drawer.boundingBox())!;
        expect(side.x).toBeGreaterThanOrEqual(main.x + main.width - 1); // 덮지 않고 옆에 선다
        await page.keyboard.press('Escape');
        await expect(drawer).toBeHidden();
    });

    test('모바일: 서랍은 머리줄 아래를 가득 덮고 하단 탭을 가린다 — 찾기칸 자동 포커스 없음', { tag: [MOBILE_ONLY] }, async ({ page }, info) => {
        await openShellWithHelp(page);
        await press(page.getByRole('link', { name: '이 화면 도움말' }), info);
        const drawer = page.locator(DRAWER);
        await expect(drawer.getByText('부에서 하는 일')).toBeVisible();
        await expect(drawer.getByRole('searchbox')).not.toBeFocused();
        const box = (await drawer.boundingBox())!;
        expect(Math.round(box.y)).toBe(56);
        expect(Math.round(box.y + box.height)).toBe(844);
        const tab = (await page.getByRole('navigation', { name: '게임 메뉴' }).first().boundingBox())!;
        const hit = await page.evaluate(([x, y]) => document.elementFromPoint(x, y)?.closest('aside')?.getAttribute('aria-label') ?? null, [tab.x + tab.width / 2, tab.y + tab.height / 2]);
        expect(hit).toBe('도움말');
    });

    test('모바일: 「전체」 시트와 서랍은 동시에 열리지 않는다 — 시트의 도움말은 시트를 닫고 서랍을 연다', { tag: [MOBILE_ONLY] }, async ({ page }, info) => {
        await openShellWithHelp(page);
        await press(page.getByRole('button', { name: '전체' }), info);
        const sheet = page.getByRole('dialog', { name: '전체 메뉴' });
        await expect(sheet).toBeVisible();
        // 시트가 열린 동안 머리줄 「?」는 시트 덮개 밑이다(누를 수 없다).
        expect(await centerHit(page, page.getByRole('link', { name: '이 화면 도움말' }))).not.toBe('link:이 화면 도움말');
        await press(sheet.getByRole('link', { name: '도움말' }), info);
        await expect(sheet).toBeHidden();
        await expect(page.locator(DRAWER).getByText('부에서 하는 일')).toBeVisible();
        // 서랍이 열린 동안 「전체」 단추는 서랍 밑이다.
        expect(await centerHit(page, page.getByRole('button', { name: '전체' }))).toBe('aside:도움말');
    });
});

/** 요소 가운데를 누르면 무엇에 닿는지 — `aside:<이름>` · `link:<이름>` · `button:<이름>` · 그 밖은 태그. */
async function centerHit(page: Page, locator: import('@playwright/test').Locator): Promise<string> {
    const box = (await locator.boundingBox())!;
    return page.evaluate(([x, y]) => {
        const el = document.elementFromPoint(x, y);
        const aside = el?.closest('aside');
        if (aside) return `aside:${aside.getAttribute('aria-label')}`;
        const a = el?.closest('a, button');
        if (a) return `${a.tagName === 'A' ? 'link' : 'button'}:${a.getAttribute('aria-label') ?? (a.textContent ?? '').trim()}`;
        return el?.tagName.toLowerCase() ?? 'none';
    }, [box.x + box.width / 2, box.y + box.height / 2]);
}

// ---- 서랍이 열린 채 지도(K1 world-map.spec.ts 와 같은 합성 지도, 작게) ------------------------------------------------
const MAP_COLS = 40;
const MAP_ROWS = 40;
async function syntheticMap(page: Page) {
    await page.route('**/api/**', (route) => {
        const url = new URL(route.request().url());
        if (url.pathname === '/api/auth/me') return route.fulfill({ json: { user: { id: 1, username: 'smoke', email: null, nickname: '스모크', role: 'USER' } } });
        if (url.pathname.endsWith('/api/const')) return route.fulfill({ json: { result: true, mapName: 'han-world-v3', mapWidth: 700, mapHeight: 610, maxTurn: 12 } });
        if (url.pathname.endsWith('/api/map/preview')) {
            return route.fulfill({ json: { mapCode: 'han-world-v3', width: 700, height: 610, nations: [{ id: 1, name: '위', color: '#b03a2e' }],
                cities: [{ id: 1, name: '낙양', level: 8, nationId: 1, x: 300, y: 280, state: 0, supply: true, isCapital: true, isCommanderySeat: true, commanderyName: '하남윤' }] } });
        }
        if (url.pathname.endsWith('/api/map/terrain')) {
            return route.fulfill({ json: {
                _meta: { cols: MAP_COLS, rows: MAP_ROWS, year: 200, terrainLegend: { 0: 'SEA', 1: 'PLAIN', 2: 'MOUNTAIN' } },
                terrain: Array.from({ length: MAP_ROWS }, (_, row) => Array.from({ length: MAP_COLS }, (_, col) => (
                    row < 3 || col < 3 ? '0' : (row * 7 + col * 3) % 11 === 0 ? '2' : '1')).join('')),
                owner: [[0, MAP_COLS * MAP_ROWS]],
                juns: [{ name: '하남윤', nameCh: '河南尹', seat: 0, col: 18, row: 18 }],
                adjacency: { county: [], commandery: [] }, regions: [], cities: [],
            } });
        }
        return route.fulfill({ status: 503, json: { error: 'smoke' } });
    });
    await serveHelpApi(page, { onlyHelp: true }); // 뒤에 건 route 가 먼저 — 도움말만 받고 나머지는 위로 넘긴다
}

async function canvasHash(page: Page): Promise<{ painted: number; hash: number }> {
    return page.locator('.os-iso-map__canvas').first().evaluate((node) => {
        const canvas = node as HTMLCanvasElement;
        const context = canvas.getContext('2d')!;
        let painted = 0;
        let hash = 0;
        for (let i = 1; i < 16; i += 1) for (let j = 1; j < 16; j += 1) {
            const d = context.getImageData(Math.floor(canvas.width * i / 16), Math.floor(canvas.height * j / 16), 1, 1).data;
            if (d[3] > 0) painted += 1;
            hash = (hash * 31 + d[0] * 7 + d[1] * 13 + d[2] * 17) >>> 0;
        }
        return { painted, hash };
    });
}

test('서랍이 열린 채 지도 — 데스크톱은 서랍이 옆에 서서 휠 · 끌기가 캔버스에 닿고, 모바일은 서랍이 덮었다가 닫으면 지도가 받는다', { tag: [BOTH] }, async ({ page }, info) => {
    await syntheticMap(page);
    await page.goto('/game/map?help=home');
    const drawer = page.locator(DRAWER);
    await expect(drawer.getByRole('searchbox')).toBeVisible({ timeout: 60_000 });
    const canvas = page.locator('.os-iso-map__canvas').first();
    await expect(canvas).toBeAttached({ timeout: 60_000 });
    await expect.poll(async () => (await canvasHash(page)).painted, { timeout: 30_000 }).toBeGreaterThan(150);

    if (isMobile(info)) {
        expect(await centerHit(page, canvas)).toBe('aside:도움말');
        await press(drawer.getByRole('button', { name: '도움말 닫기(Esc)' }), info);
        await expect(drawer).toBeHidden();
        await expectCenterHitsMap(page, '.os-iso-map');
        return;
    }
    // 데스크톱: 서랍은 본문을 덮지 않고 옆에 선다 — 지도 오른쪽 끝이 서랍 왼쪽을 넘지 않는다.
    const side = (await drawer.boundingBox())!;
    const map = (await page.locator('.os-iso-map').first().boundingBox())!;
    expect(map.x + map.width).toBeLessThanOrEqual(side.x + 1);
    // 서랍 내용이 셸 본문을 밀어 올리지 않는다 — 짧은 화면에서 서랍 아래가 창 밖으로 나가 페이지가 스크롤되면 안 된다.
    const viewportHeight = page.viewportSize()!.height;
    expect(side.y + side.height).toBeLessThanOrEqual(viewportHeight + 1);
    expect(await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight)).toBeLessThanOrEqual(1);
    await expectCenterHitsMap(page, '.os-iso-map');
    const box = (await canvas.boundingBox())!;
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;
    const beforeZoom = (await canvasHash(page)).hash;
    await page.mouse.move(cx, cy);
    await page.mouse.wheel(0, -480);
    await expect.poll(async () => (await canvasHash(page)).hash).not.toBe(beforeZoom);
    const beforeDrag = (await canvasHash(page)).hash;
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await page.mouse.move(cx - 80, cy - 40, { steps: 6 });
    await page.mouse.up();
    await expect.poll(async () => (await canvasHash(page)).hash).not.toBe(beforeDrag);
    await expect(drawer).toBeVisible(); // 지도 조작이 서랍을 닫지 않는다
});

// ---- 첫걸음 바로가기(D21) — 서랍에서 실제 화면으로 간다 ------------------------------------------------------------
const FIRST_STEP_TARGETS: ReadonlyArray<readonly [string, RegExp, string]> = [
    ['create', /\/game\/(pep\/)?join$/, '장수 생성'],
    ['enlist', /\/game(\/pep)?$/, '작전실'],
    ['dispatch', /\/game\/(pep\/)?court\?tab=orders$/, '조정'],
    ['work', /\/game\/(pep\/)?territory$/, '배치 · 방침 · 공사'],
    ['employ', /\/game(\/pep)?$/, '작전실'],
    ['march', /\/game(\/pep)?$/, '작전실'],
    ['battle', /\/game\/(pep\/)?corps\/battle$/, '전투 · 부재 대비'],
];

async function followFirstStep(page: Page, info: import('@playwright/test').TestInfo, key: string, explanationId?: string, inputs = false) {
    await shellRoutes(page, { serverScoped: false, hasGeneral: key !== 'create' });
    if (inputs) await shortcutInputs(page);
    await page.goto('/game/retinue/yuedan?help=start', { waitUntil: 'domcontentloaded' });
    const go = page.locator(DRAWER).locator(`[data-first-step-go="${key}"]`);
    await expect(go, key).toBeVisible({ timeout: 60_000 });
    if (explanationId) await expect(go.locator('..')).toHaveAttribute('data-first-step-id', explanationId);
    await press(go, info);
    await expect(page.locator(DRAWER), key).toHaveCount(0);
}

// Local contract-shaped fixtures for this shortcut flow; POSTs are denied so navigation evidence never claims execution.
const DENIED_SHORTCUT = '검증용 대역에서 접수하지 않았습니다.';
async function shortcutInputs(page: Page) {
    const stock = { money: 0, grain: 0, material: 0, metal: 0, horse: 0 };
    const reads: Record<string, unknown> = {
        '/reserved-commands': { result: true, generalId: 7, slots: [] },
        '/commands/enlistment-options': { result: true, inputId: 'action.enlist', maxReservedTurns: 12,
            options: [{ mode: 'GENERAL', targetId: 8, label: '검증용 주공', availability: { status: 'AVAILABLE' } }] },
        '/deploy/options': { available: true, maxReservedTurns: 12,
            bugoks: [{ id: 7, name: '검증용 부곡', troops: 0, available: true }], destinations: [{ provinceId: 'B', name: '검증용 목적지' }] },
        '/commands/move-options': { inputId: 'action.move', available: true, destinations: [{ provinceId: 'B', name: '검증용 목적지', available: true }] },
        '/commands/search-options': { inputId: 'action.search', available: true, undiscoveredCount: 1, targets: [] },
        '/commands/employ-options': { inputId: 'action.employ', available: true, targets: [{ generalId: 8, name: '검증용 인물', available: true }] },
        '/commands/dispatch-options': { result: false, code: 'NOT_LORD', reason: '주공만 발령할 수 있습니다.',
            targets: [], counties: [], queued: null } satisfies DispatchOptionsResponse,
        // 조정(P-K01) 화면의 나머지 읽기 — K4 court.spec 과 같은 꼴(받은 요청 띠만 이 시험의 대상).
        '/commands/political-consent-options': [],
        '/commands/legacy-court-options': { inputId: 'court.moveCapital', available: false, reason: '군주만 할 수 있습니다.', choices: [] },
        '/retinue': { status: 'READY', renown: 30, costSum: 0, overCapacity: false, people: [], units: [] },
        '/commands/dispatches': { result: true, dispatches: [{ dispatchId: 'shortcut-dispatch', issuerId: 8, targetId: 7, countyId: 30,
            issuerLabel: '검증용 주공', targetLabel: '하후돈', countyLabel: '검증용 현', status: 'PENDING',
            issuedAt: { year: 200, month: 3, phase: 1 }, dueAt: { year: 200, month: 4, phase: 1 } }] },
        '/works': { status: 'READY', counties: [{ countyId: 30, provinceId: 'B', provinceIds: ['B'], name: '검증용 현', commanderyName: null,
            warehouse: null, active: null, completed: [], startable: [{ work: 'IRRIGATION', label: '수리', available: true, blocked: null, cost: stock, estimatedPhases: 1 }] }] },
    };
    await page.route((url) => url.pathname.startsWith('/api/game/api/'), async (route) => {
        const path = new URL(route.request().url()).pathname.replace('/api/game/api', '');
        if (route.request().method() === 'POST') return route.fulfill({ json: { status: 'BLOCKED', code: 'INVALID_INPUT', reason: DENIED_SHORTCUT } });
        if (path in reads) return route.fulfill({ json: reads[path] });
        return route.fallback();
    });
}

test.describe('첫걸음 바로가기', () => {
    test('서랍 「첫걸음」 — 44 · 덮임 0 · 넘침 0, 가입은 게이트웨이 회원 가입 주소', { tag: [BOTH] }, async ({ page }) => {
        await openShellWithHelp(page, '/game/retinue/yuedan?help=start');
        const drawer = page.locator(DRAWER);
        await expect(drawer.getByRole('list', { name: '첫걸음 8단계' })).toBeVisible();
        expect(await drawer.locator('[data-first-step-id]').evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-first-step-id')))).toEqual([
            'tutorial.signup', 'tutorial.createGeneral', 'tutorial.enlist', 'tutorial.dispatch',
            'tutorial.work', 'tutorial.employ', 'tutorial.march', 'tutorial.battle',
        ]);
        expect(await smallTouchTargets(page, DRAWER)).toEqual([]);
        expect(await coveredIn(page, DRAWER)).toEqual([]);
        expect(await titleOnlyInfo(page, DRAWER)).toEqual([]);
        await expectNoHorizontalOverflow(page);
        // 가입은 게임 앱 밖(게이트웨이 /join) — 이 스모크에는 게이트웨이가 없어 주소만 본다(페이지 존재는 help-lib 시험).
        await expect(drawer.locator('[data-first-step-go="register"]')).toHaveAttribute('href', /\/join$/);
    });

    for (const [key, url, title] of FIRST_STEP_TARGETS) {
        test(`${key}: 바로가기의 실제 본문이 보인다`, { tag: [BOTH] }, async ({ page }, info) => {
            await followFirstStep(page, info, key);
            await expect(page, key).toHaveURL(url);
            await expect(page.getByRole('main', { name: '게임 콘텐츠' }).getByRole('heading', { name: title, exact: true }), key).toBeVisible();
            await expect(page.getByText('This page could not be found'), key).toHaveCount(0);
            if (key === 'battle') {
                await expect(page.getByRole('region', { name: '내 전투', exact: true })).toContainText('전투가 열리지 않습니다(서버 준비 중)');
                await expect(page.getByRole('heading', { name: '부재 대비', exact: true })).toBeVisible();
                await expect(page.getByText('감찰부', { exact: true })).toHaveCount(0);
            }
        });
    }

    const personalInputs = [
        ['enlist', 'tutorial.enlist', 'action.enlist', '출사', '출사 예약', { mode: 'GENERAL', targetId: 8 }],
        ['march', 'tutorial.march', 'action.deploy', '출병', '출병 예약', { bugokIds: [7], destinationProvinceId: 'B' }],
        ['march', 'tutorial.march', 'action.move', '이동', '이동 예약', { destinationProvinceId: 'B' }],
        ['employ', 'tutorial.employ', 'action.search', '인재탐색', '인재탐색 예약', {}],
        ['employ', 'tutorial.employ', 'action.employ', '등용', '등용 예약', { targetGeneralId: 8 }],
    ] as const;
    for (const [key, explanationId, inputId, label, submitLabel, args] of personalInputs) {
        test(`${explanationId} → ${inputId}: 실제 예약 입력을 고른다`, { tag: [BOTH] }, async ({ page }, info) => {
            await followFirstStep(page, info, key, explanationId, true);
            await expect(page.getByRole('heading', { name: '명령 목록 12순', exact: true })).toBeVisible();
            await press(page.getByRole('button', { name: '+ 예약', exact: true }).first(), info);
            const dialog = page.getByRole('dialog', { name: '명령', exact: true });
            const action = dialog.getByRole('combobox', { name: '개인 행동', exact: true });
            await action.selectOption({ label });
            await expect(action).toHaveValue(inputId);
            await expect(dialog.getByRole('heading', { name: label, exact: true })).toBeVisible();
            if (inputId === 'action.enlist') await dialog.getByRole('combobox', { name: '출사 대상' }).selectOption('0');
            if (inputId === 'action.deploy') {
                await dialog.getByRole('checkbox', { name: '검증용 부곡 · 0명' }).check();
                await dialog.getByRole('combobox', { name: '출병 목적지' }).selectOption('B');
            }
            if (inputId === 'action.move') await dialog.getByRole('combobox', { name: '목적 省' }).selectOption('B');
            if (inputId === 'action.employ') await dialog.getByRole('combobox', { name: '대상 인물' }).selectOption('8');
            const sent = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === `/api/game/api/command/${inputId}`);
            await press(dialog.getByRole('button', { name: submitLabel, exact: true }), info);
            const request = await sent;
            expect(request.postDataJSON()).toEqual(args);
            expect(new URL(request.url()).searchParams.get('generalId')).toBe('7');
            expect(new URL(request.url()).searchParams.get('turnIdx')).toBe('0');
            await expect(dialog.getByRole('alert')).toHaveText(DENIED_SHORTCUT);
        });
    }

    test('tutorial.dispatch → court.dispatchReply: 받은 발령의 실제 수락 · 거절 입력', { tag: [BOTH] }, async ({ page }, info) => {
        const paths = new Set(['/api/game/api/front-info', '/api/game/api/commands/dispatch-options', '/api/game/api/commands/dispatches']);
        const requests: string[] = [];
        const responses: Array<Promise<unknown>> = [];
        const errors: string[] = [];
        page.on('request', (request) => {
            const url = new URL(request.url());
            if (paths.has(url.pathname)) requests.push(`${request.method()} ${url.pathname}${url.search}`);
        });
        page.on('response', (response) => {
            const url = new URL(response.url());
            if (paths.has(url.pathname)) responses.push(response.json().then(
                (body: unknown) => ({ path: `${url.pathname}${url.search}`, status: response.status(), body }),
                () => ({ path: `${url.pathname}${url.search}`, status: response.status(), body: 'Not JSON' }),
            ));
        });
        page.on('pageerror', (error) => errors.push(error.message));
        try {
            await followFirstStep(page, info, 'dispatch', 'tutorial.dispatch', true);
            await expect(page).toHaveURL(/\/game\/(pep\/)?court\?tab=orders$/);
            await expect(page.getByRole('main', { name: '게임 콘텐츠' }).getByRole('heading', { name: '조정', exact: true })).toBeVisible();
            // 조정(P-K01): 데스크톱은 「받은 요청」 칸, 모바일은 「조정 결정」 목록의 「받은 요청」을 눌러 여는 시트.
            if (isMobile(info)) await press(page.getByRole('list', { name: '조정 결정' }).getByRole('button').first(), info);
            const band = isMobile(info) ? page.getByRole('dialog', { name: '받은 요청' }) : page.getByRole('region', { name: '받은 요청' });
            const card = band.locator('article').filter({ hasText: '검증용 주공' });
            await expect(card).toBeVisible();
            for (const [label, accept] of [['수락', true], ['거절', false]] as const) {
                const sent = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === '/api/game/api/commands/court/dispatchReply');
                await press(card.getByRole('button', { name: label, exact: true }), info);
                const request = await sent;
                expect(request.postDataJSON()).toEqual({ dispatchId: 'shortcut-dispatch', accept });
                expect(new URL(request.url()).searchParams.get('generalId')).toBe('7');
                // 대역은 접수하지 않는다 — 누른 단추의 사유 시트가 그 사유로 열린다(실행했다고 말하지 않는다).
                await expect(page.getByText(DENIED_SHORTCUT).first()).toBeVisible();
                await page.keyboard.press('Escape');
            }
        } finally {
            // Keep read failures and runtime errors in the normal smoke log even if later phases replace artifacts.
            const evidence = JSON.stringify({ url: page.url(), requests, responses: await Promise.all(responses), errors,
                received: await page.getByRole('region', { name: '받은 요청' }).allTextContents(),
                alerts: await page.getByRole('alert').allTextContents(), statuses: await page.getByRole('status').allTextContents() });
            console.info('First-step dispatch evidence:', evidence);
            await info.attach('first-step-dispatch', { body: evidence, contentType: 'application/json' });
        }
    });

    test('tutorial.work → work.start: 도착한 공사 칸에서 현 · 공사를 고른다', { tag: [BOTH] }, async ({ page }, info) => {
        await followFirstStep(page, info, 'work', 'tutorial.work', true);
        await expect(page.getByRole('heading', { name: '공사', exact: true })).toBeVisible();
        const start = page.getByRole('button', { name: '수리', exact: true });
        await expect(start).toBeEnabled();
        const sent = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === '/api/game/api/commands/work/start');
        await press(start, info);
        const request = await sent;
        expect(request.postDataJSON()).toEqual({ countyId: 30, work: 'IRRIGATION' });
        expect(new URL(request.url()).searchParams.get('generalId')).toBe('7');
        await expect(page.getByText(DENIED_SHORTCUT, { exact: true })).toBeVisible();
    });
});
