// 도움말 독립 페이지(/game/help, P-A01) 스모크 — 도움말 API 대역(help-api.ts, 저장소 data/help)으로 백엔드 없이 돈다.
// e2e/smoke 규칙(support/parity.ts): @both = 데스크톱 · 모바일(390 × 844 터치) 같은 흐름, @mobile-only. 누르기는 press(모바일 = 탭).
import { expect, test, type Page } from '@playwright/test';
import { BOTH, MOBILE_ONLY, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
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
