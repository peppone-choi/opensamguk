// 명령 흐름(P-W02) — 작전실(/game)에 붙은 흐름을 백엔드 없이 합성 자료로 돈다(셸 스모크와 같은 방식: 로그인 · front-info 합성,
// 나머지 게임 읽기는 503). 12순 열에서 순을 눌러 흐름 열기 · 목적지를 고르고 예약 → 닫지 않고 다음 빈 순 · 서버 거절 → 사유 시트 ·
// 규칙(44 · disabled 0 · title 0 · 넘침 0) · Esc 로 닫기. 모바일은 머리줄 아래 전체 시트.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
const GENERAL_ID = 7;

interface Server {
    filled: number[];
    rejectNext: { code: string; reason: string } | null;
    commands: { inputId: string; turnIdx: number; args: unknown }[];
}

async function serve(page: Page, server: Server) {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.slice(API.length);
        if (url.pathname.endsWith('/front-info')) {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: GENERAL_ID, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/reserved-commands') {
            return json(route, 200, { result: true, generalId: GENERAL_ID, slots: server.filled.map((turnIdx) => ({ turnIdx, action: 'action.farm', brief: '', arg: {} })) });
        }
        if (path === '/commands/move-options') {
            return json(route, 200, {
                inputId: 'action.move', available: true,
                destinations: [
                    { provinceId: 'P-1', name: '영천', available: true },
                    { provinceId: 'P-2', name: '양적', available: false, code: 'NO_ROUTE', reason: '갈 길이 없습니다' },
                ],
            });
        }
        if (path.startsWith('/command/action.') && route.request().method() === 'POST') {
            const inputId = path.slice('/command/'.length);
            const turnIdx = Number(url.searchParams.get('turnIdx'));
            server.commands.push({ inputId, turnIdx, args: route.request().postDataJSON() });
            if (server.rejectNext) {
                const { code, reason } = server.rejectNext;
                server.rejectNext = null;
                return json(route, 200, { status: 'BLOCKED', code, reason });
            }
            server.filled = [...server.filled, turnIdx];
            return json(route, 202, { status: 'AVAILABLE', requestId: 'r-1', turnIdx });
        }
        if (path === '/command/result/r-1') {
            return json(route, 200, { status: 'RESOLVED', requestId: 'r-1', ok: true, type: 'reservationAccepted', result: { commandKind: 'RESERVED_TURN' } });
        }
        return json(route, 503, {});
    });
}

/**
 * 흐름 안의 누를 것 중 44 × 44 보다 작은 것(parity.smallTouchTargets 와 같은 선택자 · 기준). 다만 라벨(label.os-check — K3 후보 목록
 * 「가능만」)이 감싼 체크박스는 라벨이 누르는 영역이라 라벨로 잰다(K3 공용 부품 미리보기 시험과 같은 기준).
 */
async function smallTargetsInFlow(page: Page): Promise<string[]> {
    return page.getByTestId('command-flow').evaluate((root) => {
        const sel = 'button, a[href], [role="button"], [role="tab"], [role="option"], [role="menuitem"], input:not([type="hidden"]), select, textarea, summary';
        const out: string[] = [];
        for (const el of Array.from(root.querySelectorAll<HTMLElement>(sel))) {
            const wrap = el.matches('input[type="checkbox"]') ? el.closest<HTMLElement>('label.os-check') : null;
            const target = wrap ?? el;
            const r = target.getBoundingClientRect();
            const cs = getComputedStyle(target);
            if (r.width === 0 || r.height === 0 || cs.visibility === 'hidden' || cs.display === 'none') continue;
            if (r.width < 43.5 || r.height < 43.5) {
                const label = (target.getAttribute('aria-label') ?? target.textContent ?? '').trim().slice(0, 24);
                out.push(`${target.tagName.toLowerCase()} "${label}" ${Math.round(r.width)}×${Math.round(r.height)}`);
            }
        }
        return out;
    });
}

