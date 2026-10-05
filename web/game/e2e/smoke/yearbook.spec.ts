// 연감(P-H02) — 계약판 K5-08 고정 자료로 /game/records/yearbook 을 돈다(@both).
//  「그려짐」: 판도 표 · 수도 · 큰 사건 · 지도 서버 대기 · 누를 영역 44 · title 0 · 네이티브 disabled 0 · 가로 넘침 0.
//  「조작됨」: 세력 거르기 · 더 보기 · 앞 해. 「서버 대기」: 경로가 없으면 연감을 준비하고 있습니다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
import { frontInfo } from '../support/campaignFixtures';
import { MAP_PREVIEW, YEARBOOK_200, YEARBOOK_200_MORE, YEARS } from '../../__tests__/fixtures/yearbook';

async function serve(page: Page, delivered = true) {
    const seen: URL[] = [];
    await page.route('**/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route('**/api/game/**', async (r) => {
        const url = new URL(r.request().url());
        const path = url.pathname.replace(/^\/api\/game/, '');
        seen.push(url);
        if (path === '/api/front-info') return r.fulfill({ json: frontInfo() });
        if (path === '/api/map/preview') return r.fulfill({ json: MAP_PREVIEW });
        if (path === '/api/yearbook/years') return delivered ? r.fulfill({ json: YEARS }) : r.fulfill({ status: 404, json: {} });
        if (path === '/api/yearbook') {
            const more = url.searchParams.get('cursor') === '902';
            return r.fulfill({ json: more ? YEARBOOK_200_MORE : { ...YEARBOOK_200, year: Number(url.searchParams.get('year')) } });
        }
        return r.fulfill({ status: 503, json: {} });
    });
    return seen;
}

async function rules(page: Page) {
    const MAIN = 'main[aria-label="게임 콘텐츠"]';
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

test.describe('연감', () => {
    test('그려짐 · 조작됨: 판도 · 큰 사건 · 세력 거르기 · 더 보기 · 앞 해', { tag: BOTH }, async ({ page }, info) => {
        const seen = await serve(page);
        await page.goto('/game/records/yearbook', { waitUntil: 'domcontentloaded' });
        const terr = page.getByRole('list', { name: '연말 판도' });
        await expect(terr).toBeVisible({ timeout: 60_000 });
        await expect(terr.getByRole('listitem').first()).toContainText('원소');
        await expect(terr.getByRole('listitem').first()).toContainText('수도 업현');
        await expect(page.getByText('200년 말 판도 지도는 준비 중입니다')).toBeVisible();
        await expect(page.getByRole('list', { name: '그해 큰 사건 목록' })).toContainText('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.');
        await rules(page);
        await press(page.getByRole('radiogroup', { name: '세력으로 거르기' }).getByRole('radio', { name: '유비' }), info);
        await expect(page.getByRole('list', { name: '그해 큰 사건 목록' }).getByRole('listitem')).toHaveCount(1);
        await press(page.getByRole('radiogroup', { name: '세력으로 거르기' }).getByRole('radio', { name: '전체' }), info);
        await press(page.getByRole('button', { name: '더 보기' }), info);
        await expect(page.getByRole('list', { name: '그해 큰 사건 목록' }).getByRole('listitem')).toHaveCount(3);
        await press(page.getByRole('button', { name: '◀ 199년' }), info);
        await expect.poll(() => seen.filter((u) => u.pathname.endsWith('/api/yearbook')).at(-1)?.searchParams.get('year')).toBe('199');
        await rules(page);
    });

    test('서버 대기: 경로가 없으면 연감을 준비하고 있습니다', { tag: BOTH }, async ({ page }) => {
        await serve(page, false);
        await page.goto('/game/records/yearbook', { waitUntil: 'domcontentloaded' });
        await expect(page.getByText('연감을 준비하고 있습니다')).toBeVisible({ timeout: 60_000 });
        await expect(page.getByRole('link', { name: '기록으로' })).toBeVisible();
        await rules(page);
    });
});
