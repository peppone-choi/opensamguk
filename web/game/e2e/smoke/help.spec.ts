// 도움말 독립 페이지(/game/help, P-A01) 스모크 — 도움말 API 대역(help-api.ts, 저장소 data/help)으로 백엔드 없이 돈다.
// e2e/smoke 규칙(support/parity.ts): @both = 데스크톱 · 모바일(390 × 844 터치) 같은 흐름, @mobile-only. 누르기는 press(모바일 = 탭).
import { expect, test, type Page } from '@playwright/test';
import { BOTH, MOBILE_ONLY, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
import { serveHelpApi, type HelpApiOptions } from './help-api';

const PANEL = '[data-help-panel="page"]';

async function open(page: Page, view = '', options: HelpApiOptions = {}) {
    await serveHelpApi(page, options);
    const url = `/game/help?from=war-room${view ? `&view=${encodeURIComponent(view)}` : ''}`;
    // 개발 서버가 첫 요청에 화면을 컴파일하며 이동을 한 번 끊을 수 있다(ERR_ABORTED) — 한 번만 다시 간다.
    await page.goto(url).catch(async (e: Error) => {
        if (!/ERR_ABORTED|frame was detached/.test(e.message)) throw e;
        await page.goto(url);
    });
    const panel = page.locator(PANEL);
    await expect(panel).toBeVisible({ timeout: 60_000 });
    return panel;
}

test.describe('도움말', () => {
    test('이 화면 → 주제 → 안 되는 경우 → 사유 → 뒤로', { tag: [BOTH] }, async ({ page }, info) => {
        const panel = await open(page);
        await expect(panel.getByRole('button', { name: /현장 행동\s*26/ })).toHaveAttribute('aria-pressed', 'true');
        await expect(panel.getByText('현의 농지를 개간해 농업 기반을 키우는 직접 행동입니다.')).toBeVisible();
        await press(panel.getByRole('button', { name: /이동\s*5/ }), info);
        await press(panel.getByRole('button', { name: /^출병/ }), info);
        await expect(panel.getByRole('heading', { name: '출병' })).toBeVisible();
        await expect(page).toHaveURL(/view=input%3Aaction\.deploy/);
        const rules = panel.getByRole('region', { name: '이 명령의 규칙' });
        await expect(rules).toContainText('명령 목록 12순 · 한 순에 하나 · 이동 단계');
        await expect(panel.getByText('초안').first()).toBeVisible();
        await press(panel.getByRole('button', { name: /안 되는 경우 \d+가지/ }), info);
        const firstFail = panel.locator('li button').filter({ hasText: /\S/ }).first();
        await expect(firstFail).not.toHaveText('…');
        await press(firstFail, info);
        await expect(panel.getByText('이렇게 하면 됩니다')).toBeVisible();
        await page.goBack();
        await expect(panel.getByRole('heading', { name: '출병' })).toBeVisible();
        await expect(panel).not.toContainText(/action\.|commands\.|HANDLER_DEFINED|NOT_LORD/);
        expect(await titleOnlyInfo(page, PANEL)).toEqual([]);
        await expectNoHorizontalOverflow(page);
    });

    test('찾기: 한 글자는 보내지 않고, 두 글자부터 찾는다', { tag: [BOTH] }, async ({ page }, info) => {
        const log: string[] = [];
        const panel = await open(page, '', { log });
        const box = panel.getByRole('searchbox');
        await box.fill('인');
        await expect(panel.getByText('두 글자 이상 적어 주세요.')).toBeVisible();
        await page.waitForTimeout(500);
        expect(log.filter((l) => l.startsWith('/api/help/search'))).toEqual([]);
        await box.fill('인물');
        await expect(panel.getByRole('status').filter({ hasText: /결과 \d+개/ })).toBeVisible();
        await expect(page).toHaveURL(/view=search%3A/);
        await press(panel.getByRole('list', { name: '찾은 도움말' }).getByRole('button').first(), info);
        await expect(panel.getByRole('heading').first()).toBeVisible();
        await box.fill('화계없음');
        await expect(panel.getByText('"화계없음"에 맞는 도움말이 없습니다')).toBeVisible();
    });

    test('승인된 새 명령 이름으로 보인다(원문 교체는 C7)', { tag: [BOTH] }, async ({ page }) => {
        const panel = await open(page, 'input:action.tradeGrain');
        await expect(panel.getByRole('heading', { name: '쌀 사고팔기' })).toBeVisible();
        // 승인된 이름만 바꾼다 — 원문의 다른 말(「군량을 사고파는」)은 C7 원문 교체(K7-COPY-06/07) 몫이라 그대로 둔다.
        await expect(panel).not.toContainText(/군량매매|숙련전환/);
        await expect(panel.getByText('쌀 사고팔기를 선택하고', { exact: false })).toBeVisible();
    });

    test('서버 대기(503)는 오류와 다른 모양', { tag: [BOTH] }, async ({ page }) => {
        const panel = await open(page, 'input:action.enlist', { forceStatus: { status: 503, code: 'WORLD_UNAVAILABLE' } });
        await expect(panel.getByText('서버가 준비 중이라 도움말도 잠시 쉽니다')).toBeVisible();
    });

    test('첫걸음 탭 — 본 서버는 안내판', { tag: [BOTH] }, async ({ page }) => {
        const panel = await open(page, 'start');
        await expect(panel.getByText('본 서버에서는 숫자 칩이 없습니다. 여덟 걸음을 어디서 하는지 안내만 합니다.')).toBeVisible();
        await expect(panel.getByRole('tab', { name: '첫걸음' })).toHaveAttribute('aria-selected', 'true');
    });

    test('모바일: 누를 것은 모두 44 이상 · 가운데를 누르면 그 단추', { tag: [MOBILE_ONLY] }, async ({ page }, info) => {
        const panel = await open(page, 'input:action.enlist');
        await press(panel.getByRole('button', { name: /안 되는 경우/ }), info);
        await expect(panel.locator('li button').first()).toBeVisible();
        expect(await smallTouchTargets(page, PANEL)).toEqual([]);
        const toggle = panel.getByRole('button', { name: /안 되는 경우/ });
        const box = (await toggle.boundingBox())!;
        const hit = await page.evaluate(([x, y]) => document.elementFromPoint(x, y)?.closest('button')?.textContent ?? '', [box.x + box.width / 2, box.y + box.height / 2]);
        expect(hit).toContain('안 되는 경우');
        await expectNoHorizontalOverflow(page);
    });
});

// ---- 셸 도움말 서랍(`?help=`, components/shell/HelpDrawer) ----------------------------------------------------------
// 셸 스모크(shell.spec.ts)와 같은 합성 로그인 · front-info 로 부 · 월단평을 열고, 머리줄 「?」로 서랍을 연다.
async function openShellWithHelp(page: Page, path = '/game/retinue/yuedan') {
    const baseURL = test.info().project.use.baseURL ?? 'http://localhost:3001';
    await page.context().addCookies([{ name: 'sam_server', value: 'pep', url: baseURL }]);
    await serveHelpApi(page);
    // 뒤에 건 route 가 먼저 받는다 — front-info · 턴 루프 읽기만 셸 몫으로 가로챈다.
    await page.route((url) => url.pathname.endsWith('/front-info'), (r) => r.fulfill({ json: {
        result: true,
        global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
        general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
        nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
    } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.goto(path, { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '월단평' })).toBeVisible({ timeout: 60_000 });
}

/** 누를 것의 가운데를 다른 상자가 덮는지(셸 스모크 · K10 「덮임」과 같은 방법 — elementFromPoint). */
async function coveredIn(page: Page, selector: string): Promise<string[]> {
    return page.locator(selector).first().evaluate((root) => {
        const out: string[] = [];
        for (const el of Array.from(root.querySelectorAll<HTMLElement>('a, button, input'))) {
            const r = el.getBoundingClientRect();
            if (r.width === 0 || r.height === 0) continue;
            const cx = r.x + r.width / 2;
            const cy = r.y + r.height / 2;
            if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) continue;
            const hit = document.elementFromPoint(cx, cy);
            if (hit !== el && !el.contains(hit) && !hit?.contains(el)) out.push(`${(el.textContent ?? '').trim()} ← ${hit?.tagName}.${hit?.className}`);
        }
        return out;
    });
}

const DRAWER = 'aside[aria-label="도움말"]';

test.describe('도움말 서랍', () => {
    test('머리줄 「?」로 열고(문서를 다시 받지 않음) 주제 · 뒤로 · 닫기 — 44 · 덮임 0 · 넘침 0', { tag: [BOTH] }, async ({ page }, info) => {
        await openShellWithHelp(page);
        await page.evaluate(() => { (window as unknown as { __k7: number }).__k7 = 1; });
        await press(page.getByRole('link', { name: '이 화면 도움말' }), info);
        const drawer = page.locator(DRAWER);
        await expect(drawer).toBeVisible();
        await expect(page).toHaveURL(/[?&]help=home/);
        expect(await page.evaluate(() => (window as unknown as { __k7?: number }).__k7)).toBe(1);
        await expect(drawer.getByText('부에서 하는 일')).toBeVisible();
        const box = (await drawer.boundingBox())!;
        if (isMobile(info)) {
            expect(Math.round(box.x)).toBe(0);
            expect(Math.round(box.width)).toBe(390);
        } else {
            expect(Math.round(box.width)).toBe(400);
        }
        expect(await smallTouchTargets(page, DRAWER)).toEqual([]);
        expect(await coveredIn(page, DRAWER)).toEqual([]);
        expect(await titleOnlyInfo(page, DRAWER)).toEqual([]);
        await expectNoHorizontalOverflow(page);

        await press(drawer.getByRole('button', { name: /^인재탐색/ }), info);
        await expect(drawer.getByRole('heading', { name: '인재탐색' })).toBeVisible();
        await expect(page).toHaveURL(/help=input%3Aaction\.search/);
        await expect(page.getByRole('heading', { level: 2, name: '월단평' })).toBeAttached(); // 본문은 그대로 — 모달 아님
        await press(drawer.getByRole('button', { name: '앞 보기로' }), info);
        await expect(drawer.getByText('부에서 하는 일')).toBeVisible();
        await press(drawer.getByRole('button', { name: '도움말 닫기(Esc)' }), info);
        await expect(drawer).toBeHidden();
        await expect(page).not.toHaveURL(/help=/);
        expect(await page.evaluate(() => (window as unknown as { __k7?: number }).__k7)).toBe(1);
    });

    test('데스크톱: 레일 「도움말」로 열면 본문 옆에 서고 찾기칸에 포커스, Esc 로 닫힌다', { tag: ['@desktop-only'] }, async ({ page }) => { // 레일은 데스크톱 · 태블릿만(모바일 프로젝트의 grep 에 안 걸린다)
        await openShellWithHelp(page);
        await page.getByRole('navigation', { name: '게임 메뉴' }).getByRole('link', { name: '도움말' }).click();
        const drawer = page.locator(DRAWER);
        await expect(drawer.getByRole('searchbox')).toBeFocused();
        const main = (await page.getByRole('main', { name: '게임 콘텐츠' }).boundingBox())!;
        const side = (await drawer.boundingBox())!;
        expect(side.x).toBeGreaterThanOrEqual(main.x + main.width - 1); // 덮지 않고 옆에 선다
        await page.keyboard.press('Escape');
        await expect(drawer).toBeHidden();
    });

    test('모바일: 서랍은 머리줄 아래를 가득 덮고 하단 탭을 가린다 — 찾기칸 자동 포커스 없음', { tag: [MOBILE_ONLY] }, async ({ page }, info) => {
        await openShellWithHelp(page);
        await press(page.getByRole('link', { name: '이 화면 도움말' }), info);
        const drawer = page.locator(DRAWER);
        await expect(drawer.getByText('부에서 하는 일')).toBeVisible();
        await expect(drawer.getByRole('searchbox')).not.toBeFocused();
        const box = (await drawer.boundingBox())!;
        expect(Math.round(box.y)).toBe(56);
        expect(Math.round(box.y + box.height)).toBe(844);
        const tab = (await page.getByRole('navigation', { name: '게임 메뉴' }).first().boundingBox())!;
        const hit = await page.evaluate(([x, y]) => document.elementFromPoint(x, y)?.closest('aside')?.getAttribute('aria-label') ?? null, [tab.x + tab.width / 2, tab.y + tab.height / 2]);
        expect(hit).toBe('도움말');
    });

    test('모바일: 「전체」 시트의 도움말로도 연다', { tag: [MOBILE_ONLY] }, async ({ page }, info) => {
        await openShellWithHelp(page);
        await press(page.getByRole('button', { name: '전체' }), info);
        await press(page.getByRole('dialog', { name: '전체 메뉴' }).getByRole('link', { name: '도움말' }), info);
        await expect(page.getByRole('dialog', { name: '전체 메뉴' })).toBeHidden();
        await expect(page.locator(DRAWER).getByText('부에서 하는 일')).toBeVisible();
    });
});
