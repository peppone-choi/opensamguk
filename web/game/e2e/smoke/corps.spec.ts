// 군단 · 세력 작전(P-C01) — /game/corps 를 백엔드 없이 합성 자료로 돈다(로그인 · front-info 합성, 나머지 게임 읽기는 503).
// 두 프로필(@both): 군단 칸 안 누를 영역 44 · 네이티브 disabled 0 · title 0 · 가로 넘침 0 · 영어 원문 0,
// 내 군단 카드(방침 · 전투 잠김 서버 대기), 「출병」 → 작전실 명령 흐름(?do=action.deploy), 편성 해제 확인 → 나(행위자)로 접수.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
const PANEL = '[data-testid="corps-panel"]';
interface Server { released: { generalId: string | null; body: unknown }[] }

async function serve(page: Page, server: Server) {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.slice(API.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 5, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/corps') {
            return json(route, 200, { status: 'READY', corps: [
                { corpsId: 'O-1', ownerGeneralId: 7, commanderGeneralId: 7, commanderName: '하후돈', nationId: 1, nationColor: '#4f7fbf', provinceId: 'P-1', commanderyNo: 12, visibility: 'FULL', own: true, troops: 1200, marchPath: ['P-1', 'P-2'], destinationProvinceId: 'P-2' },
                { corpsId: 'O-9', ownerGeneralId: 9, commanderGeneralId: 9, commanderName: '여포', nationId: 5, nationColor: '#aa3333', provinceId: 'P-7', commanderyNo: 40, visibility: 'INTEL', own: false, troopsBand: { code: 'B3', label: '3천 안팎' }, ageTurns: 2 },
            ] });
        }
        if (path === '/visibility') return json(route, 200, { status: 'READY', commanderies: [{ no: 12, id: 'c12', name: '영천군', tier: 'FULL' }] });
        if (path === '/deploy/options') return json(route, 200, { available: true, maxReservedTurns: 12, bugoks: [], destinations: [{ provinceId: 'P-2', name: '진류' }], order: { orderId: 'O-1', destinationProvinceId: 'P-2', stop: 'BUDGET_EXHAUSTED' } });
        if (path === '/policies') {
            return json(route, 200, { status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: { code: 'DEFEND', label: '수비' }, counties: [],
                corps: [{ orderId: 'O-1', commanderName: '하후돈', active: null, pending: { policy: 'EVADE', label: '회피' }, settable: true, blocked: null }] });
        }
        if (path === '/commands/legacy-court-options') {
            return json(route, 200, { inputId: 'court.releaseCorps', available: true, choices: [{ label: '하후돈 군단', arguments: { targetGeneralId: 7 }, available: true }] });
        }
        if (path === '/commands/court/releaseCorps' && route.request().method() === 'POST') {
            server.released.push({ generalId: url.searchParams.get('generalId'), body: route.request().postDataJSON() });
            return json(route, 202, { status: 'AVAILABLE', requestId: 'c-1' });
        }
        return json(route, 503, {});
    });
}

/** 화면 글자 속 영어 낱말(3글자 이상) — 서버 원문 · 코드 · 입력 id 가 글자로 새면 걸린다(battle.spec 과 같은 기준). */
async function englishWords(page: Page, root: string): Promise<string[]> {
    const text = await page.locator(root).first().innerText();
    return text.match(/[A-Za-z]{3,}/g) ?? [];
}

async function open(page: Page, server: Server) {
    await serve(page, server);
    await page.goto('/game/corps', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: '군단 · 세력 작전', exact: true })).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole('region', { name: '내 군단' })).toBeVisible({ timeout: 60_000 });
}

test.describe('군단 · 세력 작전', () => {
    test('규칙: 44 · disabled 0 · title 0 · 넘침 0 · 영어 원문 0 — 목록과 군단 카드', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page, { released: [] });
        await expect(page.locator(PANEL)).toContainText('지금 출병 명령 · 목적지 진류 · 행군 중');
        await expect(page.getByRole('region', { name: '보이는 다른 군단' })).toContainText('2순 전 첩보');
        await press(page.getByRole('region', { name: '내 군단' }).getByRole('button'), testInfo);
        const card = page.getByRole('article', { name: '군단 — 하후돈' });
        await expect(card).toContainText('방침 없음 · 다음 순부터 회피');
        await expect(card).not.toContainText('수비');
        await expect(card.locator('[data-contract="K6-11"]')).toContainText('서버 대기');
        expect(await smallTouchTargets(page, PANEL)).toEqual([]);
        expect(await titleOnlyInfo(page, PANEL)).toEqual([]);
        expect(await page.locator(`${PANEL} :disabled`).count()).toBe(0);
        expect(await englishWords(page, PANEL)).toEqual([]);
        await expectNoHorizontalOverflow(page);
    });

    test('「출병」은 작전실 명령 흐름(?do=action.deploy)을 연다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page, { released: [] });
        await press(page.getByRole('button', { name: '출병' }), testInfo);
        await expect(page).toHaveURL(/\/game\??.*[?&]do=action\.deploy\b/);
    });

    test('편성 해제는 한 번 묻고, 확인하면 나(행위자)로 서버가 준 인자를 보낸다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        const server: Server = { released: [] };
        await open(page, server);
        await press(page.getByRole('region', { name: '내 군단' }).getByRole('button'), testInfo);
        const release = page.getByRole('article', { name: '군단 — 하후돈' }).getByRole('button', { name: '편성 해제' });
        await expect(release).toHaveAttribute('data-input-status', 'AVAILABLE');
        // 모바일 하단 탭(sticky)이 화면 맨 아래 끝을 덮는다 — 셸 scroll-padding 대기(K3 #1133). 고쳐지면 이 줄을 지운다.
        await release.evaluate((el) => el.scrollIntoView({ block: 'center' }));
        await press(release, testInfo);
        await expect(page.getByText('군단 편성을 풉니다')).toBeVisible();
        await press(page.getByRole('dialog').getByRole('button', { name: '편성 해제' }), testInfo);
        await expect.poll(() => server.released.length).toBe(1);
        expect(server.released[0]).toEqual({ generalId: '7', body: { targetGeneralId: 7 } });
    });
});
