// 역사 인물 고르기(P-E03) — 서버 #1137 계약 고정 자료로 /game/create/historical 을 돈다(@both).
//  「그려짐」: 카드(이름 · 소속 · 다섯 능력) · 잠긴 카드 · 계약에 없는 칸 알림 · 누를 영역 44 · title 0 · 네이티브 disabled 0 · 가로 넘침 0.
//  「조작됨」: 거르기(재야 = nation=0) · 더 보기 · 고르기 → 접수 202 → 결과 CREATED → 입구(/game)로.
//  서버 경로가 없는 경우(생성 대기)는 entry-enlist.spec 이 본다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
import { frontInfo } from '../support/campaignFixtures';
import { serveHelpApi } from './help-api';
import { ACCEPTED, HISTORICAL_PAGE_1, HISTORICAL_PAGE_2, MAP_NATIONS, RESULT_CREATED, RESULT_PENDING } from '../../__tests__/fixtures/creation';

async function serve(page: Page) {
    const info = frontInfo();
    info.general.hasGeneral = false;
    const seen: URL[] = [];
    const posts: unknown[] = [];
    let resultReads = 0;
    await page.route('**/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'entry-qa', nickname: '장수', role: 'USER' } } }));
    await page.route('**/api/game/**', async (r) => {
        const url = new URL(r.request().url());
        const path = url.pathname.replace(/^\/api\/game/, '');
        if (path === '/api/front-info') return r.fulfill({ json: info });
        if (path === '/api/map/preview') return r.fulfill({ json: { nations: MAP_NATIONS, cities: [], year: 200, month: 3 } });
        if (path === '/api/generals/creation/historical') {
            seen.push(url);
            return r.fulfill({ json: url.searchParams.get('cursor') === '103' ? HISTORICAL_PAGE_2 : HISTORICAL_PAGE_1 });
        }
        if (path === '/api/generals/creation' && r.request().method() === 'POST') {
            posts.push(r.request().postDataJSON());
            return r.fulfill({ status: 202, json: ACCEPTED });
        }
        if (path === '/api/generals/creation/req-1') {
            resultReads += 1;
            return r.fulfill({ json: resultReads === 1 ? RESULT_PENDING : RESULT_CREATED });
        }
        return r.fulfill({ status: 503, json: {} });
    });
    await serveHelpApi(page, { onlyHelp: true });
    return { seen, posts };
}

async function open(page: Page) {
    const api = await serve(page);
    await page.goto('/game/create/historical', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('listbox', { name: '역사 인물' })).toBeVisible({ timeout: 60_000 });
    return api;
}

const MAIN = 'main';

async function rules(page: Page) {
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

test.describe('역사 인물 고르기', () => {
    test('그려짐: 카드 · 잠긴 카드 · 계약에 없는 칸 알림', { tag: BOTH }, async ({ page }) => {
        await open(page);
        const options = page.getByRole('listbox', { name: '역사 인물' }).getByRole('option');
        await expect(options).toHaveCount(3);
        await expect(options.nth(0)).toContainText('하후돈');
        await expect(options.nth(0)).toContainText('조조 소속');
        await expect(options.nth(0)).toContainText('통 90 · 무 89 · 지 58 · 정 70 · 매 80');
        await expect(options.nth(1)).toHaveAttribute('aria-disabled', 'true');
        await expect(options.nth(2)).toContainText('재야');
        await expect(page.getByText('역할 · 누구의 부 · 본관은 서버가 아직 주지 않아 카드에 없습니다.')).toBeVisible();
        await expect(page.getByRole('navigation', { name: '게임 메뉴' })).toHaveCount(0);
        await rules(page);
    });

    test('조작됨: 거르기 · 더 보기 · 고르기 → 접수 → CREATED 면 입구로', { tag: BOTH }, async ({ page }, info) => {
        const api = await open(page);
        if (isMobile(info)) {
            await press(page.getByRole('button', { name: '거르기' }), info);
            await press(page.getByRole('dialog', { name: '거르기' }).getByRole('radio', { name: '재야' }), info);
            await expect.poll(() => api.seen.at(-1)?.searchParams.get('nation')).toBe('0');
            await press(page.getByRole('dialog', { name: '거르기' }).getByRole('radio', { name: '조조' }), info);
            await press(page.getByRole('dialog', { name: '거르기' }).getByRole('button', { name: '닫기' }), info);
        } else {
            await press(page.getByRole('radio', { name: '재야' }), info);
            await expect.poll(() => api.seen.at(-1)?.searchParams.get('nation')).toBe('0');
            await press(page.getByRole('radio', { name: '조조' }), info);
        }
        await expect.poll(() => api.seen.at(-1)?.searchParams.get('nation')).toBe('1');
        await press(page.getByRole('button', { name: '더 보기' }), info);
        await expect(page.getByRole('listbox', { name: '역사 인물' }).getByRole('option')).toHaveCount(4);
        await expect(page.getByRole('button', { name: '더 보기' })).toHaveCount(0);

        await press(page.getByRole('listbox', { name: '역사 인물' }).getByRole('option').nth(0), info);
        if (isMobile(info)) await expect(page.getByRole('dialog', { name: '하후돈' })).toBeVisible();
        else await rules(page);
        await press(page.getByRole('button', { name: '이 인물로 시작' }), info);
        await expect(page.getByText('장수를 만드는 중입니다')).toBeVisible();
        await expect(page).toHaveURL(/\/game$/, { timeout: 15_000 });
        expect(api.posts).toHaveLength(1);
        expect((api.posts[0] as { choice: unknown }).choice).toEqual({ kind: 'HISTORICAL', historicalGeneralId: 101 });
    });
});
