// 새 장수 만들기(P-E02) 본관 지도 — 제품 화면 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS=1) 빌드에서만 돈다(*.topdown-screen.spec.ts).
// 합성 bake · 키트(e2e/fixtures/topdown, 원작 그림 없음)를 records.topdown-screen 과 같은 주소로 대 주고, 생성 옵션은 그 bake 칸 근처 현 넷이다.
//  「그려짐」: 천하 전체(주 보기)는 표지 없이 안내 → 주를 고르면 그 후보 가운데 · 군 보기 → 표지 44(가운데 맨 위 요소가 표지).
//  「조작됨」: 표지로 고르면 목록도 고른다 · 못 고르는 표지는 사유 줄. 누를 영역 44 · title 0 · 네이티브 disabled 0 · 가로 넘침 0.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
import { frontInfo } from '../support/campaignFixtures';
import { serveHelpApi } from '../smoke/help-api';
import { OPTIONS } from '../../__tests__/fixtures/creation';

const FIXTURE = join(__dirname, '..', 'fixtures', 'topdown');
const BAKE_ID = 'a'.repeat(64);
const contentType = (path: string) => (path.endsWith('.png') ? 'image/png' : path.endsWith('.json') ? 'application/json' : 'application/octet-stream');

const PREVIEW = {
    mapCode: 'han-world-v3', width: 700, height: 610,
    cities: [{ id: 1, name: '선무', level: 8, nationId: 1, state: 0, supply: true, x: 116, y: 101, isCommanderySeat: true, commanderyName: '하남윤', provinceId: 1 }],
    nations: [{ id: 1, name: '조조', color: '#4f7fbf' }],
    provinceOccupancy: [{ provinceRecordId: 'A', provinceIndex: 0, nationId: 1 }, { provinceRecordId: 'B', provinceIndex: 1, nationId: 0 }],
    topdownBakeId: BAKE_ID,
};
// 사예 셋의 칸 가운데 평균이 (1400.5, 900.5) — 합성 bake 城 1(선무) 자리. 군 보기(4px/칸)에서 표지 44 가 겹치지 않게 15 · 10칸씩 띄운다.
const county = (cityId: number, name: string, provinceName: string, commanderyName: string, col: number, row: number, available = true) => ({
    cityId, name, commanderyId: commanderyName, commanderyName, provinceName, cellCol: col, cellRow: row, available,
    reason: available ? null : 'INVALID_NATIVE_COUNTY',
});
const MAP_OPTIONS = {
    ...OPTIONS,
    nativeCounties: [
        county(1, '선무', '사예', '하남윤', 1400, 900),
        county(2, '낙양', '사예', '하남윤', 1415, 910),
        county(3, '곡성', '사예', '하남윤', 1385, 890, false),
        county(4, '업현', '기주', '위군', 1300, 700),
    ],
};

async function open(page: Page, testInfo: TestInfo) {
    const info = frontInfo();
    info.general.hasGeneral = false;
    await page.route((url) => url.pathname.startsWith('/map/waryong/273d596/'), async (route) => {
        const file = new URL(route.request().url()).pathname.replace('/map/waryong/273d596/', '');
        try {
            await route.fulfill({ status: 200, body: readFileSync(join(FIXTURE, 'kit', file)), contentType: contentType(file) });
        } catch {
            await route.fulfill({ status: 404, body: '' });
        }
    });
    await page.route('**/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'entry-qa', nickname: '장수', role: 'USER' } } }));
    await page.route('**/api/game/**', async (r) => {
        const url = new URL(r.request().url());
        const bake = url.pathname.indexOf(`/api/map/topdown/${BAKE_ID}/`);
        if (bake >= 0) {
            const file = url.pathname.slice(bake + `/api/map/topdown/${BAKE_ID}/`.length);
            try {
                return await r.fulfill({ status: 200, body: readFileSync(join(FIXTURE, 'bake', file)), contentType: contentType(file) });
            } catch {
                return r.fulfill({ status: 404, body: '' });
            }
        }
        const path = url.pathname.replace(/^\/api\/game/, '');
        if (path === '/api/front-info') return r.fulfill({ json: info });
        if (path === '/api/generals/creation/options') return r.fulfill({ json: MAP_OPTIONS });
        if (path === '/api/map/preview') return r.fulfill({ json: PREVIEW });
        return r.fulfill({ status: 503, json: {} });
    });
    await serveHelpApi(page, { onlyHelp: true });
    await page.goto('/game/create', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('listbox', { name: '시작할 역할' })).toBeVisible({ timeout: 60_000 });
    if (isMobile(testInfo)) await press(page.getByRole('button', { name: '다음 — 본관' }), testInfo);
}

async function rules(page: Page) {
    expect(await smallTouchTargets(page, 'main')).toEqual([]);
    expect(await titleOnlyInfo(page, 'main')).toEqual([]);
    expect(await page.locator('main :disabled').count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

test.describe('새 장수 만들기 본관 지도(교체 스위치 빌드)', () => {
    test('주를 고르면 군 보기로 옮겨 표지 — 표지로 고르면 목록도, 못 고르는 표지는 사유', { tag: [BOTH] }, async ({ page }, info) => {
        await open(page, info);
        const map = page.locator('[data-map-renderer="topdown"]');
        await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
        await expect(page.getByText('지도를 확대하거나 주 · 군을 고르면 현 표지가 나옵니다.')).toBeVisible();
        await expect(page.locator('[data-map-targets] [data-target-id]')).toHaveCount(0);

        await page.getByRole('combobox', { name: '주', exact: true }).selectOption('사예');
        await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
        await expect(map).toHaveAttribute('data-map-zoom', '4.000');
        const marker = page.getByRole('button', { name: '선무 — 고를 수 있음' });
        await expect(marker).toBeVisible();
        // 조작됨의 전제: 표지 가운데 맨 위 요소가 표지 단추다(겹친 투명 상자가 먹지 않는다)
        const box = (await marker.boundingBox())!;
        expect(box.width).toBeGreaterThanOrEqual(44);
        expect(await page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.closest('[data-target-id]')?.getAttribute('data-target-id') ?? null,
            { x: box.x + box.width / 2, y: box.y + box.height / 2 })).toBe('1');
        await rules(page);

        await press(marker, info);
        await expect(page.getByRole('button', { name: '선무 — 고른 곳' })).toHaveAttribute('aria-pressed', 'true');
        await expect(page.getByRole('listbox', { name: '본관 현 후보' }).getByRole('option', { name: /선무/ })).toHaveAttribute('aria-selected', 'true');
        await expect(map).toHaveAttribute('data-map-selected', '1');

        await press(page.getByRole('button', { name: '곡성 — 고를 수 없음 — 누르면 이유' }), info);
        await expect(page.getByRole('status').filter({ hasText: '곡성 — 시작할 수 없는 본관입니다.' })).toBeVisible();
        await expect(page.getByRole('button', { name: '선무 — 고른 곳' })).toBeVisible();
        await rules(page);
    });
});
