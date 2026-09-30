// 요청 카드(K6 · K4 · K8 공용) — /parts-lab/requests(기능 플래그 NEXT_PUBLIC_PARTS_LAB=1). 백엔드 없이 page.route 합성 자료로
// 받은 요청 읽기 → 수락(그 자리에서, 다시 읽어 「수락함」) → 서버 거절(사유 시트가 열린 채로)을 실제 경로로 본다.
// 두 프로필(@both): 누를 영역 44 · 네이티브 disabled 0 · title 0 · 가로 넘침 0. 모바일(@mobile-only): 사유는 아래 시트.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, MOBILE_ONLY, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const LAB = '/parts-lab/requests';
const API = '/api/game/api';
const phase = (year: number, month: number, p: number) => ({ year, month, phase: p });

interface Server {
    dispatchStatus: 'PENDING' | 'ACCEPTED' | 'REFUSED';
    /** 다음 응답을 서버가 거절한다(입장 거절 200 BLOCKED + code). */
    rejectNext: { code: string; reason: string } | null;
    replies: unknown[];
}

async function serve(page: Page, server: Server) {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname.startsWith(API), async (route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.slice(API.length);
        if (path === '/commands/dispatches') {
            return json(route, 200, {
                result: true,
                dispatches: [{
                    dispatchId: 'D-1', issuerId: 7, targetId: 1, countyId: 30, issuerLabel: '순욱', targetLabel: '허저', countyLabel: '허현',
                    issuedAt: phase(200, 3, 1), dueAt: phase(200, 3, 3), status: server.dispatchStatus, currentFailure: null,
                }],
            });
        }
        if (path === '/commands/political-consent-options') {
            return json(route, 200, [{ inputId: 'action.oath', issuerGeneralId: 9, issuerName: '관우', available: true, accepted: null }]);
        }
        if (path === '/commands/court/dispatchReply' && route.request().method() === 'POST') {
            server.replies.push(route.request().postDataJSON());
            if (server.rejectNext) {
                const { code, reason } = server.rejectNext;
                server.rejectNext = null;
                return json(route, 200, { status: 'BLOCKED', code, reason });
            }
            return json(route, 202, { status: 'AVAILABLE', requestId: 'r-1' });
        }
        if (path === '/command/result/r-1') {
            server.dispatchStatus = 'ACCEPTED';
            return json(route, 200, { status: 'RESOLVED', requestId: 'r-1', ok: true, type: 'executionApplied', result: {} });
        }
        return json(route, 404, { error: `합성 자료 없음: ${path}` });
    });
}

async function open(page: Page, server: Server) {
    await serve(page, server);
    await page.goto(LAB, { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: '요청 카드 미리보기' })).toBeVisible({ timeout: 60_000 });
    await expect(page.locator('main[data-hydrated="true"]')).toBeVisible();
    await expect(page.getByRole('article', { name: '발령 — 순욱' }).first()).toBeVisible();
}

const fresh = (): Server => ({ dispatchStatus: 'PENDING', rejectNext: null, replies: [] });

test.describe('요청 카드', () => {
    test('규칙: 누를 영역 44 · disabled 0 · title 0 · 가로 넘침 0', { tag: [BOTH] }, async ({ page }) => {
        await open(page, fresh());
        expect(await smallTouchTargets(page, 'main')).toEqual([]);
        expect(await page.locator('main [disabled]').count()).toBe(0);
        expect(await titleOnlyInfo(page, 'main')).toEqual([]);
        await expectNoHorizontalOverflow(page);
    });

    test('받은 요청: 서버 기한 · 거절 결과를 보이고, 수락하면 그 자리에서 「수락함」이 된다', { tag: [BOTH] }, async ({ page }) => {
        const server = fresh();
        await open(page, server);
        const live = page.getByTestId('lab-live');
        const card = live.getByRole('article', { name: '발령 — 순욱' });
        await expect(card).toContainText('200년 3월 하순까지 · 넘기면 수락');
        await expect(card).toContainText('거절하면 — 충성과 명망이 줄어듭니다');
        await expect(live.getByRole('article', { name: '결의 동의 — 관우' })).toBeVisible();
        await press(card.getByRole('button', { name: '수락' }), test.info());
        await expect(card).toContainText('수락함');
        await expect(card.getByRole('button', { name: '수락' })).toHaveCount(0);
        expect(server.replies).toEqual([{ dispatchId: 'D-1', accept: true }]);
    });

    test('서버가 응답을 거절하면 누른 쪽 사유 시트가 열린 채로 뜨고, 카드는 그대로 남는다', { tag: [BOTH] }, async ({ page }) => {
        const server = fresh();
        server.rejectNext = { code: 'DISPATCH_EXPIRED', reason: '기한이 지났습니다' };
        await open(page, server);
        const card = page.getByTestId('lab-live').getByRole('article', { name: '발령 — 순욱' });
        await press(card.getByRole('button', { name: '거절' }), test.info());
        const sheet = page.getByRole('dialog', { name: /지금은 응답할 수 없습니다/ });
        await expect(sheet).toBeVisible();
        await expect(sheet).toContainText('기한이 지났습니다');
        // 기한이 지나면 수락도 거절도 못 한다 — 두 단추가 같은 서버 사유로 막힌다.
        await expect(card.locator('[data-reason-code="DISPATCH_EXPIRED"]')).toHaveCount(2);
        await expect(card.getByRole('button', { name: '거절' })).toHaveAttribute('data-input-status', 'BLOCKED');
        await expect(card.getByRole('button', { name: '수락' })).toHaveAttribute('data-input-status', 'BLOCKED');
        // 단추 옆 사유 꼬리표가 반쪽 폭에서 잘리지 않는다(보이는 사유 = 서버 사유 전부).
        const clipped = await card.locator('.os-ia__why').evaluateAll((els) => els.filter((el) => el.scrollWidth > el.clientWidth + 1).length);
        expect(clipped).toBe(0);
        expect(server.replies).toEqual([{ dispatchId: 'D-1', accept: false }]);
    });

    test('좁은 폭은 거절 결과 줄을 빼고, 입력 없는 요청은 단추 대신 안내 한 줄', { tag: [BOTH] }, async ({ page }) => {
        await open(page, fresh());
        const compact = page.getByTestId('lab-compact');
        const done = compact.getByRole('article', { name: '발령 — 순욱' });
        await expect(done).toContainText('수락함');
        await expect(done).not.toContainText('거절하면');
        const offer = compact.getByRole('article', { name: '임명 제안 — 조조' });
        await expect(offer).toContainText('응답 입력은 서버 준비 중입니다');
        await expect(offer.getByRole('button')).toHaveCount(0);
    });

    test('모바일: 거절 사유는 화면 아래 시트로 열린다', { tag: [MOBILE_ONLY] }, async ({ page }) => {
        const server = fresh();
        server.rejectNext = { code: 'DISPATCH_EXPIRED', reason: '기한이 지났습니다' };
        await open(page, server);
        await press(page.getByTestId('lab-live').getByRole('article', { name: '발령 — 순욱' }).getByRole('button', { name: '거절' }), test.info());
        const sheet = page.getByRole('dialog', { name: /지금은 응답할 수 없습니다/ });
        await expect(sheet).toBeVisible();
        const box = (await sheet.boundingBox())!;
        const vp = page.viewportSize()!;
        expect(Math.round(box.y + box.height)).toBe(vp.height);
        expect(Math.round(box.width)).toBe(vp.width);
    });
});
