// 조정 묶음 K8 화면(관직 · 봉신 · 외교의 주변 세계 · 참모 제안 · 황실 · 세력)을 하위 화면 탭만 눌러(모바일은 터치) 옮겨 다닌다.
// 화면별 spec 은 주소로 바로 들어가 그 화면만 본다. 여기서는 모바일도 같은 게임이라는 규칙(2026-09-26)을 한 흐름으로 본다.
//  - 조작: 하위 화면 탭 · 화면 안 탭을 누르기만 해서 닿는다(page.goto 는 첫 화면 한 번뿐).
//  - 화면마다: 누를 영역 44 · title 전용 정보 0 · 가로 넘침 0.
//  - 보이는 「서버 대기」 칸은 문구가 보이고 기다리는 계약판 행(data-server-wait)에 들어 있다.
//  - 숨은 서버 대기는 모바일에서 일부러 숨긴 칸(보드 MOfficesSubordinates · MProposals의 「고른 …」)뿐이다. 데스크톱은 0이다.
import { expect, test, type Locator, type Page, type Route, type TestInfo } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
const MAIN = 'main[aria-label="게임 콘텐츠"]';

async function serve(page: Page) {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const path = new URL(route.request().url()).pathname.slice(API.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 5, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/imperial/presence') {
            return json(route, 200, {
                status: 'READY',
                badges: [{ lineCode: 'han', lineName: '한', emperorGeneralId: 101, emperorName: '유협', emperorNodeKind: 'LAND_PROVINCE', emperorNodeId: '70930', emperorCityId: 12, courtCityId: 11 }],
            });
        }
        if (path === '/map/preview') {
            return json(route, 200, { cities: [{ id: 11, name: '허', displayName: '영천군 허현', level: 1, nationId: 1, x: 0, y: 0 }, { id: 12, name: '낙양', displayName: '하남윤 낙양현', level: 1, nationId: 0, x: 0, y: 0 }], nations: [] });
        }
        if (path === '/nation/summary') {
            return json(route, 200, {
                status: 'READY', nation: { id: 1, name: '조조', color: '#4f7fbf' }, lord: { generalId: 1, name: '조조', portrait: { picture: null, imageServer: 0 } },
                capitalCityId: 11, countyCount: 1, retinueCount: 12, stockTotal: { money: 12000, grain: 34000, iron: 10, timber: 20, horses: 30 },
                population: 123456, troops: { city: 5000, bugok: 1200 },
            });
        }
        if (path === '/counties') {
            return json(route, 200, {
                status: 'READY', scope: 'NATION', commandery: null, period: 'GAME_MONTH', basis: 'CURRENT_STATE_FORECAST', stamp: null,
                counties: [{ cityId: 11, name: '허현', commanderyId: 'c1', visibility: 'FULL', income: { money: 500, grain: 1500 } }],
            });
        }
        if (path === '/diplomacy/conflict') {
            return json(route, 200, {
                result: true, conflict: [], myNationID: 1,
                nations: [
                    { nation: 1, name: '조조', color: '#4f7fbf', type: '', level: 1, capital: 0, gennum: 1, cities: ['허현'], power: 0 },
                    { nation: 2, name: '원소', color: '#b04a3c', type: '', level: 1, capital: 0, gennum: 1, cities: ['업현'], power: 0 },
                ],
                diplomacyList: { 1: { 2: 2 } },
            });
        }
        return json(route, 503, {});
    });
}

/** 하위 화면 탭을 눌러 그 화면으로 간다 — 주소 · 제목 · 지금 화면 표시가 바뀐다. */
async function go(page: Page, testInfo: TestInfo, label: string, slug: string) {
    const nav = page.getByRole('navigation', { name: '하위 화면' });
    await press(nav.getByRole('link', { name: label }), testInfo);
    await expect(page).toHaveURL(new RegExp(`/game/court/${slug}$`));
    await expect(page.getByRole('heading', { level: 2, name: label })).toBeVisible();
    await expect(nav.getByRole('link', { name: label })).toHaveAttribute('aria-current', 'page');
}

