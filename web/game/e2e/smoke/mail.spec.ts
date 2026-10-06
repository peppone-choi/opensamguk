// 서신(P-Q02) — /game/mailbox 를 백엔드 없이 합성 자료로 돈다(지도 스모크와 같은 방식: 로그인 · front-info 합성, 나머지 게임 읽기는 503).
// 두 프로필(@both): 서신 화면 안 누를 영역 44 · 네이티브 disabled 0 · title 0 · 가로 넘침 0, 받은 서신 읽기(모바일은 목록 → 읽기 → 목록),
// 개인 서신 쓰기(사람 고르기 → 본문 → 보내기 → 엔진 결과의 받는 사람 확인 뒤 「보냈습니다」), 도움 요청 양식(D68 — 서버 대기 · 준비 중).
// 받는 사람 목록(/generals)은 받는 사람 칸에 처음 초점이 가거나 누를 때만 읽는다 — 그 전 읽기 0회를 센다.
import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
// 서버 대기 표지 읽기(#1335) — 의존 없는 도우미 파일만 가져온다.
import { serverWaitRows } from '../../../shared/src/serverWaitTesting';

const API = '/api/game/api';
const ME = 7;
const ROOT = '[data-testid="mail-screen"]';

interface Server { sent: { mailbox: number; text: string }[]; generalsReads: number; waitingRequests?: number }
const fresh = (): Server => ({ sent: [], generalsReads: 0 });

const party = (id: number, name: string) => ({ id, name, nation_id: 1, nation: '조조', color: '#4f7fbf' });
const general = (generalId: number, name: string, npc = 0) => ({
    generalId, name, nationId: 1, nationName: '조조', nationColor: '#4f7fbf', npc, officerLevel: 1, cityName: '허현', picture: null, imageServer: 0,
});

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
                general: { hasGeneral: true, generalId: ME, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/mailbox/recent') {
            const now = new Date().toISOString();
            return json(route, 200, {
                result: true, sequence: 3, national: [], public: [], diplomacy: [],
                private: [
                    { id: 3, msgType: 'private', src: party(2, '순욱'), dest: party(ME, '하후돈'), text: '<p><strong>허현</strong>으로 오십시오</p>', option: null, time: now },
                    { id: 2, msgType: 'private', src: party(ME, '하후돈'), dest: party(2, '순욱'), text: '<p>알겠습니다</p>', option: null, time: now },
                ],
            });
        }
        if (path === '/generals') {
            server.generalsReads += 1;
            return json(route, 200, [general(ME, '하후돈'), general(2, '순욱'), general(3, '관해', 2)]);
        }
        if (path === '/commands/dispatches') return json(route, 200, { result: true, dispatches: [] });
        if (path === '/commands/political-consent-options') return json(route, 200, Array.from({ length: server.waitingRequests ?? 0 }, (_, i) => ({
            inputId: 'action.oath', issuerGeneralId: i + 20, issuerName: `장수 ${i + 20}`, available: true, accepted: null,
        })));
        if (path === '/command/readLatestMessage') return json(route, 202, { status: 'AVAILABLE', requestId: 'read-1' });
        if (path === '/command/sendMessage' && route.request().method() === 'POST') {
            server.sent.push(route.request().postDataJSON());
            return json(route, 202, { status: 'AVAILABLE', requestId: 'm-1' });
        }
        if (path === '/command/result/m-1') {
            return json(route, 200, {
                status: 'RESOLVED', requestId: 'm-1', ok: true, type: 'sendMessage',
                result: { type: 'sendMessage', ok: true, msgType: 'private', recipientId: 2, recipientName: '순욱', msgID: 9 },
            });
        }
        return json(route, 503, {});
    });
}

async function open(page: Page, server: Server) {
    await serve(page, server);
    await page.goto('/game/mail', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: '서신', exact: true })).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole('list', { name: '개인 서신' })).toBeVisible({ timeout: 60_000 });
}

