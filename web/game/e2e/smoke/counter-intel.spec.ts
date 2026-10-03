// 역정보(P-K06) 골격 — /game/stratagem/counter-intel 을 백엔드 없이 합성 로그인 · front-info 로 돈다(게임 읽기는 503).
// 두 프로필(@both): 「그려짐」(계책 하위 탭에서 역정보가 지금 화면 · 목록 · 상세가 서버 대기 K8-07 · 누를 영역 44 · title 0 · disabled 0 · 넘침 0 · 배치)과
// 「조작됨」(계책 덱으로 고리를 눌러 계책 덱으로 간다)을 따로 본다. 입력 단추는 원장 행이 없어 0개다.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';

async function open(page: Page) {
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
        return json(route, 503, {});
    });
    await page.goto('/game/stratagem/counter-intel', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '역정보' })).toBeVisible({ timeout: 60_000 });
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

async function rules(page: Page) {
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

test.describe('역정보', () => {
    test('그려짐: 목록 · 상세 서버 대기(K8-07) · 안내 · 입력 단추 0, 데스크톱은 두 열 · 모바일은 목록 → 안내', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        await expect(page.getByRole('navigation', { name: '하위 화면' }).getByRole('link', { name: '역정보' })).toHaveAttribute('aria-current', 'page');
        const list = page.getByRole('region', { name: '내가 건 역정보' });
        const guide = page.getByRole('region', { name: '알아 둘 것' });
        await expect(list.locator('[data-server-wait="K8-07"] .os-status--waiting')).toBeVisible();
        await expect(list).toContainText('상대 장수');
        await expect(guide).toContainText('상대는 이것이 가짜인 줄 모릅니다');
        await expect(page.locator(`${MAIN} [data-input-id]`)).toHaveCount(0);
        await rules(page);
        const detail = page.getByRole('region', { name: '고른 역정보' });
        const [l, g] = await Promise.all([list.boundingBox(), guide.boundingBox()]);
        if (isMobile(testInfo)) {
            await expect(detail).toBeHidden(); // 보드 MMisinfo — 목록 카드를 누르면 여는 자리
            expect(Math.round(g!.x)).toBe(Math.round(l!.x)); // 한 열
            expect(g!.y).toBeGreaterThan(l!.y); // 목록 → 안내
        } else {
            await expect(detail.locator('[data-server-wait="K8-07"] .os-status--waiting')).toBeVisible();
            const d = await detail.boundingBox();
            expect(Math.round(d!.y)).toBe(Math.round(l!.y)); // 두 열 나란히
            expect(Math.round(d!.width)).toBe(460); // 보드 grid2(460, …) — 오른쪽 열 460
            expect(l!.width).toBeGreaterThan(d!.width);
        }
    });

    test('조작됨: 「계책 덱으로」 → 계책 덱', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        await press(page.getByRole('region', { name: '알아 둘 것' }).getByRole('link', { name: '계책 덱으로' }), testInfo);
        await expect(page).toHaveURL(/\/game\/stratagem$/);
        await expect(page.getByRole('heading', { level: 2, name: '계책 덱' })).toBeVisible({ timeout: 60_000 });
    });
});
