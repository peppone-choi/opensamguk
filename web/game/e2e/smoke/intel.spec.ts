// 시야 · 첩보(P-C06) — /game/corps/intel 을 백엔드 없이 합성 자료로 돈다(로그인 · front-info 합성, 나머지 게임 읽기는 503).
// 두 프로필(@both): 목록 안 누를 영역 44 · 네이티브 disabled 0 · title 0 · 가로 넘침 0 · 영어 원문 0, 단계 글자 칩,
// 역정보 표식 없음(「가짜 · 역정보 · 의심」 0), 「첩보」 → 그 군을 미리 고른 작전실 명령 흐름, 막힌 첩보는 사유 시트.
// 시야 출처: 여섯 종류 이름 · 郡國 이름 · 반경, 알 수 없는 행은 「알 수 없음」, 날 id 없음, 서버 기록 수와 화면이 가린 줄 수는 따로.
// 출처는 READY 응답에서만 — 읽는 중 · 실패에는 출처 칸이 없고 다시 시도한 응답만 그린다.
// 이 파일은 경로 가로채기(route mock)로 돈다 — 실제 HTTP · DB 를 거친 QA 가 아니다.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
const PANEL = '[data-testid="intel-panel"]';
type Json = (route: Route, status: number, body: unknown) => Promise<void>;
const json: Json = (route, status, body) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });

const VISIBILITY = {
    status: 'READY', commanderies: [
        { no: 0, id: 'PARENT-0000', name: '하남윤', tier: 'FULL' },
        { no: 1, id: 'c1', name: '영천군', tier: 'FULL' }, { no: 2, id: 'c2', name: '진류군', tier: 'INTEL', ageTurns: 3 },
        { no: 3, id: 'c3', name: '양국', tier: 'FOG' },
    ],
    sources: [
        { kind: 'SELF', commanderyNo: 0, radius: 0, provinceId: '82828', refId: 7 },
        { kind: 'OWN_CORPS', commanderyNo: 1, radius: 0, provinceId: '82829', refId: 31 },
        { kind: 'RETINUE', commanderyNo: 2, radius: 0, refId: 4401 },
        { kind: 'TERRITORY', commanderyNo: 0, radius: 0 },
        { kind: 'SCOUT_POST', commanderyNo: 3, radius: 1, provinceId: '77001', refId: 9 },
        { kind: 'WATCHTOWER_BEACON', commanderyNo: 1, radius: 1, refId: 5501 },
        { kind: 'SPY_NET', commanderyNo: 3, radius: 1 },
    ],
    invalidSourceRecords: 1,
};

async function serve(page: Page, visibility: (route: Route) => Promise<void> = (route) => json(route, 200, VISIBILITY)) {
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
        if (path === '/visibility') return visibility(route);
        if (path === '/scout-options') {
            return json(route, 200, { status: 'READY', available: true, options: [
                { no: 2, id: 'c2', name: '진류군', tier: 'INTEL', available: true },
                { no: 3, id: 'c3', name: '양국', tier: 'FOG', available: false, code: 'TOO_FAR', reason: '너무 멉니다' },
            ] });
        }
        return json(route, 503, {});
    });
}

/** 화면 글자 속 영어 낱말(3글자 이상) — 서버 원문 · 코드 · 입력 id 가 글자로 새면 걸린다(battle.spec 과 같은 기준). */
async function englishWords(page: Page, root: string): Promise<string[]> {
    const text = await page.locator(root).first().innerText();
    return text.match(/[A-Za-z]{3,}/g) ?? [];
}

async function goto(page: Page) {
    await page.goto('/game/corps/intel', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: '시야 · 첩보', exact: true })).toBeVisible({ timeout: 60_000 });
}

async function open(page: Page) {
    await serve(page);
    await goto(page);
    await expect(page.getByRole('region', { name: '첩보', exact: true })).toBeVisible({ timeout: 60_000 });
}

const sourcesBox = (page: Page) => page.getByRole('region', { name: '내 시야 출처' });