/** 화면 안 탭을 눌러 고른다. */
async function pick(root: Locator, testInfo: TestInfo, tab: string) {
    await press(root.getByRole('tab', { name: tab }), testInfo);
    await expect(root.getByRole('tab', { name: tab })).toHaveAttribute('aria-selected', 'true');
}

/**
 * 지금 보이는 모습을 잰다. root 안의 서버 대기만 본다(외교의 세력 외교 · 서신은 K6 몫이라 root 를 주변 세계로 좁힌다).
 * hiddenOnMobile = 모바일에서 보드대로 숨긴 서버 대기 칸 수(데스크톱은 늘 0).
 */
async function check(page: Page, testInfo: TestInfo, root: Locator, step: string, hiddenOnMobile = 0) {
    const waits = await root.evaluate((node) => {
        const shown = (el: Element) => (el as HTMLElement).getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';
        const statuses = Array.from(node.querySelectorAll('.os-status--waiting'));
        return {
            unmarked: statuses.filter((el) => shown(el) && !el.closest('[data-server-wait]')).map((el) => (el.textContent ?? '').trim().slice(0, 24)),
            blank: statuses.filter((el) => shown(el) && (el as HTMLElement).innerText.trim() === '').length,
            shown: statuses.filter(shown).length,
            hidden: Array.from(node.querySelectorAll('[data-server-wait]')).filter((el) => !shown(el) && !el.closest('[data-server-wait]:not(:scope)')).length,
        };
    });
    expect(waits.unmarked, `${step}: 보이는 서버 대기는 계약판 행(data-server-wait) 안에 있다`).toEqual([]);
    expect(waits.blank, `${step}: 서버 대기 문구가 보인다`).toBe(0);
    expect(waits.shown, `${step}: 서버 대기 칸이 하나 이상 보인다`).toBeGreaterThan(0);
    expect(waits.hidden, `${step}: 숨은 서버 대기`).toBe(isMobile(testInfo) ? hiddenOnMobile : 0);
    expect(await smallTouchTargets(page, MAIN), `${step}: 누를 영역 44`).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN), `${step}: 호버(title) 전용 정보`).toEqual([]);
    await expectNoHorizontalOverflow(page);
}

test('조정 묶음 K8 화면을 하위 화면 탭으로만 옮겨 다닌다 — 화면 · 탭마다 서버 대기 문구 · 누를 영역 44 · title 0 · 넘침 0', { tag: [BOTH] }, async ({ page }, testInfo) => {
    test.setTimeout(180_000);
    await serve(page);
    await page.goto('/game/court/offices', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '관직 · 봉신' })).toBeVisible({ timeout: 60_000 });
    const main = page.locator(MAIN);

    await check(page, testInfo, main, '관직 · 봉신 › 지방 관직');
    for (const [tab, hidden] of [['내 속관', 1], ['추천 · 자칭', 0], ['중앙 관직', 0], ['봉신', 0], ['지방 관직', 0]] as const) {
        await pick(main, testInfo, tab);
        await check(page, testInfo, main, `관직 · 봉신 › ${tab}`, hidden);
    }

    await go(page, testInfo, '외교', 'diplomacy');
    const diplomacy = page.getByRole('region', { name: '외교' });
    await pick(diplomacy, testInfo, '주변 세계');
    await check(page, testInfo, diplomacy, '외교 › 주변 세계');

    await go(page, testInfo, '참모 제안', 'proposals');
    await check(page, testInfo, main, '참모 제안', 1);

    await go(page, testInfo, '황실', 'imperial');
    await expect(page.getByRole('region', { name: '황통 — 한' })).toContainText('유협');
    await check(page, testInfo, main, '황실');

    await go(page, testInfo, '세력', 'realm');
    for (const tab of ['정체성', '제도', '편제 전통']) {
        await pick(main, testInfo, tab);
        await check(page, testInfo, main, `세력 › ${tab}`);
    }

    await go(page, testInfo, '관직 · 봉신', 'offices');
    await check(page, testInfo, main, '관직 · 봉신(돌아옴)');
});
