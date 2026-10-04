// 새 장수 만들기(P-E02) — 서버 #1137 생성 옵션 고정 자료로 /game/create 를 돈다(@both).
//  「그려짐」: 역할 둘(예비 주공은 서버 대기) · 본관 후보(불가 사유) · 능력 합 · 누를 영역 44 · title 0 · 네이티브 disabled 0 · 가로 넘침 0.
//  「조작됨」: 본관 · 이름 · 주의 · 개성 → 「만들고 섬길 주공 고르기」 → 202 → CREATED → 출사(/game/join)로.
//  서버 경로가 없는 경우(생성 대기)는 entry-enlist.spec 이 본다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
import { frontInfo } from '../support/campaignFixtures';
import { serveHelpApi } from './help-api';
import { ACCEPTED, OPTIONS, RESULT_CREATED } from '../../lib/creation-fixtures';

async function serve(page: Page) {
    const info = frontInfo();
    info.general.hasGeneral = false;
    const posts: unknown[] = [];
    await page.route('**/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'entry-qa', nickname: '장수', role: 'USER' } } }));
    await page.route('**/api/game/**', async (r) => {
        const url = new URL(r.request().url());
        const path = url.pathname.replace(/^\/api\/game/, '');
        if (path === '/api/front-info') return r.fulfill({ json: info });
        if (path === '/api/generals/creation/options') return r.fulfill({ json: OPTIONS });
        if (path === '/api/generals/creation' && r.request().method() === 'POST') {
            posts.push(r.request().postDataJSON());
            return r.fulfill({ status: 202, json: ACCEPTED });
        }
        if (path === '/api/generals/creation/req-1') return r.fulfill({ json: RESULT_CREATED });
        return r.fulfill({ status: 503, json: {} });
    });
    await serveHelpApi(page, { onlyHelp: true });
    return { posts };
}

async function open(page: Page) {
    const api = await serve(page);
    await page.goto('/game/create', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('listbox', { name: '시작할 역할' })).toBeVisible({ timeout: 60_000 });
    return api;
}

async function rules(page: Page) {
    expect(await smallTouchTargets(page, 'main')).toEqual([]);
    expect(await titleOnlyInfo(page, 'main')).toEqual([]);
    expect(await page.locator('main :disabled').count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

test.describe('새 장수 만들기', () => {
    test('그려짐: 역할 · 본관 후보 · 능력 합', { tag: BOTH }, async ({ page }, info) => {
        await open(page);
        const roles = page.getByRole('listbox', { name: '시작할 역할' }).getByRole('option');
        await expect(roles.nth(0)).toHaveAttribute('aria-selected', 'true');
        await expect(roles.nth(1)).toHaveAttribute('aria-disabled', 'true');
        await expect(page.getByRole('navigation', { name: '게임 메뉴' })).toHaveCount(0);
        await rules(page);
        if (isMobile(info)) await press(page.getByRole('button', { name: '다음 — 본관' }), info);
        await expect(page.getByRole('listbox', { name: '본관 현 후보' }).getByRole('option')).toHaveCount(4);
        await rules(page);
    });

    test('조작됨: 채우고 만들기 → 202 → CREATED 면 출사로', { tag: BOTH }, async ({ page }, info) => {
        const api = await open(page);
        const mobile = isMobile(info);
        if (mobile) await press(page.getByRole('button', { name: '다음 — 본관' }), info);
        await press(page.getByRole('listbox', { name: '본관 현 후보' }).getByRole('option', { name: /허현/ }), info);
        if (mobile) await press(page.getByRole('button', { name: '다음 — 능력' }), info);
        await page.getByRole('textbox', { name: '이름' }).fill('하후연');
        if (mobile) await press(page.getByRole('button', { name: '다음 — 주의 · 개성' }), info);
        await press(page.getByRole('group', { name: '주의' }).getByRole('button', { name: '왕도' }), info);
        await press(page.getByRole('group', { name: '개성' }).getByRole('button', { name: '규율' }), info);
        if (mobile) await press(page.getByRole('button', { name: '다음 — 확인' }), info);
        await expect(page.getByRole('region', { name: '미리보기' })).toContainText('향당 · 허현');
        await press(page.getByRole('button', { name: '만들고 섬길 주공 고르기' }), info);
        await expect(page).toHaveURL(/\/game\/join$/, { timeout: 15_000 });
        expect(api.posts).toHaveLength(1);
        expect((api.posts[0] as { choice: { kind: string; nativeCountyId: number } }).choice).toMatchObject({ kind: 'CUSTOM', name: '하후연', nativeCountyId: 11 });
    });
});
