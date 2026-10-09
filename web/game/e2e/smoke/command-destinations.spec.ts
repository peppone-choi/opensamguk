import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, titleOnlyInfo } from '../support/parity';

// LIVE-05 regression: synthetic API fixtures only. Browsing/filtering must not submit an action or reservation.
async function serveDestinations(page: Page) {
    const writes: string[] = [];
    const destinations = Array.from({ length: 1608 }, (_, i) => ({
        provinceId: String(70000 + i), name: `구역 ${i + 1}`, available: i < 200,
        distanceMm: (i + 1) * 1_000_000, costMm: (i + 1) * 2_000_000,
        estimatedTurns: i < 185 ? 1 : 2, reachability: i < 185 ? 'THIS_TURN' : 'MULTI_TURN',
        arrivesThisTurn: i < 185, forcedFatigueDelta: 20, forcedMoraleDelta: -10, afterFatigue: 20, afterMorale: 90,
    }));
    destinations[623] = { ...destinations[623], name: '매우 긴 목적지 이름을 줄이지 않고 읽을 수 있는 장안현', distanceMm: 62_345_678, costMm: 80_000_000, available: true, estimatedTurns: 1, reachability: 'THIS_TURN', arrivesThisTurn: true };
    destinations[634] = { ...destinations[634], name: '70634', available: true, distanceMm: 90_000_000, estimatedTurns: 1, reachability: 'THIS_TURN', arrivesThisTurn: true };
    await page.route('**/api/auth/me', r => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route('**/api/server-basic-info/**', r => r.fulfill({ status: 404, json: {} }));
    await page.route('**/api/game/**', async route => {
        const path = new URL(route.request().url()).pathname;
        // The shell sends an unrelated presence heartbeat on mount and interaction. Mock it separately.
        if (path.endsWith('/command/presence')) return route.fulfill({ status: 503, json: {} });
        if (route.request().method() !== 'GET') {
            writes.push(path);
            return route.fulfill({ status: 503, json: {} });
        }
        if (path.endsWith('/front-info')) return route.fulfill({ json: {
            result: true,
            global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
            general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
            nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
        } });
        if (path.endsWith('/reserved-commands')) return route.fulfill({ json: { result: true, generalId: 7, slots: [] } });
        if (path.endsWith('/commands/forced-march-options')) return route.fulfill({ json: { inputId: 'action.forcedMarch', available: true, destinations } });
        return route.fulfill({ status: 503, json: {} });
    });
    await page.goto('/game?server=pep&do=action.forcedMarch&slot=1&target=province%3A70623', { waitUntil: 'domcontentloaded' });
    const flow = page.getByTestId('command-flow');
    await expect(flow.getByLabel('목적지 검색')).toBeVisible({ timeout: 60_000 });
    await expect(flow.getByRole('status').filter({ hasText: '검색·범위 결과' })).toHaveText('검색·범위 결과 20 / 1,608곳');
    return { flow, writes };
}

test('1608곳 전체 검색·가까운 20곳·검색 Escape는 선택과 예약을 보존한다', { tag: [BOTH] }, async ({ page }, info) => {
    const { flow, writes } = await serveDestinations(page);
    const options = flow.getByRole('listbox', { name: '어디로' }).getByRole('option');
    await expect(flow.getByLabel('목적지 범위')).toHaveValue('nearby');
    await expect(flow.getByRole('checkbox', { name: '가능만' })).toBeChecked();
    await expect(options).toHaveCount(20);
    await expect(flow.getByRole('button', { name: /목적지 더 보기/ })).toHaveCount(0);
    await flow.getByLabel('목적지 범위').selectOption('all');
    await expect(options).toHaveCount(50);
    await press(flow.getByRole('button', { name: /목적지 더 보기/ }), info);
    await expect(options).toHaveCount(100);
    await flow.getByLabel('목적지 범위').selectOption('nearby');
    await expect(options).toHaveCount(20);
    const search = flow.getByLabel('목적지 검색');
    await search.fill('장안');
    await expect(options).toHaveCount(1);
    await expect(options.first()).toHaveAttribute('data-target-id', '70623');
    await expect(options.first()).toContainText('예상 1순');
    await expect(options.first()).toContainText('거리 62.35km');
    await expect(options.first()).toContainText('강행 비용 피로 +20 → 20 · 사기 -10 → 90');
    await expect(flow.getByText(/검색은 전체 목적지에서 찾습니다/)).toBeVisible();
    await press(options.first(), info);
    await expect(options.first()).toHaveAttribute('aria-selected', 'true');
    await search.press('Escape');
    await expect(search).toHaveValue('');
    await expect(options).toHaveCount(20);
    await expect(flow).toBeVisible();
    await expect(flow.locator('summary')).toContainText('장안현');
    expect((await flow.locator('summary').boundingBox())!.height).toBeGreaterThanOrEqual(44);
    await press(flow.locator('summary'), info);
    await expect(flow.getByText(/거리 62.345678km/)).toBeVisible();
    await expect(page).toHaveURL(/[?&]slot=1\b/);
    expect(writes).toEqual([]);
});

test('긴 지명·이름 미확인·도착 정보는 좁은 화면에서도 잘리지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
    const { flow, writes } = await serveDestinations(page);
    for (const width of [320, 375, 414]) {
        await page.setViewportSize({ width, height: 844 });
        await flow.getByLabel('목적지 검색').fill('70623');
        const option = flow.getByRole('option', { name: /장안현/ });
        await expect(option).toBeVisible();
        await expect(option).toContainText('이번 턴 도착 · 예상 1순');
        const clipped = await option.evaluate(el => Array.from(el.querySelectorAll<HTMLElement>('.os-opt__name, .os-opt__sub-text'))
            .filter(node => node.scrollWidth > node.clientWidth + 1).map(node => node.className));
        expect(clipped).toEqual([]);
        await expectNoHorizontalOverflow(page);
    }
    await press(flow.getByRole('option', { name: /장안현/ }), info);
    await flow.getByLabel('목적지 검색').fill('70634');
    await expect(flow.getByRole('option', { name: /이름 미확인 · 구역 70634/ })).toBeVisible();
    expect(await titleOnlyInfo(page, '[data-testid="command-flow"]')).toEqual([]);
    await page.screenshot({ path: info.outputPath('destination-mobile.png'), fullPage: true });
    expect(writes).toEqual([]);
});
