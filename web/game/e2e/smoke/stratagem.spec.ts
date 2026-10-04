// 계책 덱(P-S01) — /game/stratagem 을 백엔드 없이 합성 자료로 돈다(로그인 · front-info 합성, 나머지 게임 읽기는 503).
// 두 프로필(@both): 덱 안 누를 영역 44 · 네이티브 disabled 0 · title 0 · 가로 넘침 0, 카드 그림은 정본 export 를 실제로 받아 그린다,
// 카드를 누르면 고르고 「걸기」는 계책 쓰기 시트(P-S02, ?card=)를 연다 — 시트의 결정 단추는 「준비 중」(stratagem.play PLANNED)이고 누르면 사유가 열린다,
// 시트는 누를 영역 44 · title 0 · 넘침 0 · 가운데가 시트 · 닫으면 ?card= 가 빠진다.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
const DECK = '[data-testid="stratagem-deck"]';
const SHEET = '[data-testid="stratagem-play"]';

async function serve(page: Page) {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const path = new URL(route.request().url()).pathname.slice(API.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 5, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/commands/stratagem-hand') {
            return json(route, 200, {
                status: 'READY', handLimit: 5, canUse: false,
                cards: [{ instanceId: 1, type: 'FORTIFY', label: '견벽' }, { instanceId: 2, type: 'INSIGHT', label: '간파' }],
            });
        }
        return json(route, 503, {});
    });
}

async function open(page: Page) {
    await serve(page);
    await page.goto('/game/stratagem', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: '계책 덱', exact: true })).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole('listbox', { name: '손패 카드' })).toBeVisible({ timeout: 60_000 });
}

test.describe('계책 덱', () => {
    test('손패와 세 칸 가장자리 여백은 셸이 한 번 준다', { tag: [BOTH] }, async ({ page }) => {
        await open(page);
        const inset = await page.locator(DECK).evaluate((deck) => {
            const body = deck.closest('[data-shell-body]')!;
            const outer = body.getBoundingClientRect();
            const hand = deck.querySelector('[aria-label="손패"]')!.getBoundingClientRect();
            const zone = deck.querySelector('[aria-label="즉시 칸"]')!.getBoundingClientRect();
            return { handLeft: Math.round(hand.left - outer.left), zoneLeft: Math.round(zone.left - outer.left), top: Math.round(Math.min(hand.top, zone.top) - outer.top) };
        });
        expect(inset).toEqual({ handLeft: 12, zoneLeft: 12, top: page.viewportSize()!.width < 768 ? 10 : 12 });
    });

    test('규칙: 누를 영역 44 · disabled 0 · title 0 · 가로 넘침 0, 카드 그림은 정본 export 를 받아 그린다', { tag: [BOTH] }, async ({ page }) => {
        await open(page);
        expect(await smallTouchTargets(page, DECK)).toEqual([]);
        expect(await titleOnlyInfo(page, DECK)).toEqual([]);
        expect(await page.locator(`${DECK} :disabled`).count()).toBe(0);
        await expectNoHorizontalOverflow(page);
        // 그림이 실제로 내려와 그려졌는지(경로 · 파일 · 디코딩) — 깨진 그림은 naturalWidth 0.
        const art = page.getByRole('img', { name: '견벽 카드 그림' });
        await art.scrollIntoViewIfNeeded();
        await expect.poll(() => art.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true);
    });

    test('카드를 누르면 고르고, 「걸기」는 계책 쓰기 시트를 연다 — 결정 단추는 「준비 중」, 누르면 사유가 열린다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        const cards = page.getByRole('listbox', { name: '손패 카드' }).getByRole('option');
        await expect(cards).toHaveCount(2);
        await expect(cards.first()).toHaveAttribute('aria-selected', 'true');
        await press(cards.nth(1), testInfo);
        await expect(cards.nth(1)).toHaveAttribute('aria-selected', 'true');
        const opener = page.getByRole('button', { name: '간파 — 대응 칸에 걸기' });
        // 모바일 하단 탭(sticky)이 화면 맨 아래 끝을 덮는다 — 셸 scroll-padding 대기(K3). 고쳐지면 이 줄을 지운다.
        await opener.evaluate((el) => el.scrollIntoView({ block: 'center' }));
        await press(opener, testInfo);
        await expect(page).toHaveURL(/[?&]card=2(&|$)/);
        const sheet = page.getByRole('complementary', { name: '계책 걸기' });
        await expect(sheet).toBeVisible();
        // 대상 후보 · 비용은 서버가 아직 주지 않는다(계약판 K6-10) — 지어내지 않고 서버 대기.
        await expect(sheet.getByRole('region', { name: '어느 방어 칸에 걸까' })).toContainText('대상 후보 준비 중');
        const act = sheet.getByRole('button', { name: /간파 걸기/ });
        await expect(act).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
        await press(act, testInfo);
        await expect(page.getByText('계책 쓰기 — 아직 열리지 않았습니다').first()).toBeVisible();
    });

    test('계책 쓰기 시트 — 누를 영역 44 · title 0 · 넘침 0 · 가운데가 시트 · 닫으면 덱으로(?card= 가 빠진다)', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await serve(page);
        await page.goto('/game/stratagem?card=1', { waitUntil: 'domcontentloaded' });
        const sheet = page.getByRole('complementary', { name: '계책 걸기' });
        await expect(sheet).toBeVisible({ timeout: 60_000 });
        await expect(sheet.getByRole('heading', { level: 3, name: '견벽' })).toBeVisible();
        expect(await smallTouchTargets(page, SHEET)).toEqual([]);
        expect(await titleOnlyInfo(page, SHEET)).toEqual([]);
        await expectNoHorizontalOverflow(page);
        // 시트 가운데를 누르면 시트가 받는다(elementFromPoint) — 셸 층 · 탭 막대가 위에 있지 않다.
        const box = (await sheet.boundingBox())!;
        const onTop = await page.evaluate(([x, y, sel]) => document.elementFromPoint(x as number, y as number)?.closest(sel as string) != null,
            [box.x + box.width / 2, box.y + box.height / 2, SHEET] as const);
        expect(onTop, '시트 가운데가 시트가 아니다').toBe(true);
        await press(sheet.getByRole('button', { name: '닫기(Esc)' }), testInfo);
        await expect(sheet).toHaveCount(0);
        await expect(page).toHaveURL(/\/game\/stratagem$/);
    });
});