test.describe('시야 · 첩보', () => {
    test('규칙: 44 · disabled 0 · title 0 · 넘침 0 · 영어 원문 0 · 역정보 표식 없음', { tag: [BOTH] }, async ({ page }) => {
        await open(page);
        await expect(page.getByRole('region', { name: '첩보', exact: true })).toContainText('3순 전 첩보');
        await expect(page.getByRole('region', { name: '안 보임' })).toContainText('양국');
        await expect(sourcesBox(page)).toBeVisible();
        await expect(page.locator(PANEL)).not.toContainText(/가짜|역정보|의심/);
        expect(await smallTouchTargets(page, PANEL)).toEqual([]);
        expect(await titleOnlyInfo(page, PANEL)).toEqual([]);
        expect(await page.locator(`${PANEL} :disabled`).count()).toBe(0);
        expect(await englishWords(page, PANEL)).toEqual([]);
        await expectNoHorizontalOverflow(page);
    });

    test('「첩보」는 그 군을 미리 고른 작전실 명령 흐름을 연다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        await press(page.getByRole('region', { name: '첩보', exact: true }).getByRole('button', { name: '첩보' }), testInfo);
        await expect(page).toHaveURL(/[?&]do=action\.scout\b/);
        await expect(page).toHaveURL(/[?&]target=commandery(%3A|:)c2\b/);
    });

    test('막힌 첩보는 누르면 서버 사유가 열리고 흐름으로 가지 않는다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        const blocked = page.getByRole('region', { name: '안 보임' }).getByRole('button', { name: '첩보' });
        await expect(blocked).toHaveAttribute('data-input-status', 'BLOCKED');
        await press(blocked, testInfo);
        await expect(page.getByRole('dialog', { name: /양국 첩보/ })).toContainText('너무 멉니다');
        await expect(page).toHaveURL(/\/game\/corps\/intel/);
    });

    test('시야 출처: 여섯 종류 · 郡國 이름 · 반경, 알 수 없는 행은 「알 수 없음」, 날 id 없음', { tag: [BOTH] }, async ({ page }) => {
        await open(page);
        const items = sourcesBox(page).getByRole('listitem');
        await expect(items).toHaveCount(7);
        const lines = await items.allInnerTexts();
        expect(lines.map((t) => t.replace(/\s+/g, ' ').trim())).toEqual([
            '내 위치 하남윤 · 반경 0칸', '내 군단 영천군 · 반경 0칸', '휘하 인물 진류군 · 반경 0칸',
            '우리 세력 영토 하남윤 · 반경 0칸', '정찰 배치 양국 · 반경 1칸', '망루·봉화 영천군 · 반경 1칸',
            '알 수 없는 출처 양국 · 반경 1칸',
        ]);
        await expect(sourcesBox(page)).toContainText('읽지 못한 출처 기록 1개');
        await expect(sourcesBox(page)).toContainText('모양이 어긋난 출처 1줄');
        await expect(sourcesBox(page)).not.toContainText(/82828|82829|77001|4401|5501|PARENT|SPY_NET/);
        await expectNoHorizontalOverflow(page);
    });

    test('읽는 중 · 실패에는 출처 칸이 없고, 다시 시도한 READY 응답의 출처만 그린다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        // 다시 시도 전의 읽기는 모두 붙잡았다가 실패로 끝낸다(세션이 범위를 다시 잡아 요청이 바뀌어도 같은 모양).
        let release!: () => void;
        const gate = new Promise<void>((resolve) => { release = resolve; });
        let failing = true;
        let calls = 0;
        await serve(page, async (route) => {
            calls += 1;
            const fail = failing;
            await gate;
            // 범위가 바뀌어 중단된 요청은 이미 끝났을 수 있다 — 그 응답은 버린다.
            await (fail ? json(route, 500, { error: { code: 'INTERNAL', message: 'Internal Server Error' } }) : json(route, 200, VISIBILITY)).catch(() => undefined);
        });
        await goto(page);
        await expect(page.locator(`${PANEL} [aria-busy="true"]`)).toBeAttached({ timeout: 60_000 });
        await expect(sourcesBox(page)).toHaveCount(0);
        release();
        const retry = page.locator(PANEL).getByRole('button', { name: /다시 시도/ });
        await expect(retry).toBeVisible({ timeout: 60_000 });
        await expect(sourcesBox(page)).toHaveCount(0);
        await expect(page.locator(PANEL)).not.toContainText(/Internal Server Error|INTERNAL/);
        expect(await smallTouchTargets(page, PANEL)).toEqual([]);
        failing = false;
        const before = calls;
        await press(retry, testInfo);
        await expect(sourcesBox(page)).toContainText('내 위치', { timeout: 60_000 });
        await expect(sourcesBox(page)).toContainText('읽지 못한 출처 기록 1개');
        expect(calls).toBeGreaterThan(before);
        await expectNoHorizontalOverflow(page);
    });
});
