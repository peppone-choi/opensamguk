// 도움말 독립 페이지(/game/help, P-A01) 스모크 — 도움말 API 대역(help-api.ts, 저장소 data/help)으로 백엔드 없이 돈다.
// e2e/smoke 규칙(support/parity.ts): @both = 데스크톱 · 모바일(390 × 844 터치) 같은 흐름, @mobile-only. 누르기는 press(모바일 = 탭).
import { expect, test, type Page } from '@playwright/test';
import { BOTH, MOBILE_ONLY, expectCenterHitsMap, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo, coveredTargets } from '../support/parity';
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

async function shellRoutes(page: Page, options: { serverScoped?: boolean; hasGeneral?: boolean; unaffiliated?: boolean } = {}) {
    const baseURL = test.info().project.use.baseURL ?? 'http://localhost:3001';
    // The normal smoke app has no SERVER_ID; only address assertions need a server cookie.
    await page.context().addCookies([{ name: 'sam_server', value: options.serverScoped === false ? '' : 'pep', url: baseURL,
        ...(options.serverScoped === false ? { expires: 1 } : {}) }]);
    await serveHelpApi(page);
    // 뒤에 건 route 가 먼저 받는다 — front-info · 턴 루프 읽기만 셸 몫으로 가로챈다.
    await page.route((url) => url.pathname.endsWith('/front-info'), (r) => r.fulfill({ json: {
        result: true,
        global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
        // unaffiliated = 재야(소속 없음) — 출사 화면(/join)은 소속이 있으면 작전실로 돌려보낸다.
        general: { hasGeneral: options.hasGeneral ?? true, generalId: 7, name: '하후돈', nationId: options.unaffiliated ? 0 : 1, officerLevel: options.unaffiliated ? 0 : 1, permission: 0, showSecret: false },
        nation: options.unaffiliated ? null : { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
    } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
}

/** 덮임 — 공용 coveredTargets(support/parity, 한 화면씩 내려가며 · 붙박인 층은 스크롤해 다시)로 옮겼다(K10 10-02). */
const coveredIn = (page: Page, selector: string): Promise<string[]> => coveredTargets(page.locator(selector).first(), 'a, button, input');

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

// ---- 도움말 띠(InputHelpStrip) — 부품 시험실(/parts-lab, CI 빌드 플래그)에서 잰다 -------------------------------------
test('도움말 띠 — 설명 글이 남는 폭을 쓰고 「초안」 칩은 제 크기, 누를 것 44 · 넘침 0', { tag: [BOTH] }, async ({ page }) => {
    await serveHelpApi(page);
    await page.goto('/parts-lab', { waitUntil: 'domcontentloaded' });
    const strip = page.getByTestId('lab-help-strip').locator('[data-help-strip]');
    await expect(strip).toBeVisible({ timeout: 60_000 });
    const text = strip.locator('[data-help-strip-text]');
    await expect(text).toContainText('섬길 주공');
    const box = (await strip.boundingBox())!;
    const textBox = (await text.boundingBox())!;
    const chipBox = (await strip.getByText('초안', { exact: true }).boundingBox())!;
    // 칩은 글자 크기만큼(K5: `.strip > span` 이 칩까지 늘려 설명이 몇 글자마다 꺾였다)
    expect(chipBox.width).toBeLessThan(60);
    // 설명은 띠 폭의 대부분을 쓴다 — 좁으면 칩 · 단추가 다음 줄로 넘어간다
    expect(textBox.width).toBeGreaterThanOrEqual(box.width * 0.6);
    expect(await smallTouchTargets(page, '[data-help-strip]')).toEqual([]);
    await expectNoHorizontalOverflow(page);
});

// ---- 첫걸음 바로가기(D21) — 서랍에서 실제 화면으로 간다 ------------------------------------------------------------
const FIRST_STEP_TARGETS: ReadonlyArray<readonly [string, RegExp, string]> = [
    ['create', /\/game\/(pep\/)?create$/, '내 장수를 만든다'],
    ['enlist', /\/game\/(pep\/)?join$/, '섬길 주공을 고른다'],
    ['dispatch', /\/game\/(pep\/)?court\?tab=orders$/, '조정'],
    ['work', /\/game\/(pep\/)?territory\?view=work$/, '영지'],
    ['employ', /\/game(\/pep)?\?do=action\.search$/, '작전실'],
    ['march', /\/game(\/pep)?\?do=action\.deploy$/, '작전실'],
    ['battle', /\/game\/(pep\/)?corps\/battle$/, '전투 · 부재 대비'],
];

async function followFirstStep(page: Page, info: import('@playwright/test').TestInfo, key: string, explanationId?: string, inputs = false) {
    await shellRoutes(page, { serverScoped: false, hasGeneral: key !== 'create', unaffiliated: key === 'enlist' });
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
            if (key === 'create') await expect(page.getByText('장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중')).toBeVisible();
            if (key === 'employ' || key === 'march') await expect(page.getByTestId('command-flow')).toBeVisible();
            if (key === 'battle') {
                await expect(page.getByRole('region', { name: '내 전투', exact: true })).toContainText('전투가 열리지 않습니다(서버 준비 중)');
                await expect(page.getByRole('heading', { name: '부재 대비', exact: true })).toBeVisible();
                await expect(page.getByText('감찰부', { exact: true })).toHaveCount(0);
            }
        });
    }

    // 출사는 출사 화면(/join), 등용 · 행군은 작전실 명령 흐름(K6 #1125, `?do=`). 대역은 POST 를 접수하지 않는다(BLOCKED).
    test('tutorial.enlist → action.enlist: 출사 화면에서 묶음 · 주공을 고르고 출사 예약', { tag: [BOTH] }, async ({ page }, info) => {
        await followFirstStep(page, info, 'enlist', 'tutorial.enlist', true);
        const screen = page.getByTestId('enlist-screen');
        await expect(screen.getByRole('heading', { name: '섬길 주공을 고른다' })).toBeVisible();
        await press(screen.getByRole('radiogroup', { name: '출사 후보 묶음' }).getByRole('radio', { name: /^장수/ }), info);
        await press(screen.getByRole('option', { name: /검증용 주공/ }).first(), info);
        const sent = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === '/api/game/api/command/action.enlist');
        await press(screen.getByRole('button', { name: '출사 예약', exact: true }), info);
        const request = await sent;
        expect(request.postDataJSON()).toEqual({ mode: 'GENERAL', targetId: 8 });
        expect(new URL(request.url()).searchParams.get('generalId')).toBe('7');
        expect(new URL(request.url()).searchParams.get('turnIdx')).toBe('0');
        await expect(page.getByText(DENIED_SHORTCUT).first()).toBeVisible();
    });

    const flowInputs = [
        // [단계, 설명 id, 입력, 바로가기가 연 입력, 고를 것, 보낼 본문]
        ['march', 'tutorial.march', 'action.deploy', 'action.deploy', [/검증용 부곡/, /검증용 목적지/], { bugokIds: [7], destinationProvinceId: 'B' }],
        ['march', 'tutorial.march', 'action.move', 'action.deploy', [/검증용 목적지/], { destinationProvinceId: 'B' }],
        ['employ', 'tutorial.employ', 'action.search', 'action.search', [], {}],
        ['employ', 'tutorial.employ', 'action.employ', 'action.search', [/검증용 인물/], { targetGeneralId: 8 }],
    ] as const;
    for (const [key, explanationId, inputId, opened, picks, args] of flowInputs) {
        test(`${explanationId} → ${inputId}: 작전실 흐름에서 실제 예약 입력을 고른다`, { tag: [BOTH] }, async ({ page }, info) => {
            await followFirstStep(page, info, key, explanationId, true);
            const flow = page.getByTestId('command-flow');
            await expect(flow).toBeVisible();
            await expect(flow.getByRole('heading', { name: /이번 순에 할 일/ })).toBeVisible();
            if (inputId !== opened) {
                // 같은 단계의 다른 입력 — 명령 목록에서 고른다. 데스크톱(≥1200)은 목록 열이 늘 떠 있고 「← 명령 목록」은 좁은 화면에만 있다.
                if (isMobile(info)) await press(flow.getByRole('button', { name: '← 명령 목록', exact: true }), info);
                else await expect(flow.getByRole('button', { name: '← 명령 목록', exact: true })).toBeHidden();
                await press(flow.getByRole('list', { name: '명령' }).locator(`[data-input-id="${inputId}"]`), info);
            }
            for (const pick of picks) await press(flow.getByRole('option', { name: pick }).first(), info);
            const submit = flow.locator(`[data-input-id="${inputId}"][data-input-status]`);
            await expect(submit).toHaveText(/순에 예약$/);
            const sent = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === `/api/game/api/command/${inputId}`);
            await press(submit, info);
            const request = await sent;
            expect(request.postDataJSON()).toEqual(args);
            expect(new URL(request.url()).searchParams.get('generalId')).toBe('7');
            expect(new URL(request.url()).searchParams.get('turnIdx')).toBe('0');
            await expect(page.getByText(DENIED_SHORTCUT).first()).toBeVisible();
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
            const openCard = async () => {
                if (isMobile(info)) await press(page.getByRole('list', { name: '조정 결정' }).getByRole('button').first(), info);
                const band = isMobile(info) ? page.getByRole('dialog', { name: '받은 요청' }) : page.getByRole('region', { name: '받은 요청' });
                const card = band.locator('article').filter({ hasText: '검증용 주공' });
                await expect(card).toBeVisible();
                return card;
            };
            for (const [label, accept] of [['수락', true], ['거절', false]] as const) {
                // 서버가 한 번 거절하면 그 요청 카드는 두 단추 모두 그 사유로 막힌다 — 단추마다 화면을 새로 연다.
                if (!accept) await page.reload({ waitUntil: 'domcontentloaded' });
                const card = await openCard();
                const sent = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === '/api/game/api/commands/court/dispatchReply');
                await press(card.getByRole('button', { name: label, exact: true }), info);
                const request = await sent;
                expect(request.postDataJSON()).toEqual({ dispatchId: 'shortcut-dispatch', accept });
                expect(new URL(request.url()).searchParams.get('generalId')).toBe('7');
                // 대역은 접수하지 않는다 — 누른 단추의 사유 시트가 그 사유로 열리고 두 단추가 막힌다(실행했다고 말하지 않는다).
                await expect(page.getByText(DENIED_SHORTCUT).first()).toBeVisible();
                await expect(card.getByRole('button', { name: label, exact: true })).toHaveAttribute('data-input-status', 'BLOCKED');
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
        // 영지(P-T01, K4 #1174): 바로가기 `?view=work`(K4 #1201) — 모바일도 「보기」가 처음부터 「공사」. 현 줄의 「새 공사」 → 시트에서 공사 → 「이 공사로」.
        if (isMobile(info)) await expect(page.getByRole('radiogroup', { name: '보기' }).getByRole('radio', { name: '공사' })).toBeChecked();
        const works = isMobile(info) ? page.getByRole('main', { name: '게임 콘텐츠' }) : page.getByRole('region', { name: '공사' });
        const row = works.getByRole('list', { name: '공사' }).getByRole('listitem').filter({ hasText: '검증용 현' });
        await press(row.getByRole('button', { name: '새 공사', exact: true }), info);
        const sheet = page.getByRole('dialog', { name: '검증용 현 공사' });
        await press(sheet.getByRole('option', { name: /수리/ }), info);
        const sent = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === '/api/game/api/commands/work/start');
        await press(sheet.getByRole('button', { name: '이 공사로', exact: true }), info);
        const request = await sent;
        expect(request.postDataJSON()).toEqual({ countyId: 30, work: 'IRRIGATION' });
        expect(new URL(request.url()).searchParams.get('generalId')).toBe('7');
        await expect(page.getByText(DENIED_SHORTCUT, { exact: true })).toBeVisible();
    });
});

