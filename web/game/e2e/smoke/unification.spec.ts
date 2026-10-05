// 천하 형세(P-H04) 골격 — /game/records/unification 을 백엔드 없이 합성 로그인 · front-info 로 돈다(게임 읽기는 503).
// 두 프로필(@both): 기록 하위 탭에서 천하 형세가 지금 화면 · 13주 격자 · 통일 조건 두 칸 · 서버 대기(K8-13 · K8-15) ·
// 누를 영역 44 · title 0 · disabled 0 · 넘침 0 · 배치(데스크톱 오른쪽 열 560 · 13주 4열, 모바일 조건이 13주보다 위 · 3열).
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
    await page.goto('/game/records/unification', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '천하 형세' })).toBeVisible({ timeout: 60_000 });
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

test('천하 형세 골격: 13주 · 통일 조건 · 서버 대기 · 규칙 · 배치', { tag: [BOTH] }, async ({ page }, testInfo) => {
    await open(page);
    await expect(page.getByRole('navigation', { name: '하위 화면' }).getByRole('link', { name: '천하 형세' })).toHaveAttribute('aria-current', 'page');
    const tiles = page.getByRole('list', { name: '13주' }).getByRole('listitem').filter({ has: page.locator('[data-server-wait="K8-13"]') });
    await expect(tiles).toHaveCount(13);
    // 화면 이름(D25) — 데이터 키 「량주」 · 「사예」는 data-ju 에만 있고 글자로는 안 보인다
    const shown = await tiles.evaluateAll((els) => els.map((el) => (el.firstElementChild as HTMLElement).innerText.trim()));
    expect(shown).toEqual(expect.arrayContaining(['사례', '양주', '서량']));
    expect(shown.filter((t) => t === '량주' || t === '사예')).toEqual([]);
    await expect(page.getByRole('region', { name: '통일 조건' })).toContainText('「쥔다」의 뜻은 아직 정해지지 않았습니다');
    await expect(page.getByRole('region', { name: '통일 조건' }).locator('[data-server-wait="K8-15"]')).toHaveCount(1);
    await expect(page.locator(`${MAIN} [data-server-wait="K8-13"] .os-status--waiting`)).toHaveCount(2); // 세력 · 내 몫
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
    const [zhou, cond] = await Promise.all([page.getByRole('region', { name: '13주' }).boundingBox(), page.getByRole('region', { name: '통일 조건' }).boundingBox()]);
    const tileBoxes = await tiles.evaluateAll((els) => els.slice(0, 5).map((el) => Math.round(el.getBoundingClientRect().y)));
    if (isMobile(testInfo)) {
        expect(cond!.y).toBeLessThan(zhou!.y); // 조건이 13주보다 위
        expect(new Set(tileBoxes.slice(0, 3)).size).toBe(1); // 한 줄에 셋
        expect(tileBoxes[3]).toBeGreaterThan(tileBoxes[0]);
    } else {
        expect(Math.round(cond!.width)).toBe(560); // 보드 grid2(560, …) — 오른쪽 열 560
        expect(Math.round(cond!.y)).toBe(Math.round(zhou!.y)); // 두 열 나란히
        expect(new Set(tileBoxes.slice(0, 4)).size).toBe(1); // 한 줄에 넷
        expect(tileBoxes[4]).toBeGreaterThan(tileBoxes[0]);
    }
});

// 지도는 스위치 없이 늘 새 지도다(옛 지도는 지웠다, M2-9) — 단추는 늘 작전실을 주 보기로 여는 고리다.
test('지도에서 보기 — 주 경계: 작전실을 주 보기로 여는 고리', { tag: [BOTH] }, async ({ page }) => {
    await open(page);
    const link = page.getByRole('region', { name: '13주' }).getByRole('link', { name: /지도에서 보기 — 주 경계/ });
    await expect(link).toHaveAttribute('href', /\?view=ju$/);
    await expect(link).not.toHaveAttribute('aria-disabled', 'true');
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
});
