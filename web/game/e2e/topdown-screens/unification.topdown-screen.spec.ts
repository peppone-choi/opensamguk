// 천하 형세(P-H04) 「지도에서 보기 — 주 경계」 — 제품 화면 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS=1) 빌드에서만 돈다(*.topdown-screen.spec.ts).
// 새 지도 빌드에서는 단추가 작전실을 주 보기로 여는 고리(?view=ju, K2 #1213)다 — 비활성 사유 시트가 아니다.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, smallTouchTargets } from '../support/parity';

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
    await page.goto('/game/records/unification', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '천하 형세' })).toBeVisible({ timeout: 60_000 });
}

test('천하 형세 — 새 지도 빌드에서 「지도에서 보기 — 주 경계」는 작전실 ?view=ju 고리', { tag: [BOTH] }, async ({ page }) => {
    await open(page);
    const zhou = page.getByRole('region', { name: '13주' });
    const link = zhou.getByRole('link', { name: /지도에서 보기 — 주 경계/ });
    await expect(link).toHaveAttribute('href', /\?view=ju$/);
    await expect(zhou.getByRole('button', { name: /지도에서 보기/ })).toHaveCount(0);
    expect(await smallTouchTargets(page, 'main[aria-label="게임 콘텐츠"]')).toEqual([]);
});