// ---- 첫걸음 8단계 연속 걷기(GATE:7:04) ------------------------------------------------------------------------------
// 사람이 하듯 한 페이지에서 걷는다: 머리줄 「이 화면 도움말」 → 「첫걸음」 탭 → N단계 바로가기 → 도착 화면의 그 단계 입력 → 다시 머리줄.
// 장수 상태(없음 → 재야 → 소속)는 대역이 단계 경계에서 바꾸고 첫 화면(/game)부터 다시 연다 — 생성 · 출사가 됐다는 증거가 아니다.
// 대역은 POST 를 하나도 접수하지 않는다(BLOCKED) — 이 걷기가 증명하는 것은 「설명 → 실제 화면 → 그 입력이 서버로 간다」까지다.
test('첫걸음 8단계를 한 번에 걷는다 — 머리줄 「?」 → 첫걸음 → 화면 → 입력, 다시 머리줄에서 다음 단계', { tag: [BOTH] }, async ({ page }, info) => {
    test.setTimeout(180_000);
    const who = { serverScoped: false, hasGeneral: false, unaffiliated: true };
    await shellRoutes(page, who); // 대역 front-info 는 요청마다 who 를 읽는다
    await shortcutInputs(page);
    const main = page.getByRole('main', { name: '게임 콘텐츠' });
    const trail: Array<{ step: string; at: string; sent?: string }> = [];

    const openFirstSteps = async () => {
        await press(page.getByRole('link', { name: '이 화면 도움말' }), info);
        const drawer = page.locator(DRAWER);
        await press(drawer.getByRole('tab', { name: '첫걸음' }), info);
        await expect(drawer.getByRole('list', { name: '첫걸음 8단계' })).toBeVisible();
        return drawer;
    };
    const follow = async (key: string, explanationId: string, url: RegExp, title: string, drawer?: import('@playwright/test').Locator) => {
        const go = (drawer ?? await openFirstSteps()).locator(`[data-first-step-go="${key}"]`);
        await expect(go.locator('..'), key).toHaveAttribute('data-first-step-id', explanationId);
        await press(go, info);
        await expect(page.locator(DRAWER), key).toHaveCount(0);
        await expect(page, key).toHaveURL(url);
        await expect(main.getByRole('heading', { name: title, exact: true }), key).toBeVisible();
        await expect(page.getByText('This page could not be found'), key).toHaveCount(0);
        const at = new URL(page.url());
        trail.push({ step: key, at: `${at.pathname}${at.search}` });
    };
    /** 입력을 보내고 대역 거절까지 본 뒤, 열린 사유 · 시트를 닫기 단추로 닫는다(다음 단계는 머리줄에서 연다). */
    const send = async (path: string, act: () => Promise<void>) => {
        const sent = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === path);
        await act();
        const request = await sent;
        trail[trail.length - 1].sent = `${path} ${request.postData() ?? ''}`;
        await expect(page.getByText(DENIED_SHORTCUT).first()).toBeVisible();
        // 대화 상자(사유 · 받은 요청 시트)의 닫기만 — 명령 흐름 머리의 「닫기」(×)는 흐름을 닫으므로 누르지 않는다.
        const closers = page.getByRole('dialog').getByRole('button', { name: '닫기', exact: true }).filter({ visible: true });
        for (let i = 0; i < 3 && await closers.count() > 0; i += 1) await press(closers.last(), info);
        await expect(closers).toHaveCount(0);
        return request;
    };
    const restartAt = async (state: Partial<typeof who>) => {
        Object.assign(who, state);
        await page.goto('/game', { waitUntil: 'domcontentloaded' });
    };

    // 장수 없음 — 게임 입구에서 시작한다.
    await page.goto('/game', { waitUntil: 'domcontentloaded' });
    await expect(main.getByRole('heading', { name: '이 서버에서 시작한다' })).toBeVisible({ timeout: 60_000 });
    // 1 가입 — 게이트웨이(게임 앱 밖) 주소만 본다. 2 장수 생성 — 생성 서버 대기라 화면까지.
    const drawer = await openFirstSteps();
    await expect(drawer.locator('[data-first-step-id]').first()).toHaveAttribute('data-first-step-id', 'tutorial.signup');
    await expect(drawer.locator('[data-first-step-go="register"]')).toHaveAttribute('href', /\/join$/);
    trail.push({ step: 'register', at: 'gateway /join' });
    await follow('create', 'tutorial.createGeneral', /\/game\/create$/, '내 장수를 만든다', drawer);
    await expect(page.getByText('장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중')).toBeVisible();

    // 재야 장수 — 3 출사.
    await restartAt({ hasGeneral: true, unaffiliated: true });
    await expect(main.getByRole('heading', { name: '작전실', exact: true })).toBeVisible({ timeout: 60_000 }); // do 가 없으면 명령 흐름은 닫혀 있다
    await follow('enlist', 'tutorial.enlist', /\/game\/join$/, '섬길 주공을 고른다');
    const enlist = page.getByTestId('enlist-screen');
    await press(enlist.getByRole('radiogroup', { name: '출사 후보 묶음' }).getByRole('radio', { name: /^장수/ }), info);
    await press(enlist.getByRole('option', { name: /검증용 주공/ }).first(), info);
    expect((await send('/api/game/api/command/action.enlist', () => press(enlist.getByRole('button', { name: '출사 예약', exact: true }), info))).postDataJSON())
        .toEqual({ mode: 'GENERAL', targetId: 8 });

    // 소속 장수 — 4 발령부터 8 전투까지 문서를 다시 열지 않고 이어 간다.
    await restartAt({ unaffiliated: false });
    await expect(main.getByRole('heading', { name: '작전실', exact: true })).toBeVisible({ timeout: 60_000 });
    await follow('dispatch', 'tutorial.dispatch', /\/game\/court\?tab=orders$/, '조정');
    if (isMobile(info)) await press(page.getByRole('list', { name: '조정 결정' }).getByRole('button').first(), info);
    const band = isMobile(info) ? page.getByRole('dialog', { name: '받은 요청' }) : page.getByRole('region', { name: '받은 요청' });
    const card = band.locator('article').filter({ hasText: '검증용 주공' });
    expect((await send('/api/game/api/commands/court/dispatchReply', () => press(card.getByRole('button', { name: '수락', exact: true }), info))).postDataJSON())
        .toEqual({ dispatchId: 'shortcut-dispatch', accept: true });

    await follow('work', 'tutorial.work', /\/game\/territory\?view=work$/, '영지');
    // 영지(P-T01): `?view=work` 로 「공사」 칸이 열린다(모바일 「보기」도 「공사」) › 현 줄 「새 공사」 → 시트에서 수리 → 「이 공사로」. 거절 뒤 시트는 「그만두기」로 닫는다.
    if (isMobile(info)) await expect(page.getByRole('radiogroup', { name: '보기' }).getByRole('radio', { name: '공사' })).toBeChecked();
    const works = isMobile(info) ? main : page.getByRole('region', { name: '공사' });
    await press(works.getByRole('list', { name: '공사' }).getByRole('listitem').filter({ hasText: '검증용 현' }).getByRole('button', { name: '새 공사', exact: true }), info);
    const workSheet = page.getByRole('dialog', { name: '검증용 현 공사' });
    await press(workSheet.getByRole('option', { name: /수리/ }), info);
    expect((await send('/api/game/api/commands/work/start', () => press(workSheet.getByRole('button', { name: '이 공사로', exact: true }), info))).postDataJSON())
        .toEqual({ countyId: 30, work: 'IRRIGATION' });
    await press(workSheet.getByRole('button', { name: '그만두기', exact: true }), info);
    await expect(workSheet).toHaveCount(0);

    const flow = page.getByTestId('command-flow');
    const reserve = (inputId: string) => press(flow.locator(`[data-input-id="${inputId}"][data-input-status]`), info);
    await follow('employ', 'tutorial.employ', /\/game\?do=action\.search$/, '작전실');
    expect((await send('/api/game/api/command/action.search', () => reserve('action.search'))).postDataJSON()).toEqual({});

    // 흐름이 열린 채(인재탐색)로 같은 /game 에서 ?do= 만 바뀐다 — 사람이 6 → 7단계를 잇는 그대로.
    await expect(flow).toBeVisible();
    await follow('march', 'tutorial.march', /\/game\?do=action\.deploy$/, '작전실');
    await expect(flow.locator('[data-input-id="action.deploy"][data-input-status]')).toBeVisible();
    for (const pick of [/검증용 부곡/, /검증용 목적지/]) await press(flow.getByRole('option', { name: pick }).first(), info);
    expect((await send('/api/game/api/command/action.deploy', () => reserve('action.deploy'))).postDataJSON())
        .toEqual({ bugokIds: [7], destinationProvinceId: 'B' });

    await follow('battle', 'tutorial.battle', /\/game\/corps\/battle$/, '전투 · 부재 대비');
    await expect(page.getByRole('region', { name: '내 전투', exact: true })).toContainText('전투가 열리지 않습니다(서버 준비 중)');

    expect(trail.map((t) => t.step)).toEqual(['register', 'create', 'enlist', 'dispatch', 'work', 'employ', 'march', 'battle']);
    await info.attach('first-steps-walk', { body: JSON.stringify(trail, null, 2), contentType: 'application/json' });
});
