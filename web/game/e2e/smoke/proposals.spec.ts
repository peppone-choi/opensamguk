// 참모 제안(P-K05) 골격 — /game/court/proposals 를 백엔드 없이 합성 로그인 · front-info 로 돈다(게임 읽기는 503).
// 두 프로필(@both): 조정 하위 탭에서 참모 제안이 지금 화면 · 목록이 서버 대기(K8-06) · 안내 세 줄 · 단추 0 · 누를 영역 44 · title 0 · disabled 0 · 넘침 0 ·
// 배치(데스크톱 오른쪽 열 460 · 모바일은 고른 제안 칸 없이 목록 → 안내 — 보드 MProposals).
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, smallTouchTargets, titleOnlyInfo } from '../support/parity';

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
    await page.goto('/game/court/proposals', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '참모 제안' })).toBeVisible({ timeout: 60_000 });
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

test('참모 제안 골격: 서버 대기(K8-06) · 안내 · 단추 0 · 규칙 · 배치', { tag: [BOTH] }, async ({ page }, testInfo) => {
    await open(page);
    await expect(page.getByRole('navigation', { name: '하위 화면' }).getByRole('link', { name: '참모 제안' })).toHaveAttribute('aria-current', 'page');
    const list = page.getByRole('region', { name: '이번 순 제안' });
    const detail = page.getByRole('region', { name: '고른 제안' });
    const guide = page.getByRole('region', { name: '알아 둘 것' });
    await expect(list.locator('[data-server-wait="K8-06"] .os-status--waiting')).toBeVisible();
    await expect(guide).toContainText('거부 · 만료된 제안은 다시 오지 않습니다');
    await expect(page.locator(`${MAIN} [data-input-id]`)).toHaveCount(0);
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
    const [l, g] = await Promise.all([list.boundingBox(), guide.boundingBox()]);
    if (isMobile(testInfo)) {
        await expect(detail).toBeHidden();
        expect(Math.round(g!.x)).toBe(Math.round(l!.x));
        expect(g!.y).toBeGreaterThan(l!.y);
    } else {
        await expect(detail.locator('[data-server-wait="K8-06"] .os-status--waiting')).toBeVisible();
        const d = await detail.boundingBox();
        expect(Math.round(d!.y)).toBe(Math.round(l!.y));
        expect(Math.round(d!.width)).toBe(460);
        expect(l!.width).toBeGreaterThan(d!.width);
    }
});
