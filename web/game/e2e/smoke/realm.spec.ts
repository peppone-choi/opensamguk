// 세력(P-K10) — /game/court/realm 을 백엔드 없이 합성 자료로 돈다(로그인 · front-info · 세력 요약 · 현 목록 합성, 나머지 게임 읽기는 503).
// 두 프로필(@both): 「그려짐」(조정 하위 탭에서 세력이 지금 화면 · 요약 띠 값 · 현 목록 · 누를 영역 44 · title 0 · 가로 넘침 0)과
// 「조작됨」(탭을 눌러 정체성 · 제도 · 편제 전통의 서버 대기로 바뀜 · 창고망으로 이동)을 따로 본다.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
const SUMMARY = {
    status: 'READY',
    nation: { id: 1, name: '조조', color: '#4f7fbf' },
    lord: { generalId: 1, name: '조조', portrait: { picture: null, imageServer: 0 } },
    capitalCityId: 11, countyCount: 3, retinueCount: 12,
    stockTotal: { money: 12000, grain: 34000, iron: 10, timber: 20, horses: 30 },
    population: 123456, troops: { city: 5000, bugok: 1200 },
};
const COUNTIES = {
    status: 'READY', scope: 'NATION', commandery: null, period: 'GAME_MONTH', basis: 'CURRENT_STATE_FORECAST', stamp: null,
    counties: [
        { cityId: 12, name: '양적현', commanderyId: 'c1', visibility: 'FULL', income: { money: 300, grain: 900 } },
        { cityId: 11, name: '허현', commanderyId: 'c1', visibility: 'FULL', income: { money: 500, grain: 1500 } },
        { cityId: 13, name: '언릉현', commanderyId: 'c1', visibility: 'FOG', income: null },
    ],
};

async function serve(page: Page, summary: unknown = SUMMARY) {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const path = new URL(route.request().url()).pathname.slice(API.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 5, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/nation/summary') return json(route, 200, summary);
        if (path === '/counties') return json(route, 200, COUNTIES);
        return json(route, 503, {});
    });
}

async function open(page: Page, summary?: unknown) {
    await serve(page, summary);
    await page.goto('/game/court/realm', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '세력' })).toBeVisible({ timeout: 60_000 });
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

async function rules(page: Page) {
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

test.describe('세력', () => {
    test('그려짐: 요약 띠(서버 값 · 창고 합) · 현 목록(수도 맨 위) · 하위 탭에서 세력이 지금 화면', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        await expect(page.getByRole('navigation', { name: '하위 화면' }).getByRole('link', { name: '세력' })).toHaveAttribute('aria-current', 'page');
        const band = page.getByRole('region', { name: '세력 요약' });
        await expect(band).toContainText('군주 조조 · 수도 허현');
        for (const t of ['123,456', '성 5,000 · 부곡 1,200', '금 12,000 · 쌀 34,000']) await expect(band).toContainText(t);
        await expect(band).toContainText('창고 합');
        const rows = page.getByRole('list', { name: '다스리는 현' }).getByRole('listitem');
        await expect(rows).toHaveCount(3);
        await expect(rows.first()).toContainText('허현');
        await expect(rows.first()).toContainText('수도');
        await expect(rows.nth(2)).toContainText('—');
        await rules(page);
        if (isMobile(testInfo)) {
            // 좁은 폭: 요약 값은 한 줄에 둘씩 쌓이고 창고망 단추는 띠 폭 가득.
            const [b, link] = await Promise.all([band.boundingBox(), band.getByRole('link', { name: '창고망 보기' }).boundingBox()]);
            expect(Math.round(link!.width)).toBe(Math.round(b!.width) - 26); // 띠 테두리 1 · 1 + 여백 12 · 12
        }
    });

    test('조작됨: 탭 → 서버 대기 · 창고망 보기로 이동', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        for (const [tab, title] of [['정체성', '정체성은 아직 없습니다'], ['제도', '제도 확산이 아직 없습니다'], ['편제 전통', '편제 전통은 아직 없습니다']]) {
            await press(page.getByRole('tab', { name: tab }), testInfo);
            await expect(page.getByRole('tab', { name: tab })).toHaveAttribute('aria-selected', 'true');
            await expect(page.getByRole('tabpanel', { name: tab })).toContainText(title);
        }
        await rules(page);
        await press(page.getByRole('tab', { name: '현 목록' }), testInfo);
        await expect(page.getByRole('list', { name: '다스리는 현' })).toBeVisible();
        await press(page.getByRole('link', { name: '창고망 보기' }), testInfo);
        await expect(page).toHaveURL(/\/territory\/supply/);
    });

    test('그려짐: 재야 — 빈 상태, 탭 없음', { tag: [BOTH] }, async ({ page }) => {
        await open(page, { status: 'NO_NATION', nation: null, lord: null, capitalCityId: null, countyCount: null, retinueCount: null, stockTotal: null, population: null, troops: null });
        await expect(page.getByText('소속 세력이 없습니다')).toBeVisible();
        await expect(page.getByRole('tablist', { name: '세력 보기' })).toHaveCount(0);
        await rules(page);
    });
});
