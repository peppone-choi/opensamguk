// 다시 보기(P-H03) — 계약판 K5-09(C2)가 없어 /game/records/replay/[id] 는 서버 대기(K5-09)로만 그린다(@both).
//  「그려짐」: 대기 안내 · 전투 · 기록 링크 · 누를 영역 44 · title 0 · 네이티브 disabled 0 · 가로 넘침 0, 틀린 번호는 따로.
//  「조작됨」(부품 시험 /parts-lab/replay, NEXT_PUBLIC_PARTS_LAB=1): 원작 전장 판이 그려지고 확대 · 축소 · 판 전체,
//   시간 막대 재생 · 멈춤 · 다음 사건 · 빠르기가 시계를 움직인다. 합성 사건만 쓴다(서버 값 아님).
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
import { frontInfo } from '../support/campaignFixtures';

async function serve(page: Page) {
    const seen: string[] = [];
    await page.route('**/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route('**/api/game/**', async (r) => {
        const path = new URL(r.request().url()).pathname.replace(/^\/api\/game/, '');
        seen.push(path);
        if (path === '/api/front-info') return r.fulfill({ json: frontInfo() });
        return r.fulfill({ status: 503, json: {} });
    });
    return seen;
}

async function rules(page: Page, root: string) {
    expect(await smallTouchTargets(page, root)).toEqual([]);
    expect(await titleOnlyInfo(page, root)).toEqual([]);
    expect(await page.locator(`${root} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

test.describe('다시 보기', () => {
    test('서버 대기: 결과 반영 뒤 공개 안내 · 전투 · 기록 링크, 리플레이 읽기를 부르지 않는다', { tag: BOTH }, async ({ page }) => {
        const seen = await serve(page);
        await page.goto('/game/records/replay/42', { waitUntil: 'domcontentloaded' });
        const wait = page.locator('[data-server-wait="K5-09"]');
        await expect(wait).toBeVisible({ timeout: 60_000 });
        await expect(wait).toContainText('전투가 끝나고 결과가 반영되면 다시 볼 수 있습니다');
        await expect(page.getByRole('link', { name: '군단 › 전투로' })).toHaveAttribute('href', /\/corps\/battle$/);
        await expect(page.getByRole('link', { name: '기록으로' })).toHaveAttribute('href', /\/records$/);
        await rules(page, MAIN);
        expect(seen.filter((p) => p.includes('/battles'))).toEqual([]);
    });

    test('틀린 번호: 서버 대기가 아니라 번호 안내 + 기록으로', { tag: BOTH }, async ({ page }) => {
        await serve(page);
        await page.goto('/game/records/replay/abc', { waitUntil: 'domcontentloaded' });
        await expect(page.getByText('다시 볼 전투 번호가 올바르지 않습니다')).toBeVisible({ timeout: 60_000 });
        await expect(page.locator('[data-server-wait]')).toHaveCount(0);
        await rules(page, MAIN);
    });

    test('부품 시험: 전장 판 확대 · 축소 · 판 전체, 시간 막대 재생 · 멈춤 · 다음 사건 · 빠르기', { tag: BOTH }, async ({ page }, info) => {
        await page.goto('/parts-lab/replay?board=4', { waitUntil: 'domcontentloaded' });
        const board = page.locator('[data-board-status]');
        await expect(board).toHaveAttribute('data-board-status', 'ready', { timeout: 60_000 });
        const canvas = page.getByTestId('replay-board');
        await expect(canvas).toHaveAttribute('data-scale', /\d/);
        const fit = Number(await canvas.getAttribute('data-scale'));
        await press(page.getByRole('button', { name: '확대' }), info);
        await expect.poll(async () => Number(await canvas.getAttribute('data-scale'))).toBeGreaterThan(fit);
        await press(page.getByRole('button', { name: '판 전체' }), info);
        await expect.poll(async () => Number(await canvas.getAttribute('data-scale'))).toBeCloseTo(fit, 3);

        const clock = page.getByTestId('replay-lab-clock');
        await expect(clock).toHaveText('0 paused 1');
        // 다음 사건 — 멈춘 채로 그 사건 자리(0:42)로 간다.
        await press(page.getByRole('button', { name: '다음 사건' }), info);
        await expect(clock).toHaveText('42000 paused 1');
        await expect(page.getByRole('group', { name: '시간 막대' })).toContainText('선봉끼리 부딪혔다');
        // 빠르기 2× 로 재생하면 시계가 나아가고, 멈추면 선다.
        await press(page.getByRole('radiogroup', { name: '빠르기' }).getByRole('radio', { name: '2×' }), info);
        await press(page.getByRole('button', { name: '재생' }), info);
        await expect.poll(async () => Number((await clock.textContent())?.split(' ')[0])).toBeGreaterThan(43_000);
        await press(page.getByRole('button', { name: '멈춤' }), info);
        const stopped = await clock.textContent();
        expect(stopped).toMatch(/ paused 2$/);
        await page.evaluate(() => new Promise((resolve) => setTimeout(resolve, 300)));
        await expect(clock).toHaveText(stopped ?? '');
        expect(await smallTouchTargets(page, 'main')).toEqual([]);
        await expectNoHorizontalOverflow(page);
    });
});
