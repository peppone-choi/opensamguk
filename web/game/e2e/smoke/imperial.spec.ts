// 황실(P-K09) — /game/court/imperial 을 백엔드 없이 합성 자료로 돈다(로그인 · front-info · 황제 소재지 · 지도 미리보기 합성, 나머지 게임 읽기는 503).
// 두 프로필(@both): 「그려짐」(조정 하위 탭에서 황실이 지금 화면 · 상태별 모양 · 누를 영역 44 · title 0 · 가로 넘침 0 · 배치)과
// 「조작됨」(읽기 실패 → 다시 시도 → 다시 읽어 바뀐 모양)을 따로 본다.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
type Presence = { status: number; body: unknown };

const READY = {
    status: 'READY',
    badges: [
        { lineCode: 'han', lineName: '한', emperorGeneralId: 101, emperorNodeKind: 'LAND_PROVINCE', emperorNodeId: '70930', emperorCityId: 12, courtCityId: 11 },
        { lineCode: 'zhong', lineName: '중', emperorGeneralId: 102, emperorNodeKind: 'WATER_ZONE', emperorNodeId: 'w1', emperorCityId: null, courtCityId: null },
    ],
};
const NOT_SEEDED = { status: 'NOT_SEEDED', badges: [] };
const UNAVAILABLE = { status: 'STATE_UNAVAILABLE', badges: [] };

async function serve(page: Page, presences: Presence[]) {
    const calls = { presence: 0, preview: 0 };
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
        if (path === '/imperial/presence') {
            const p = presences[Math.min(calls.presence, presences.length - 1)];
            calls.presence += 1;
            return json(route, p.status, p.body);
        }
        if (path === '/map/preview') {
            calls.preview += 1;
            return json(route, 200, { cities: [{ id: 11, name: '허', displayName: '영천군 허현', level: 1, nationId: 1, x: 0, y: 0 }, { id: 12, name: '낙양', displayName: '하남윤 낙양현', level: 1, nationId: 0, x: 0, y: 0 }], nations: [] });
        }
        return json(route, 503, {});
    });
    return calls;
}

async function open(page: Page, presences: Presence[]) {
    const calls = await serve(page, presences);
    await page.goto('/game/court/imperial', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '황실' })).toBeVisible({ timeout: 60_000 });
    return calls;
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

async function rules(page: Page) {
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

test.describe('황실', () => {
    test('그려짐: 황실 없음 — 조정 하위 탭의 지금 화면, 빈 상태와 칭제 칸만, 지도 미리보기를 받지 않는다', { tag: [BOTH] }, async ({ page }) => {
        const calls = await open(page, [{ status: 200, body: NOT_SEEDED }]);
        await expect(page.getByRole('navigation', { name: '하위 화면' }).getByRole('link', { name: '황실' })).toHaveAttribute('aria-current', 'page');
        await expect(page.getByText('이 천하에는 황실이 없습니다')).toBeVisible();
        await expect(page.getByRole('heading', { name: '칭제' })).toBeVisible();
        await expect(page.getByRole('heading', { name: '세력과 황실' })).toHaveCount(0);
        await rules(page);
        expect(calls.preview).toBe(0);
    });

    test('그려짐: 황제가 있다 — 황통 카드(이름은 지도 미리보기) · 서버 대기 셋 · 칭제, 데스크톱은 대기 셋이 한 줄 · 모바일은 쌓인다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        const calls = await open(page, [{ status: 200, body: READY }]);
        const han = page.getByRole('region', { name: '황통 — 한' });
        await expect(han).toContainText('어느 인물');
        await expect(han).toContainText('하남윤 낙양현 · 성 안');
        await expect(han).toContainText('영천군 허현');
        const zhong = page.getByRole('region', { name: '황통 — 중' });
        await expect(zhong).toContainText('물 위');
        await expect(zhong).toContainText('정하지 않음');
        const waits = ['세력과 황실', '조서', '인장 · 조정 방침'].map((name) => page.getByRole('heading', { name }));
        for (const w of waits) await expect(w).toBeVisible();
        await expect(page.locator(`${MAIN} .os-status--waiting`)).toHaveCount(4);
        await rules(page);
        expect(calls.preview).toBe(1);
        const ys = await Promise.all(waits.map(async (w) => Math.round((await w.boundingBox())!.y)));
        if (isMobile(testInfo)) {
            expect(new Set(ys).size).toBe(3); // 쌓인다
            const card = (await han.boundingBox())!;
            expect(Math.round(card.width)).toBe(390 - 24); // 본문 여백 12 · 12
        } else {
            expect(new Set(ys).size).toBe(1); // 한 줄
            const [a, b] = await Promise.all([han.boundingBox(), zhong.boundingBox()]);
            expect(Math.round(a!.y)).toBe(Math.round(b!.y)); // 황통 카드 둘이 나란히
        }
    });

    test('조작됨: 읽기 실패(409) → 다시 시도 → 다시 읽어 황실 없음으로 바뀐다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        const calls = await open(page, [{ status: 409, body: UNAVAILABLE }, { status: 200, body: NOT_SEEDED }]);
        await expect(page.getByText('황실 정보를 지금 읽을 수 없습니다')).toBeVisible();
        await expect(page.getByText('이 천하에는 황실이 없습니다')).toHaveCount(0);
        await rules(page);
        await press(page.getByRole('button', { name: '다시 시도' }), testInfo);
        await expect(page.getByText('이 천하에는 황실이 없습니다')).toBeVisible();
        await expect(page.getByText('황실 정보를 지금 읽을 수 없습니다')).toHaveCount(0);
        expect(calls.presence).toBe(2);
    });
});
