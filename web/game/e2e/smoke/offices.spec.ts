// 관직 · 봉신(P-K03 · P-K04) 골격 — /game/court/offices 를 백엔드 없이 합성 로그인 · front-info 로 돈다(게임 읽기는 503).
// 두 프로필(@both): 「그려짐」(하위 탭에서 관직 · 봉신이 지금 화면 · 보드 칸이 서버 대기로 남음 · 누를 영역 44 · title 0 · disabled 0 · 넘침 0 · 배치)과
// 「조작됨」(탭을 눌러 추천 · 자칭 · 중앙 관직 · 봉신으로 바뀜)을 따로 본다. 입력 단추는 원장 행이 없어 0개다.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';

async function open(page: Page, nationId = 1) {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const path = new URL(route.request().url()).pathname.slice(API.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId, officerLevel: 5, permission: 0, showSecret: false },
                nation: nationId ? { id: 1, name: '조조', color: '#4f7fbf' } : null, city: null, recentRecord: {},
            });
        }
        return json(route, 503, {});
    });
    await page.goto('/game/court/offices', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '관직 · 봉신' })).toBeVisible({ timeout: 60_000 });
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

async function rules(page: Page) {
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

async function serverWaits(page: Page): Promise<string[]> {
    return page.locator(`${MAIN} [role="tabpanel"] [data-server-wait]`).evaluateAll((els) => els.map((el) => el.getAttribute('data-server-wait') ?? ''));
}

test.describe('관직 · 봉신', () => {
    test('그려짐: 지방 관직 — 보드 칸 셋이 서버 대기(K8-03 · K8-03 · K8-02), 입력 단추 0, 데스크톱은 두 열 · 모바일은 한 열', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        await expect(page.getByRole('navigation', { name: '하위 화면' }).getByRole('link', { name: '관직 · 봉신' })).toHaveAttribute('aria-current', 'page');
        expect(await serverWaits(page)).toEqual(['K8-03', 'K8-03', 'K8-02']);
        await expect(page.getByText('현령은 배치 · 발령으로 정합니다')).toBeVisible();
        await expect(page.locator(`${MAIN} [data-input-id]`)).toHaveCount(0);
        await rules(page);
        const [left, right] = await Promise.all([
            page.getByRole('region', { name: '관할과 앉은 사람' }).boundingBox(),
            page.getByRole('region', { name: '고른 관할' }).boundingBox(),
        ]);
        if (isMobile(testInfo)) expect(Math.round(right!.x)).toBe(Math.round(left!.x)); // 한 열
        else {
            expect(Math.round(right!.y)).toBe(Math.round(left!.y)); // 두 열 — 나란히
            expect(Math.round(left!.width)).toBe(440);
        }
    });

    test('조작됨: 탭 → 추천 · 자칭(K8-05) · 중앙 관직(K8-05) · 봉신(K8-04 · K8-02 · K8-17)', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        const cases: Array<[string, string[]]> = [['추천 · 자칭', ['K8-05']], ['중앙 관직', ['K8-05']], ['봉신', ['K8-04', 'K8-02', 'K8-17']], ['지방 관직', ['K8-03', 'K8-03', 'K8-02']]];
        for (const [tab, rows] of cases) {
            await press(page.getByRole('tab', { name: tab }), testInfo);
            await expect(page.getByRole('tab', { name: tab })).toHaveAttribute('aria-selected', 'true');
            await expect(page.getByRole('tabpanel', { name: tab })).toBeVisible();
            expect(await serverWaits(page)).toEqual(rows);
            await rules(page);
        }
    });

    test('그려짐: 재야 — 「세력에 속해야 관직이 있습니다」만', { tag: [BOTH] }, async ({ page }) => {
        await open(page, 0);
        await expect(page.getByText('세력에 속해야 관직이 있습니다')).toBeVisible();
        await expect(page.getByRole('tablist', { name: '관직 · 봉신 보기' })).toHaveCount(0);
        await rules(page);
    });
});
