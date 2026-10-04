// 시즌 결산(P-H05) 골격 — /game/records/season 을 백엔드 없이 합성 로그인 · front-info 로 돈다(게임 읽기는 503).
// 두 프로필(@both): 기록 하위 탭에서 시즌 결산이 지금 화면 · 값 칸은 서버 대기(K8-14) · 다음 시즌 안내 · 누를 영역 44 · title 0 · disabled 0 · 넘침 0 ·
// 배치(데스크톱 오른쪽 열 420 · 모바일은 결과 → 주요 인물 → 다음 시즌, 시즌 칸 없음 — 보드 MSeason).
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
    await page.goto('/game/records/season', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '시즌 결산' })).toBeVisible({ timeout: 60_000 });
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

test('시즌 결산 골격: 값 칸 서버 대기(K8-14) · 다음 시즌 안내 · 규칙 · 배치', { tag: [BOTH] }, async ({ page }, testInfo) => {
    await open(page);
    await expect(page.getByRole('navigation', { name: '하위 화면' }).getByRole('link', { name: '시즌 결산' })).toHaveAttribute('aria-current', 'page');
    const result = page.getByRole('region', { name: '시즌 결과' });
    const people = page.getByRole('region', { name: '주요 인물' });
    const next = page.getByRole('region', { name: '다음 시즌' });
    const facts = page.getByRole('region', { name: '시즌', exact: true });
    for (const region of [result, people]) await expect(region.locator('[data-server-wait="K8-14"] .os-status--waiting')).toBeVisible();
    await expect(next).toContainText('다음 시즌으로 넘어가지 않습니다');
    await expect(page.locator(MAIN)).not.toContainText('통일했습니다');
    await expect(page.locator(`${MAIN} [data-input-id]`)).toHaveCount(0);
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
    const [r, p, n] = await Promise.all([result.boundingBox(), people.boundingBox(), next.boundingBox()]);
    if (isMobile(testInfo)) {
        await expect(facts).toBeHidden(); // 보드 MSeason — 시즌 칸은 데스크톱 오른쪽 열에만
        expect(Math.round(n!.x)).toBe(Math.round(r!.x)); // 한 열
        expect(p!.y).toBeGreaterThan(r!.y); // 결과 → 주요 인물 → 다음 시즌
        expect(n!.y).toBeGreaterThan(p!.y);
    } else {
        await expect(facts.locator('[data-server-wait="K8-14"] .os-status--waiting')).toBeVisible();
        const f = await facts.boundingBox();
        expect(Math.round(f!.y)).toBe(Math.round(r!.y)); // 두 열 나란히
        expect(Math.round(f!.width)).toBe(420); // 보드 grid2(420, …) — 오른쪽 열 420
        expect(r!.width).toBeGreaterThan(f!.width);
    }
});
