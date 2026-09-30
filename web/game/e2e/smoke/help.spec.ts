// 도움말 독립 페이지(/game/help, P-A01) 스모크 — 도움말 API 대역(help-api.ts, 저장소 도움말 데이터)으로 백엔드 없이 돈다.
// e2e/smoke 규칙: @both = 데스크톱 · 모바일 두 프로필, @mobile-only. 「그려졌다」와 「누르면 된다」를 따로 본다.
import { expect, test, type Page } from '@playwright/test';
import { serveHelpApi } from './help-api';

async function open(page: Page, view = '', from = 'war-room', options: Parameters<typeof serveHelpApi>[1] = {}) {
    await serveHelpApi(page, options);
    const url = `/game/help?from=${from}${view ? `&view=${encodeURIComponent(view)}` : ''}`;
    // 개발 서버가 첫 요청에 화면을 컴파일하며 이동을 한 번 끊을 수 있다(ERR_ABORTED) — 한 번만 다시 간다.
    await page.goto(url).catch(async (e: Error) => {
        if (!/ERR_ABORTED|frame was detached/.test(e.message)) throw e;
        await page.goto(url);
    });
    const panel = page.locator('[data-help-panel="page"]');
    // 개발 서버는 첫 요청에 화면을 컴파일한다.
    await expect(panel).toBeVisible({ timeout: 60_000 });
    return panel;
}

test.describe('도움말', () => {
    test('이 화면 → 주제 → 안 되는 경우 → 사유 → 뒤로', { tag: '@both' }, async ({ page }) => {
        const panel = await open(page);
        await expect(panel.getByRole('button', { name: /현장 행동\s*26/ })).toHaveAttribute('aria-pressed', 'true');
        await expect(panel.getByText('현의 농지를 개간해 농업 기반을 키우는 직접 행동입니다.')).toBeVisible();
        await panel.getByRole('button', { name: /이동\s*5/ }).click();
        await panel.getByRole('button', { name: /^출병/ }).click();
        await expect(panel.getByRole('heading', { name: '출병' })).toBeVisible();
        await expect(page).toHaveURL(/view=input%3Aaction\.deploy/);
        const rules = panel.getByRole('region', { name: '이 명령의 규칙' });
        await expect(rules).toContainText('명령 목록 12순 · 한 순에 하나 · 이동 단계');
        await expect(panel.getByText('초안').first()).toBeVisible();
        await panel.getByRole('button', { name: /안 되는 경우 \d+가지/ }).click();
        const firstFail = panel.locator('li button').filter({ hasText: /\S/ }).first();
        await expect(firstFail).not.toHaveText('…');
        await firstFail.click();
        await expect(panel.getByText('이렇게 하면 됩니다')).toBeVisible();
        await page.goBack();
        await expect(panel.getByRole('heading', { name: '출병' })).toBeVisible();
        await expect(panel).not.toContainText(/action\.|commands\.|HANDLER_DEFINED|NOT_LORD/);
    });

    test('찾기: 한 글자는 보내지 않고, 두 글자부터 찾는다', { tag: '@both' }, async ({ page }) => {
        const log: string[] = [];
        const panel = await open(page, '', 'war-room', { log });
        const box = panel.getByRole('searchbox');
        await box.fill('인');
        await expect(panel.getByText('두 글자 이상 적어 주세요.')).toBeVisible();
        await page.waitForTimeout(500);
        expect(log.filter((l) => l.startsWith('/api/help/search'))).toEqual([]);
        await box.fill('인물');
        await expect(panel.getByRole('status').filter({ hasText: /결과 \d+개/ })).toBeVisible();
        await expect(page).toHaveURL(/view=search%3A/);
        await panel.getByRole('list', { name: '찾은 도움말' }).getByRole('button').first().click();
        await expect(panel.getByRole('heading').first()).toBeVisible();
        await box.fill('화계없음');
        await expect(panel.getByText('"화계없음"에 맞는 도움말이 없습니다')).toBeVisible();
    });

    test('옛 명령 이름은 새 이름으로(승인 2026-09-30)', { tag: '@both' }, async ({ page }) => {
        const panel = await open(page, 'input:action.tradeGrain');
        await expect(panel.getByRole('heading', { name: '쌀 사고팔기' })).toBeVisible();
        await expect(panel).not.toContainText(/군량|숙련전환/);
        await expect(panel.getByText('쌀을 사고파는 직접 행동입니다.')).toBeVisible();
    });

    test('서버 대기(503)는 오류와 다른 모양', { tag: '@both' }, async ({ page }) => {
        const panel = await open(page, 'input:action.enlist', 'war-room', { forceStatus: { status: 503, code: 'WORLD_UNAVAILABLE' } });
        await expect(panel.getByText('서버가 준비 중이라 도움말도 잠시 쉽니다')).toBeVisible();
    });

    test('첫걸음 탭 — 본 서버는 안내판', { tag: '@both' }, async ({ page }) => {
        const panel = await open(page, 'start');
        await expect(panel.getByText('본 서버에서는 숫자 칩이 없습니다. 여덟 걸음을 어디서 하는지 안내만 합니다.')).toBeVisible();
        await expect(panel.getByRole('tab', { name: '첫걸음' })).toHaveAttribute('aria-selected', 'true');
    });

    test('모바일: 누를 것은 모두 44px 이상 · 가운데를 누르면 그 단추', { tag: '@mobile-only' }, async ({ page }) => {
        const panel = await open(page, 'input:action.enlist');
        await panel.getByRole('button', { name: /안 되는 경우/ }).click();
        const small = await panel.locator('button, a, input').evaluateAll((els) => els
            .map((e) => { const r = e.getBoundingClientRect(); return { t: (e.textContent || e.getAttribute('aria-label') || '').trim().slice(0, 20), w: r.width, h: r.height }; })
            .filter((r) => r.w > 0 && r.h > 0 && (r.h < 44 || r.w < 44)));
        expect(small).toEqual([]);
        const tabs = panel.getByRole('button', { name: /안 되는 경우/ });
        const box = (await tabs.boundingBox())!;
        const hit = await page.evaluate(([x, y]) => document.elementFromPoint(x, y)?.closest('button')?.textContent ?? '', [box.x + box.width / 2, box.y + box.height / 2]);
        expect(hit).toContain('안 되는 경우');
    });
});
