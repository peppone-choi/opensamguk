// 주변 세계(P-K08) — 외교(/game/court/diplomacy)의 「주변 세계」 탭 내용 골격을 백엔드 없이 합성 자료로 돈다(나머지 게임 읽기는 503).
// 두 프로필(@both): 탭을 누르면 서버 대기(K8-09) 한 칸 · 안내 한 줄, 지어낸 행위자 · 입력 단추 없음, 누를 영역 44 · title 0 · disabled 0 · 넘침 0.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

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
        if (path === '/diplomacy/conflict') {
            return json(route, 200, {
                result: true, conflict: [], myNationID: 1,
                nations: [
                    { nation: 1, name: '조조', color: '#4f7fbf', type: '', level: 1, capital: 0, gennum: 1, cities: ['허현'], power: 0 },
                    { nation: 2, name: '원소', color: '#b04a3c', type: '', level: 1, capital: 0, gennum: 1, cities: ['업현'], power: 0 },
                ],
                diplomacyList: { 1: { 2: 2 } },
            });
        }
        return json(route, 503, {});
    });
    await page.goto('/game/court/diplomacy', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '외교' })).toBeVisible({ timeout: 60_000 });
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

test('주변 세계 탭: 서버 대기(K8-09) · 안내 한 줄 · 지어낸 행위자 없음 · 규칙', { tag: [BOTH] }, async ({ page }, testInfo) => {
    await open(page);
    const panel = page.getByRole('region', { name: '외교' });
    await press(panel.getByRole('tab', { name: '주변 세계' }), testInfo);
    await expect(panel.getByRole('tab', { name: '주변 세계' })).toHaveAttribute('aria-selected', 'true');
    await expect(panel.locator('[data-server-wait="K8-09"] .os-status--waiting')).toBeVisible();
    await expect(panel).toContainText('주변 세계 준비 중');
    await expect(panel).toContainText('주변 세계는 지도 밖 세력입니다');
    await expect(panel).not.toContainText('고구려');
    await expect(panel.locator('[data-input-id]')).toHaveCount(0);
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
});