const fresh = (): Server => ({ filled: [0, 1], rejectNext: null, commands: [] });
const flow = (page: Page) => page.getByTestId('command-flow');

async function openFlow(page: Page, server: Server, query: string) {
    await serve(page, server);
    await page.goto(`/game?${query}`, { waitUntil: 'domcontentloaded' });
    await expect(flow(page)).toBeVisible({ timeout: 60_000 });
    // 12순을 읽고 순이 정해질 때까지(다음 빈 순)
    await expect(flow(page).locator('[data-turn-idx][aria-pressed="true"]')).toHaveCount(1);
}

/** 화면 글자 속 영어 낱말(3글자 이상) — 서버 원문 · 코드 · 입력 id 가 글자로 새면 걸린다(battle.spec 과 같은 기준). */
async function englishWords(page: Page, root: string): Promise<string[]> {
    const text = await page.locator(root).first().innerText();
    return text.match(/[A-Za-z]{3,}/g) ?? [];
}

/** 도움말 서랍이 화면 맨 위에 보이는가 — 가운데 · 머리 쪽 두 점을 눌렀을 때 서랍 안 요소가 맞는다(흐름 시트에 덮이지 않음). */
async function drawerOnTop(page: Page): Promise<boolean> {
    return page.getByRole('complementary', { name: '도움말' }).evaluate((drawer) => {
        const r = drawer.getBoundingClientRect();
        const xs = [r.left + r.width / 2];
        const ys = [r.top + Math.min(r.height / 2, 200), r.top + 24];
        return xs.every((x) => ys.every((y) => { const el = document.elementFromPoint(x, y); return el != null && drawer.contains(el); }));
    });
}

/** 거절 사유 시트를 띄운다(BATTLE_LOCKED) — 시트 안 「도움말 — …」 링크가 도움말 서랍을 연다. */
async function rejectedSheet(page: Page, testInfo: Parameters<typeof press>[1]) {
    const server = fresh();
    server.rejectNext = { code: 'BATTLE_LOCKED', reason: '전투 중이라 새 명령을 받지 않습니다' };
    await openFlow(page, server, 'do=action.move');
    await press(flow(page).getByRole('option', { name: /영천/ }), testInfo);
    await press(flow(page).locator('[data-input-id="action.move"][data-input-status]'), testInfo);
    const sheet = page.getByRole('dialog', { name: /서버가 받지 않았습니다/ });
    await expect(sheet).toBeVisible();
    return sheet;
}

