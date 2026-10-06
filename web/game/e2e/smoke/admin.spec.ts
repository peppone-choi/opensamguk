// 게임 관리(P-A03) — /game/admin 을 백엔드 없이 합성 자료로 돈다(로그인 운영자 · 세력 개요 · 인물 목록 두 쪽 · 서버 상태 합성,
// 나머지 게임 읽기는 503). 두 프로필(@both):
//  「그려짐」 — 셸 머리 탭 다섯 · 세력 개요 표(창고 합 · 병력 합 · 못 읽은 값 「—」) · 누를 영역 44 · title 0 · 네이티브 disabled 0 · 가로 넘침 0.
//  「조작됨」 — 열 머리 정렬 · 인물 조치(사람 고르기 · 조치는 잠김 + 사유, 모바일은 하단 시트) · 서버 상태 바꾸기(확인 → POST → 접수 줄).
//  「권한 없음」 — 운영자가 아니면 「관리자 권한이 필요합니다.」, 탭 없음.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
const nation = (id: number, name: string, over: Record<string, unknown> = {}) => ({
    status: 'READY', nation: { id, name, color: '#4f7fbf' }, lord: null, capitalCityId: 11, countyCount: 3, retinueCount: 12,
    stockTotal: { money: 12000, grain: 34000, iron: 0, timber: 0, horses: 0 }, population: 123456, troops: { city: 5000, bugok: 1200 },
    ...over,
});
const NATIONS = {
    status: 'PARTIAL',
    nations: [nation(1, '조조'), nation(2, '원소', { status: 'PARTIAL', countyCount: 9, stockTotal: null, troops: { city: 800, bugok: null } }), nation(3, '유비', { countyCount: 1 })],
};
const person = (generalId: number, name: string, nationName: string | null) => ({
    generalId, name, portrait: { picture: null, imageServer: 0 },
    affiliation: nationName ? { nationId: 1, name: nationName, color: '#4f7fbf' } : null,
    role: null, lordGeneralId: null, stats: { leadership: 90, strength: 95, intel: 40, politics: 30, charm: 60 },
    aptitudes: null, locationCityId: null, bonds: null,
});
const SETTINGS = {
    msg: '', logWritable: false, scenarioCode: 's1', scenarioText: '군웅할거', year: 200, month: 3, turnPhaseText: '중순', status: 'OPEN',
    starttime: null, startyear: 190, maxgeneral: null, maxnation: null, turntime: '2026-10-04 05:00:00', turnterm: 60, turnOptions: [], blockedWrites: [],
};

async function serve(page: Page, role = 'ADMIN') {
    const posts: unknown[] = [];
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'op', nickname: 'op', role } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.slice(API.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: false },
                nation: null, city: null, recentRecord: {},
            });
        }
        if (path === '/admin/nations') return json(route, role === 'ADMIN' ? 200 : 403, role === 'ADMIN' ? NATIONS : {});
        if (path === '/admin/people') {
            const cursor = url.searchParams.get('cursor');
            return json(route, 200, cursor === null
                ? { status: 'READY', people: [person(10, '허저', '조조'), person(11, '이전', '조조')], nextCursor: 'c2' }
                : { status: 'READY', people: [person(12, '여포', null)], nextCursor: null });
        }
        if (path === '/admin/game-settings') return json(route, 200, SETTINGS);
        if (path === '/admin/server-status' && route.request().method() === 'POST') {
            posts.push(route.request().postDataJSON());
            return json(route, 202, { result: true, status: 'CLOSED' });
        }
        return json(route, 503, {});
    });
    return posts;
}

async function open(page: Page, query = '', role = 'ADMIN') {
    const posts = await serve(page, role);
    await page.goto(`/game/admin${query}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: /^게임 관리/ })).toBeVisible({ timeout: 60_000 });
    return posts;
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

async function rules(page: Page) {
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

test.describe('게임 관리', () => {
    test('그려짐: 머리 탭 다섯 · 세력 개요 표(창고 합 · 병력 합 · 못 읽은 값 —)', { tag: [BOTH] }, async ({ page }) => {
        await open(page);
        const nav = page.getByRole('navigation', { name: '하위 화면' });
        await expect(nav.getByRole('link')).toHaveText(['세력 개요', '인물 조치', '인물 기록', '외교 관계', '서버 상태']);
        await expect(nav.getByRole('link', { name: '세력 개요' })).toHaveAttribute('aria-current', 'page');
        const table = page.getByRole('table');
        await expect(table.getByRole('row', { name: /조조/ }).getByRole('cell')).toHaveText(['3', '12', '12,000', '34,000', '6,200', '123,456']);
        await expect(table.getByRole('row', { name: /원소/ }).getByRole('cell')).toHaveText(['9', '12', '—', '—', '—', '123,456']);
        await expect(page.getByRole('note')).toBeVisible();
        await rules(page);
    });

    test('조작됨: 열 머리 정렬 · 인물 조치(조치 잠김 + 사유) · 서버 상태 바꾸기', { tag: [BOTH] }, async ({ page }, testInfo) => {
        const posts = await open(page);
        const names = page.getByRole('table').getByRole('rowheader');
        await press(page.getByRole('button', { name: /^현/ }), testInfo);
        await expect(names).toHaveText(['원소', '조조', '유비']);

        await press(page.getByRole('navigation', { name: '하위 화면' }).getByRole('link', { name: '인물 조치' }), testInfo);
        await expect(page).toHaveURL(/tab=people/);
        const list = page.getByRole('listbox', { name: '대상 인물' });
        await expect(list.getByText('여포')).toBeVisible();
        await press(list.getByText('허저'), testInfo);
        if (isMobile(testInfo)) {
            await press(page.getByRole('button', { name: '고른 1명 조치' }), testInfo);
            const sheet = page.getByRole('dialog', { name: '허저 조치' });
            await expect(sheet.getByRole('button', { name: '턴 막기' })).toHaveAttribute('aria-disabled', 'true');
            await expect(sheet.getByRole('table', { name: '고른 인물' })).toContainText('조조 소속');
            await press(sheet.getByRole('button', { name: '닫기' }), testInfo);
        } else {
            await expect(page.getByRole('button', { name: '턴 막기' })).toHaveAttribute('aria-disabled', 'true');
            await expect(page.getByRole('table', { name: '고른 인물' })).toContainText('조조 소속');
            await rules(page);
        }

        await press(page.getByRole('navigation', { name: '하위 화면' }).getByRole('link', { name: '서버 상태' }), testInfo);
        const panel = page.getByRole('region', { name: '서버 상태' });
        await expect(panel).toContainText('열림');
        await expect(panel).toContainText('200년 3월 중순 · 군웅할거');
        await rules(page);
        await press(page.getByRole('radio', { name: '닫힘(점검)' }), testInfo);
        await press(page.getByRole('button', { name: '상태 바꾸기' }), testInfo);
        await press(page.getByRole('dialog', { name: '서버 상태 바꾸기' }).getByRole('button', { name: '바꾸기' }), testInfo);
        await expect(page.getByRole('status').filter({ hasText: '바꾸기를 접수했습니다' })).toBeVisible();
        expect(posts).toEqual([{ status: 'CLOSED' }]);
    });

    test('권한 없음: 운영자가 아니면 막고 탭이 없다', { tag: [BOTH] }, async ({ page }) => {
        await open(page, '', 'USER');
        await expect(page.getByText('관리자 권한이 필요합니다.')).toBeVisible();
        await expect(page.getByRole('navigation', { name: '하위 화면' })).toHaveCount(0);
        await rules(page);
    });
});