test.describe('서신', () => {
    test('규칙: 누를 영역 44 · disabled 0 · title 0 · 가로 넘침 0', { tag: [BOTH] }, async ({ page }, testInfo) => {
        const server = fresh();
        await open(page, server);
        expect(await smallTouchTargets(page, ROOT)).toEqual([]);
        expect(await titleOnlyInfo(page, ROOT)).toEqual([]);
        expect(await page.locator(`${ROOT} :disabled`).count()).toBe(0);
        await expectNoHorizontalOverflow(page);
        // 쓰기 칸(모바일은 따로 여는 화면) — 사람 고르기 · 서식 도구 · 보내기도 같은 규칙.
        await page.getByRole('button', { name: '서신 쓰기' }).click();
        const compose = page.getByRole('region', { name: '서신 쓰기' });
        // 받는 사람 칸을 아직 안 썼다 — 목록을 읽지 않고, 「불러오는 중」 대신 안내 한 줄.
        await expect(compose.getByText('찾기 칸을 누르면 받을 사람 목록이 나옵니다.')).toBeVisible();
        expect(server.generalsReads).toBe(0);
        expect(await smallTouchTargets(page, ROOT)).toEqual([]);
        await press(compose.getByRole('searchbox', { name: '이름 · 초성으로 찾기' }), testInfo);
        await expect(compose.getByRole('option', { name: /순욱/ })).toBeVisible();
        expect(server.generalsReads).toBe(1);
        expect(await smallTouchTargets(page, ROOT)).toEqual([]);
        expect(await titleOnlyInfo(page, ROOT)).toEqual([]);
        expect(await page.locator(`${ROOT} :disabled`).count()).toBe(0);
        await expectNoHorizontalOverflow(page);
    });

    test('받은 서신을 누르면 읽기 칸에 서식 그대로 열리고, 모바일은 목록으로 돌아간다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page, fresh());
        const list = page.getByRole('list', { name: '개인 서신' });
        await expect(list.getByRole('button')).toHaveCount(2);
        await press(list.getByRole('button').filter({ hasText: '받음' }), testInfo);
        const card = page.getByRole('article', { name: '받은 서신 — 순욱' });
        await expect(card).toBeVisible();
        await expect(card.locator('strong')).toHaveText('허현');
        await expect(card).toContainText('순욱 → 나');
        if (isMobile(testInfo)) {
            await expect(list).toBeHidden(); // 한 화면씩 — 읽기 화면
            await press(page.getByRole('button', { name: '← 서신 목록' }).first(), testInfo);
            await expect(list).toBeVisible();
        }
    });

    test('사람을 골라 개인 서신을 보내면 그 사람 id 로 보내고, 엔진 결과의 받는 사람을 확인한 뒤 「보냈습니다」', { tag: [BOTH] }, async ({ page }, testInfo) => {
        const server = fresh();
        await open(page, server);
        await press(page.getByRole('button', { name: '서신 쓰기' }), testInfo);
        const compose = page.getByRole('region', { name: '서신 쓰기' });
        await expect(compose).toBeVisible();
        const send = compose.getByRole('button', { name: '보내기' });
        await expect(send).toHaveAttribute('aria-disabled', 'true');
        expect(server.generalsReads).toBe(0);
        await press(compose.getByRole('searchbox', { name: '이름 · 초성으로 찾기' }), testInfo);
        // 사람 고르기(K3 PeoplePicker) 행은 option — NPC 행은 보이되 서버 대기로 막혀 있다.
        await expect(compose.getByRole('option', { name: /관해/ })).toHaveAttribute('aria-disabled', 'true');
        expect(server.generalsReads).toBe(1);
        await press(compose.getByRole('option', { name: /순욱/ }), testInfo);
        await expect(compose.getByText('받는 사람 — 순욱')).toBeVisible();
        const editor = compose.getByRole('textbox', { name: '서신 내용' });
        await press(editor, testInfo);
        await page.keyboard.type('곧 가겠습니다');
        await expect(send).not.toHaveAttribute('aria-disabled', 'true');
        // 모바일 하단 탭 막대(sticky)는 화면 맨 아래 끝을 덮는다 — 「보일 만큼만」 스크롤하면 단추가 막대 밑에 놓인다(셸에
        // scroll-padding-bottom 이 없어서). 셸 scroll-padding 대기(K3) — 고쳐지면 이 줄을 지워 같은 결함을 다시 잡게 한다.
        // 그동안은 사람처럼 단추를 화면 가운데로 올린 뒤 누른다.
        await send.evaluate((el) => el.scrollIntoView({ block: 'center' }));
        await press(send, testInfo);
        await expect(compose.getByText('순욱에게 보냈습니다')).toBeVisible();
        expect(server.sent).toHaveLength(1);
        expect(server.sent[0].mailbox).toBe(2);
        expect(server.sent[0].text).toContain('곧 가겠습니다');
    });

    test('도움 요청(D68 · 원장 D111) — 개인 서신 쓰기에서 「도움 요청」(오가도 쓰던 글 서신 남음): 서버가 줄 칸 · 결정 대기는 서버 대기(H01), 보내기 「준비 중」(data-input-id 없음), 요청 탭 「보낸 것」도 서버 대기', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page, fresh());
        await press(page.getByRole('button', { name: '서신 쓰기' }), testInfo);
        // 쓰던 글 서신이 「도움 요청」을 오가도 남는다(#1399 리뷰 — 양식을 갈아 끼우지 않고 숨긴다).
        const editor = page.getByRole('region', { name: '서신 쓰기' }).getByRole('textbox', { name: '서신 내용' });
        await press(editor, testInfo);
        await page.keyboard.type('곧 가겠습니다');
        const kinds = page.getByRole('group', { name: '서신 종류' });
        await press(kinds.getByRole('button', { name: '도움 요청' }), testInfo);
        await expect(editor).toBeHidden();
        await press(kinds.getByRole('button', { name: '글 서신' }), testInfo);
        await expect(editor).toContainText('곧 가겠습니다');
        await press(kinds.getByRole('button', { name: '도움 요청' }), testInfo);
        const form = page.getByRole('region', { name: '도움 요청 쓰기' });
        await expect(form).toBeVisible();
        expect([...new Set(await form.evaluate(serverWaitRows))].sort()).toEqual(
            ['H01 · 기한', 'H01 · 받는 사람', 'H01 · 받는 사람 범위', 'H01 · 보낼 곳', 'H01 · 자원 · 상한', 'H01 · 판단 규칙 · 빈도'].sort(),
        );
        await press(form.getByRole('group', { name: '도움 종류' }).getByRole('button', { name: '병력' }), testInfo);
        await expect(form.locator('[data-server-wait="H01 · 병력 단위"]')).toBeVisible();
        await expect(form.getByRole('textbox', { name: '본문(선택)' })).toHaveAccessibleDescription('본문은 전달만 됩니다 — 요청 내용은 위 양식으로만 판단합니다');
        await expect(form.getByRole('button', { name: '도움 요청 보내기' })).toHaveAttribute('aria-disabled', 'true');
        await expect(form.locator('[data-input-id]')).toHaveCount(0);
        expect(await smallTouchTargets(page, ROOT)).toEqual([]);
        expect(await titleOnlyInfo(page, ROOT)).toEqual([]);
        expect(await page.locator(`${ROOT} :disabled`).count()).toBe(0);
        await expectNoHorizontalOverflow(page);
        await press(page.getByRole('tab', { name: /^요청/ }), testInfo);
        await press(page.getByRole('group', { name: '요청 — 받은 것 · 보낸 것' }).getByRole('button', { name: '보낸 것' }), testInfo);
        await expect(page.locator(`${ROOT} [data-server-wait="H01 · 보낸 요청 읽기"]`)).toBeVisible();
        expect(await smallTouchTargets(page, ROOT)).toEqual([]);
        await expectNoHorizontalOverflow(page);
    });

    test('접근성: 서신 화면 axe 「심각」 위반 0(탭 묶음 · 목록 · 쓰기 칸)', { tag: [BOTH] }, async ({ page }) => {
        await open(page, fresh());
        const result = await new AxeBuilder({ page }).include(ROOT).analyze();
        expect(result.violations.filter((v) => v.impact === 'critical').map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(' | ')}`)).toEqual([]);
    });

    test('탭과 쓰기 단추가 겹치지 않고 좁은 화면에서는 가로로 밀어 누를 수 있다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page, { ...fresh(), waitingRequests: 12 });
        await page.evaluate(() => document.fonts.ready);
        const widths = isMobile(testInfo) ? [360, 390] : [page.viewportSize()!.width];
        const tabs = page.getByRole('tablist', { name: '서신 묶음' });
        const last = tabs.getByRole('tab', { name: '요청 12', exact: true });
        await expect(last).toBeVisible();
        for (const width of widths) {
            await page.setViewportSize({ width, height: page.viewportSize()!.height });
            await press(tabs.getByRole('tab', { name: '개인', exact: true }), testInfo);
            const write = page.getByRole('button', { name: '서신 쓰기', exact: true });
            const lastTab = await last.boundingBox();
            const button = await write.boundingBox();
            expect(lastTab).not.toBeNull();
            expect(button).not.toBeNull();
            expect(lastTab!.x + lastTab!.width, `${width}px: 마지막 탭이 쓰기 단추 앞에서 끝난다`).toBeLessThanOrEqual(button!.x);
            if (isMobile(testInfo) && width === 360) {
                const scrolled = await tabs.evaluate((el) => {
                    const row = el.parentElement!;
                    row.scrollLeft = row.scrollWidth;
                    return row.scrollLeft;
                });
                expect(scrolled, `${width}px: 탭 줄을 가로로 밀 수 있다`).toBeGreaterThan(0);
            }
            await press(write, testInfo);
            await expect(page.getByRole('region', { name: '서신 쓰기', exact: true })).toBeVisible();
            await expectNoHorizontalOverflow(page);
        }
    });
});
