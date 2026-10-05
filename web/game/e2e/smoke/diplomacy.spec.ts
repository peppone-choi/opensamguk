// 외교(P-K02) — /game/court/diplomacy 를 백엔드 없이 합성 자료로 돈다(로그인 · front-info 합성, 나머지 게임 읽기는 503).
// 두 프로필(@both): 외교 칸 · 관계 지도 자리의 누를 영역 44 · 네이티브 disabled 0 · title 0 · 가로 넘침 0, 「세력 × 세력 표」 펼침(표 안에서만
// 가로로 민다), 외교권 없음 → 「보기만」 · 쓰기 대신 안내, 외교권자 → 받는 세력을 골라 그 세력 서신함으로 보내고 엔진이 외교 서신으로 적었는지 확인.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
const ME = 7;
const nation = (id: number, name: string, color: string, cities: string[]) => ({ nation: id, name, color, type: '', level: 1, capital: 0, gennum: 1, cities, power: 0 });

interface Server { diplomat: boolean; sent: { mailbox: number; text: string }[] }

async function serve(page: Page, server: Server) {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const path = new URL(route.request().url()).pathname.slice(API.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: ME, name: server.diplomat ? '조조' : '하후돈', nationId: 1, officerLevel: server.diplomat ? 12 : 5, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/diplomacy/conflict') {
            return json(route, 200, {
                result: true, conflict: [], myNationID: 1,
                // 실제 서버 규모(여덟 세력) — 세력 × 세력 표가 좁은 폭보다 넓어야 「표 안에서만 민다」를 잴 수 있다.
                nations: [
                    nation(1, '조조', '#4f7fbf', ['허현', '진류']), nation(2, '원소', '#b04a3c', ['업현', '남피', '평원']), nation(3, '유표', '#4f8f5a', ['양양']),
                    nation(4, '손책', '#b9b2a3', []), nation(5, '유비', '#5f9a6a', ['소패']), nation(6, '원술', '#9a7a3a', ['수춘']),
                    nation(7, '마등', '#7a6a9a', ['무위']), nation(8, '공손찬', '#6a8a9a', ['계현']),
                ],
                diplomacyList: { 1: { 2: 0, 3: 7, 4: 2, 5: 2, 6: 1, 7: 2, 8: 2 }, 2: { 3: 1, 4: 2, 8: 0 }, 3: { 4: 2 } },
            });
        }
        if (path === '/mailbox/recent') {
            const now = new Date().toISOString();
            return json(route, 200, {
                result: true, sequence: 2, private: [], public: [], national: [],
                diplomacy: [
                    server.diplomat
                        ? { id: 2, msgType: 'diplomacy', src: { id: 20, name: '전풍', nation_id: 2, nation: '원소', color: '#b04a3c' }, dest: { id: 0, name: '', nation_id: 1, nation: '조조', color: '#4f7fbf' }, text: '<p>불가침을 청합니다</p>', option: { action: 'no_aggression' }, time: now }
                        : { id: 2, msgType: 'diplomacy', src: { id: 20, name: '전풍', nation_id: 2, nation: '원소', color: '#b04a3c' }, dest: { id: 0, name: '', nation_id: 1, nation: '조조', color: '#4f7fbf' }, text: '(외교 메시지입니다)', option: { invalid: true }, time: now },
                ],
            });
        }
        if (path === '/contacts') {
            return json(route, 200, { nation: [
                { mailbox: 9001, name: '조조', color: '#4f7fbf', general: [[ME, server.diplomat ? '조조' : '하후돈', server.diplomat ? 5 : 0]] },
                { mailbox: 9002, name: '원소', color: '#b04a3c', general: [[20, '전풍', 4]] },
                { mailbox: 9003, name: '유표', color: '#4f8f5a', general: [] },
            ] });
        }
        if (path === '/command/readLatestMessage') return json(route, 202, { status: 'AVAILABLE', requestId: 'read-1' });
        if (path === '/command/sendMessage' && route.request().method() === 'POST') {
            server.sent.push(route.request().postDataJSON());
            return json(route, 202, { status: 'AVAILABLE', requestId: 'd-1' });
        }
        if (path === '/command/result/d-1') {
            return json(route, 200, { status: 'RESOLVED', requestId: 'd-1', ok: true, type: 'sendMessage', result: { type: 'sendMessage', ok: true, msgType: 'diplomacy', msgID: 9 } });
        }
        return json(route, 503, {});
    });
}

async function open(page: Page, server: Server) {
    await serve(page, server);
    await page.goto('/game/court/diplomacy', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: '외교', exact: true })).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole('list', { name: '세력별 관계' })).toBeVisible({ timeout: 60_000 });
}

