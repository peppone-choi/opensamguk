// 계책 덱(P-S01) — /game/stratagem 을 백엔드 없이 합성 자료로 돈다(로그인 · front-info 합성, 나머지 게임 읽기는 503).
// 두 프로필(@both): 덱 안 누를 영역 44 · 네이티브 disabled 0 · title 0 · 가로 넘침 0, 카드 그림은 정본 export 를 실제로 받아 그린다,
// 카드를 누르면 고르고 「걸기」는 「준비 중」(stratagem.play PLANNED) — 누르면 사유가 열린다.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
const DECK = '[data-testid="stratagem-deck"]';

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

    test('카드를 누르면 고르고, 「걸기」는 「준비 중」 — 누르면 사유가 열린다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        const cards = page.getByRole('listbox', { name: '손패 카드' }).getByRole('option');
        await expect(cards).toHaveCount(2);
        await expect(cards.first()).toHaveAttribute('aria-selected', 'true');
        await press(cards.nth(1), testInfo);
        await expect(cards.nth(1)).toHaveAttribute('aria-selected', 'true');
        const act = page.getByRole('button', { name: /간파 — 대응 칸에 걸기/ });
        await expect(act).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
        // 모바일 하단 탭(sticky)이 화면 맨 아래 끝을 덮는다 — 셸 scroll-padding 대기(K3). 고쳐지면 이 줄을 지운다.
        await act.evaluate((el) => el.scrollIntoView({ block: 'center' }));
        await press(act, testInfo);
        await expect(page.getByText('계책 쓰기 — 아직 열리지 않았습니다').first()).toBeVisible();
    });
});
