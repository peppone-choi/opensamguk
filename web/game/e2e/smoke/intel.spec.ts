// 시야 · 첩보(P-C06) — /game/corps/intel 을 백엔드 없이 합성 자료로 돈다(로그인 · front-info 합성, 나머지 게임 읽기는 503).
// 두 프로필(@both): 목록 안 누를 영역 44 · 네이티브 disabled 0 · title 0 · 가로 넘침 0 · 영어 원문 0, 단계 글자 칩,
// 역정보 표식 없음(「가짜 · 역정보 · 의심」 0), 「첩보」 → 그 군을 미리 고른 작전실 명령 흐름, 막힌 첩보는 사유 시트.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
const PANEL = '[data-testid="intel-panel"]';

async function serve(page: Page) {
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
        if (path === '/visibility') {
            return json(route, 200, { status: 'READY', commanderies: [
                { no: 1, id: 'c1', name: '영천군', tier: 'FULL' }, { no: 2, id: 'c2', name: '진류군', tier: 'INTEL', ageTurns: 3 },
                { no: 3, id: 'c3', name: '양국', tier: 'FOG' },
            ] });
        }
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

async function open(page: Page) {
    await serve(page);
    await page.goto('/game/corps/intel', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: '시야 · 첩보', exact: true })).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole('region', { name: '첩보', exact: true })).toBeVisible({ timeout: 60_000 });
}

test.describe('시야 · 첩보', () => {
    test('규칙: 44 · disabled 0 · title 0 · 넘침 0 · 영어 원문 0 · 역정보 표식 없음', { tag: [BOTH] }, async ({ page }) => {
        await open(page);
        await expect(page.getByRole('region', { name: '첩보', exact: true })).toContainText('3순 전 첩보');
        await expect(page.getByRole('region', { name: '안 보임' })).toContainText('양국');
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
});