const PANEL = '[data-testid="diplomacy-panel"]';
const MAP = 'section[aria-label="관계 지도"]';

/** 칸 안에서 옆으로 밀리는 상자(aria-label 또는 클래스). 세력 × 세력 표 상자만 옆으로 밀려야 한다 — 칸 몸통이 밀리면 안 된다. */
async function sidewaysScrollers(page: Page): Promise<string[]> {
    return page.locator(PANEL).evaluate((root) => Array.from(root.querySelectorAll<HTMLElement>('*'))
        // 사람이 옆으로 밀 수 있는 상자만(auto · scroll). hidden · clip(숨김 라벨 등)은 밀리지 않는다.
        .filter((el) => el.scrollWidth > el.clientWidth + 1 && ['auto', 'scroll'].includes(getComputedStyle(el).overflowX))
        .map((el) => el.getAttribute('aria-label') ?? el.className));
}

async function rules(page: Page) {
    for (const root of [PANEL, MAP]) {
        expect(await smallTouchTargets(page, root)).toEqual([]);
        expect(await titleOnlyInfo(page, root)).toEqual([]);
        expect(await page.locator(`${root} :disabled`).count()).toBe(0);
    }
    await expectNoHorizontalOverflow(page);
}

test.describe('외교', () => {
    test('외교권 없음: 규칙 · 「보기만」 안내 · 세력 × 세력 표 · 외교 서신은 가려지고 쓰기 대신 안내', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page, { diplomat: false, sent: [] });
        await expect(page.getByRole('note')).toContainText('외교는 군주가 합니다');
        await rules(page);
        const world = page.getByRole('region', { name: '천하 관계' });
        await expect(world.getByRole('list', { name: '다른 세력끼리' })).toContainText('원소 · 유표');
        await press(world.getByRole('button', { name: '세력 × 세력 표로 보기' }), testInfo);
        await expect(world.getByRole('region', { name: '세력 × 세력 표' })).toBeVisible();
        await rules(page); // 표가 넓어도 화면은 넘치지 않는다
        const scrollers = await sidewaysScrollers(page);
        expect(scrollers.filter((s) => s !== '세력 × 세력 표')).toEqual([]); // 칸 몸통은 옆으로 밀리지 않는다
        if (isMobile(testInfo)) expect(scrollers).toContain('세력 × 세력 표'); // 좁은 폭에서는 표 안에서만 민다
        await press(page.getByRole('tab', { name: '외교 서신' }), testInfo);
        await expect(page.getByText('외교 서신은 군주 · 외교권자만 봅니다').first()).toBeVisible();
        await press(page.getByRole('button', { name: '외교 서신 쓰기' }), testInfo);
        await expect(page.getByText('외교 서신은 군주 · 외교권자만 씁니다')).toBeVisible();
        await rules(page);
        await page.screenshot({ path: testInfo.outputPath('no-diplomat-letters.png') });
    });

    test('외교권자: 받은 제의는 서버 대기 안내(수락 · 거절 없음) · 받는 세력을 골라 외교 서신을 보낸다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        const server: Server = { diplomat: true, sent: [] };
        await open(page, server);
        await expect(page.getByRole('note')).toHaveCount(0);
        await press(page.getByRole('tab', { name: '외교 서신' }), testInfo);
        const letter = page.getByRole('article', { name: '받은 서신 — 원소' });
        await expect(letter).toContainText('불가침 제의');
        await expect(letter.getByText('제의에 답하기는 서버 준비 중입니다')).toBeVisible();
        await expect(letter.getByRole('button', { name: /^(수락|거절)$/ })).toHaveCount(0);
        await press(page.getByRole('button', { name: '외교 서신 쓰기' }), testInfo);
        const compose = page.getByRole('region', { name: '외교 서신 쓰기' });
        const nations = compose.getByRole('listbox', { name: '받는 세력' });
        await expect(nations.getByRole('option')).toHaveText(['원소', '유표']);
        await rules(page);
        await press(nations.getByRole('option', { name: '원소' }), testInfo);
        const editor = compose.getByRole('textbox', { name: '서신 내용' });
        await press(editor, testInfo);
        await page.keyboard.type('동맹을 청합니다');
        await press(compose.getByRole('button', { name: '보내기' }), testInfo);
        await expect(compose.getByText('원소에 외교 서신을 보냈습니다')).toBeVisible();
        expect(server.sent).toHaveLength(1);
        expect(server.sent[0].mailbox).toBe(9002);
        expect(server.sent[0].text).toContain('동맹을 청합니다');
        await page.screenshot({ path: testInfo.outputPath('diplomat-letters.png') });
    });
});