test.describe('명령 흐름', () => {
    test('태블릿에서 흐름은 겹쳐 열리고 지도에는 빈 격자 칸이 남지 않는다', async ({ page }) => {
        await serve(page, fresh());
        for (const width of [768, 1000, 1199, 1200]) {
            await page.setViewportSize({ width, height: 900 });
            await page.goto('/game?do=action.move', { waitUntil: 'domcontentloaded' });
            await expect(flow(page).getByRole('option', { name: /영천/ })).toBeVisible({ timeout: 60_000 });
            // 작전실 틀(P-W01): 지도 영역 + 흐름 칸(데스크톱 576) — 태블릿은 흐름이 겹치고 흐름 칸은 0이라 지도가 틀을 다 쓴다.
            const layout = page.getByTestId('war-room-layout');
            const dimensions = await layout.evaluate((el) => {
                const host = el.querySelector('[data-testid="command-flow-host"]');
                return {
                    mapWidth: el.firstElementChild!.getBoundingClientRect().width,
                    width: el.clientWidth,
                    flowColumn: host?.parentElement && host.parentElement !== el ? host.parentElement.getBoundingClientRect().width : null,
                };
            });
            if (width < 1200) {
                expect(dimensions.mapWidth).toBeCloseTo(dimensions.width, 0);
                await expect(page.getByTestId('command-flow-host')).toHaveCSS('position', 'fixed');
                expect((await page.getByTestId('command-flow-host').boundingBox())!.width).toBe(480);
            } else {
                expect(dimensions.flowColumn).toBe(576);
                expect(dimensions.mapWidth).toBeCloseTo(dimensions.width - 576, 0);
                // 데스크톱 흐름은 칸 안에 선다 — 지도 위로 뜨면(fixed) 회귀(K6 제안).
                await expect(page.getByTestId('command-flow-host')).not.toHaveCSS('position', 'fixed');
            }
            await expectNoHorizontalOverflow(page);
        }
    });

    test('옵션과 예약의 HTTP 실패는 한국어로 보이고 원문은 나오지 않는다', { tag: [BOTH] }, async ({ page }) => {
        await serve(page, fresh());
        await page.route('**/commands/move-options?**', (route) => route.fulfill({ status: 500, body: 'Internal Server Error' }));
        await page.goto('/game?do=action.move', { waitUntil: 'domcontentloaded' });
        await expect(flow(page).getByText('서버에서 문제가 생겼습니다. 잠시 뒤 다시 해 보세요.')).toBeVisible({ timeout: 60_000 });
        await expect(flow(page).getByRole('button', { name: '오류 번호 500 복사' })).toBeVisible();
        expect(await englishWords(page, '[data-testid="command-flow"]')).toEqual([]);
        await page.unroute('**/commands/move-options?**');
        await flow(page).getByRole('button', { name: /다시 시도/ }).click();
        await expect(flow(page).getByRole('option', { name: /영천/ })).toBeVisible();
        await page.route('**/command/action.move?**', (route) => route.fulfill({ status: 503, body: 'Service Unavailable' }));
        await flow(page).getByRole('option', { name: /영천/ }).click();
        await flow(page).locator('[data-input-id="action.move"][data-input-status]').click();
        await expect(flow(page).getByRole('alert')).toHaveText('서버가 잠시 응답하지 않습니다. 잠시 뒤 다시 해 보세요.');
        expect(await englishWords(page, '[data-testid="command-flow"]')).toEqual([]);
    });

    test('작전실 12순 열에서 빈 순을 누르면 그 순으로 흐름이 열린다', async ({ page }) => {
        await serve(page, fresh());
        await page.goto('/game', { waitUntil: 'domcontentloaded' });
        const column = page.getByTestId('turn-slots-column');
        await expect(column).toBeVisible({ timeout: 60_000 });
        await expect(column.getByRole('button', { name: '01순 — 농지개간' })).toBeVisible();
        await column.getByRole('button', { name: '05순 — 빈 순' }).click();
        await expect(flow(page)).toBeVisible();
        await expect(page).toHaveURL(/[?&]slot=5\b/);
        await expect(flow(page).getByRole('button', { name: '05순 — 빈 순' })).toHaveAttribute('aria-pressed', 'true');
    });

    test('규칙: 누를 영역 44 · disabled 0 · title 0 · 가로 넘침 0 · 영어 원문 0', { tag: [BOTH] }, async ({ page }) => {
        await openFlow(page, fresh(), 'do=action.move');
        await expect(flow(page).getByRole('option', { name: /영천/ })).toBeVisible();
        expect(await smallTargetsInFlow(page)).toEqual([]);
        expect(await flow(page).locator('[disabled]').count()).toBe(0);
        expect(await titleOnlyInfo(page, '[data-testid="command-flow"]')).toEqual([]);
        expect(await englishWords(page, '[data-testid="command-flow"]')).toEqual([]);
        await expectNoHorizontalOverflow(page);
    });

    test('분류 탭 줄은 제 높이(44)를 갖고, 탭 가운데를 누르면 그 탭이 받는다 — 검색 칸 밑에 깔리지 않는다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        // K10 품질 측정 #2(10-05): `.tabs` 가 overflow-x 인 세로 flex 항목이라 자동 최소 높이가 0 이 되어 15–20px 로 눌렸다.
        // 탭 단추 자체는 44 라 위 「누를 영역 44」 규칙은 통과했지만, 탭 가운데를 누르면 검색 칸이 받았다.
        await openFlow(page, fresh(), 'do=');
        const tablist = flow(page).getByRole('tablist', { name: '명령 분류' });
        await expect(tablist).toBeVisible();
        expect.soft((await tablist.boundingBox())!.height, '탭 줄 높이').toBeGreaterThanOrEqual(43.5);
        const tabs = tablist.getByRole('tab');
        const n = await tabs.count();
        expect(n).toBeGreaterThan(1);
        for (let i = 0; i < n; i += 1) {
            const tab = tabs.nth(i);
            await press(tab, testInfo); // 누르기 전에 가운데 elementFromPoint = 그 탭을 단언한다(parity.press)
            await expect(tab).toHaveAttribute('aria-selected', 'true');
        }
    });

    test('목적지를 고르고 예약하면 그 순에 보내고, 닫지 않고 다음 빈 순으로 간다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        const server = fresh();
        await openFlow(page, server, 'do=action.move');
        await expect(flow(page).getByRole('button', { name: '03순 — 빈 순' })).toHaveAttribute('aria-pressed', 'true');
        await press(flow(page).getByRole('option', { name: /영천/ }), testInfo);
        const submit = flow(page).locator('[data-input-id="action.move"][data-input-status]');
        await expect(submit).toHaveText('03순에 예약');
        await press(submit, testInfo);
        await expect(flow(page).getByText('「이동」 — 03순에 예약했습니다.')).toBeVisible();
        expect(server.commands).toEqual([{ inputId: 'action.move', turnIdx: 2, args: { destinationProvinceId: 'P-1' } }]);
        await expect(flow(page).locator('[data-turn-idx="3"]')).toHaveAttribute('aria-pressed', 'true');
        await expect(page).toHaveURL(/[?&]slot=4\b/);
        if (isMobile(testInfo)) {
            // 모바일: 머리줄 아래 전체 시트 — 화면 폭을 채운다.
            const box = (await page.getByTestId('command-flow-host').boundingBox())!;
            expect(Math.round(box.width)).toBe(page.viewportSize()!.width);
        }
    });

    test('서버가 예약을 거절하면 그 code · reason 으로 막히고 사유 시트가 열린 채로 뜬다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        const server = fresh();
        server.rejectNext = { code: 'BATTLE_LOCKED', reason: '전투 중이라 새 명령을 받지 않습니다' };
        await openFlow(page, server, 'do=action.move');
        await press(flow(page).getByRole('option', { name: /영천/ }), testInfo);
        await press(flow(page).locator('[data-input-id="action.move"][data-input-status]'), testInfo);
        const sheet = page.getByRole('dialog', { name: /서버가 받지 않았습니다/ });
        await expect(sheet).toBeVisible();
        await expect(sheet).toContainText('전투 중이라 새 명령을 받지 않습니다');
        await expect(flow(page).locator('[data-reason-code="BATTLE_LOCKED"]')).toHaveCount(1);
        await expect(flow(page).locator('[data-input-id="action.move"][data-input-status]')).toHaveAttribute('data-input-status', 'BLOCKED');
        await expect(flow(page)).toBeVisible();
        // Keyboard focus outside the sheet must not discard the draft.
        await flow(page).getByRole('button', { name: '03순 — 빈 순' }).focus();
        await page.keyboard.press('Escape');
        await expect(sheet).toHaveCount(0);
        await expect(flow(page)).toBeVisible();
        await expect(flow(page).getByRole('option', { name: /영천/ })).toHaveAttribute('aria-selected', 'true');
    });

    test('못 가는 곳은 행을 눌러 사유가 열리고 고르지 않는다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await openFlow(page, fresh(), 'do=action.move');
        const blocked = flow(page).getByRole('option', { name: /양적/ });
        await press(blocked, testInfo);
        await expect(page.getByRole('dialog', { name: /양적 — 고를 수 없습니다/ })).toContainText('갈 길이 없습니다');
        await expect(blocked).toHaveAttribute('aria-selected', 'false');
    });

    test('Esc 로 닫으면 주소에서 흐름 키가 빠지고 12순 열로 돌아온다', async ({ page }) => {
        await openFlow(page, fresh(), 'do=action.move&slot=4');
        await page.keyboard.press('Escape');
        await expect(flow(page)).toHaveCount(0);
        await expect(page).not.toHaveURL(/[?&](do|slot)=/);
        await expect(page.getByTestId('turn-slots-column')).toBeVisible();
    });

    // 흐름 시트(모바일 --z-sheet · 태블릿 fixed)가 도움말 서랍을 덮지 않는다(K7 10-02 발견) — 서랍은 사용자가 직접 연 층이라 시트 위.
    test('사유 시트의 「도움말」을 누르면 도움말 서랍이 흐름 위에 보이고, 흐름 주소는 그대로다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        const sheet = await rejectedSheet(page, testInfo);
        await press(sheet.getByRole('link', { name: /^도움말 — / }), testInfo);
        await expect(page).toHaveURL(/[?&]help=/);
        await expect(page).toHaveURL(/[?&]do=action\.move\b/);
        await expect(sheet).toHaveCount(0);
        await expect(page.getByRole('complementary', { name: '도움말' })).toBeVisible();
        expect(await drawerOnTop(page)).toBe(true);
        await expect(flow(page)).toHaveCount(1);
    });

    test('태블릿(1024)에서도 사유 시트의 도움말 서랍이 흐름 위에 보인다', async ({ page }, testInfo) => {
        await page.setViewportSize({ width: 1024, height: 900 });
        const sheet = await rejectedSheet(page, testInfo);
        await press(sheet.getByRole('link', { name: /^도움말 — / }), testInfo);
        await expect(page.getByRole('complementary', { name: '도움말' })).toBeVisible();
        expect(await drawerOnTop(page)).toBe(true);
    });

    // 머리줄 「?」(이 화면 도움말)로 연 서랍도 같다 — 서랍은 흐름과 같은 층 · DOM 순서로 위(K3). 그 순서가 바뀌면 여기가 빨개진다.
    for (const width of [1024, 0]) {
        test(`흐름을 연 채 머리줄 「?」로 연 도움말 서랍이 흐름 위에 보인다${width ? `(${width})` : ''}`, { tag: width ? [] : [BOTH] }, async ({ page }, testInfo) => {
            if (width) {
                test.skip(isMobile(testInfo), '태블릿 폭은 데스크톱 프로젝트에서만 잰다');
                await page.setViewportSize({ width, height: 900 });
            }
            await openFlow(page, fresh(), 'do=action.move');
            await press(page.getByRole('link', { name: '이 화면 도움말' }), testInfo);
            await expect(page).toHaveURL(/[?&]help=/);
            await expect(page.getByRole('complementary', { name: '도움말' })).toBeVisible();
            expect(await drawerOnTop(page)).toBe(true);
            await expect(flow(page)).toHaveCount(1);
        });
    }

    test('도움말 서랍을 닫으면 흐름이 그대로 남는다(초안 · 주소)', { tag: [BOTH] }, async ({ page }, testInfo) => {
        const sheet = await rejectedSheet(page, testInfo);
        await press(sheet.getByRole('link', { name: /^도움말 — / }), testInfo);
        const drawer = page.getByRole('complementary', { name: '도움말' });
        await expect(drawer).toBeVisible();
        await press(drawer.getByRole('button', { name: '도움말 닫기(Esc)' }), testInfo);
        await expect(drawer).toHaveCount(0);
        await expect(page).not.toHaveURL(/[?&]help=/);
        await expect(page).toHaveURL(/[?&]do=action\.move\b/);
        await expect(flow(page)).toBeVisible();
        await expect(flow(page).getByRole('option', { name: /영천/ })).toHaveAttribute('aria-selected', 'true');
    });
});
