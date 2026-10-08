// 새 장수 만들기(P-E02) — 서버 #1137 생성 옵션 고정 자료로 /game/create 를 돈다(@both).
//  「그려짐」: 역할 둘(예비 주공은 서버 대기) · 본관 후보(불가 사유) · 능력 합 · 누를 영역 44 · title 0 · 네이티브 disabled 0 · 가로 넘침 0.
//  「조작됨」: 본관 · 이름 · 주의 · 개성 → 「만들고 섬길 주공 고르기」 → 202 → CREATED → 세션에 장수가 보인 뒤 출사(/game/join) 화면까지.
//  서버 경로가 없는 경우(생성 대기)는 entry-enlist.spec 이 본다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
import { frontInfo } from '../support/campaignFixtures';
import { serveHelpApi } from './help-api';
import { ACCEPTED, OPTIONS, RESULT_CREATED } from '../../__tests__/fixtures/creation';

async function serve(page: Page) {
    const info = frontInfo();
    info.general.nationId = 0;
    // 결과가 CREATED 가 되기 전엔 장수 없음, 뒤엔 재야 장수 — 출사(join)는 세션에 장수가 보여야 입구로 되돌리지 않는다(#1329 리뷰)
    let made = false;
    const posts: unknown[] = [];
    await page.route('**/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'entry-qa', nickname: '장수', role: 'USER' } } }));
    await page.route('**/api/game/**', async (r) => {
        const url = new URL(r.request().url());
        const path = url.pathname.replace(/^\/api\/game/, '');
        if (path === '/api/front-info') return r.fulfill({ json: { ...info, general: { ...info.general, hasGeneral: made } } });
        if (path === '/api/generals/creation/options') return r.fulfill({ json: { ...OPTIONS, playerCap: { used: 0, max: 50 } } });
        // 출사 화면(P-E04)이 그려질 만큼만 — 후보 하나 · 예약 칸 없음
        if (path === '/api/commands/enlistment-options') {
            return r.fulfill({ json: { result: true, inputId: 'action.enlist', maxReservedTurns: 12,
                options: [{ mode: 'NATION', targetId: 2, label: '조조', availability: { status: 'AVAILABLE' } }] } });
        }
        if (path === '/api/reserved-commands') return r.fulfill({ json: { result: true, generalId: info.general.generalId, slots: [] } });
        if (path === '/api/generals/creation' && r.request().method() === 'POST') {
            posts.push(r.request().postDataJSON());
            return r.fulfill({ status: 202, json: ACCEPTED });
        }
        if (path === '/api/generals/creation/req-1') { made = true; return r.fulfill({ json: RESULT_CREATED }); }
        return r.fulfill({ status: 503, json: {} });
    });
    await serveHelpApi(page, { onlyHelp: true });
    return { posts };
}

async function open(page: Page) {
    const api = await serve(page);
    await page.goto('/game', { waitUntil: 'domcontentloaded' });
    const entry = page.getByTestId('game-entry-screen');
    await expect(entry).toBeVisible({ timeout: 60_000 });
    await expect(entry.getByText(/사람 장수 자리 50\/50 남음/)).toBeVisible();
    await expect(entry.getByText('내 장수를 만들 수 있습니다.')).toBeVisible();
    await expect(entry.getByText('역사 인물로 시작할 수 있습니다.')).toBeVisible();
    await expect(entry.getByText('장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중')).toHaveCount(0);
    await expect(entry.getByRole('link', { name: '역사 인물 화면 보기' })).toHaveAttribute('href', /\/game(?:\/[^/]+)?\/create\/historical$/);
    expect(api.posts).toHaveLength(0);
    await page.evaluate(() => document.fonts.ready);
    await press(entry.getByRole('link', { name: '생성 화면 보기' }), test.info());
    await expect(page).toHaveURL(/\/game(?:\/[^/]+)?\/create$/);
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
        // URL 만이 아니라 출사 화면이 그려지고 입구로 되돌아가지 않는다
        await expect(page.getByTestId('enlist-screen')).toBeVisible({ timeout: 15_000 });
        await expect(page.getByRole('option', { name: '조조', exact: true })).toBeVisible();
        await expect(page).toHaveURL(/\/game\/join$/);
        expect(api.posts).toHaveLength(1);
        expect((api.posts[0] as { choice: { kind: string; nativeCountyId: number } }).choice).toMatchObject({ kind: 'CUSTOM', name: '하후연', nativeCountyId: 11, role: 'RETAINER' });
    });
});
