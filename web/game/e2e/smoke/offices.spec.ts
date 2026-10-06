// 관직 · 봉신(P-K03 · P-K04) 골격 — /game/court/offices 를 백엔드 없이 합성 로그인 · front-info 로 돈다(게임 읽기는 503,
// 지방 관직 읽기는 서버에 아직 경로가 없어 404 = 서버 대기 — K8-03 미리 짓기, D124 A).
// 두 프로필(@both): 「그려짐」(하위 탭에서 관직 · 봉신이 지금 화면 · 보드 칸이 서버 대기로 남음 · 누를 영역 44 · title 0 · disabled 0 · 넘침 0 · 배치)과
// 「조작됨」(탭을 눌러 추천 · 자칭 · 중앙 관직 · 봉신으로 바뀜)을 따로 본다. 입력 단추는 원장 행이 없어 0개다.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const API = '/api/game/api';
// 봉신 저장 조건(C5 #1373) 서버 시험의 고정 응답 — 같은 본문으로 화면을 본다.
const VASSALS = JSON.parse(readFileSync(join(__dirname, '..', '..', '..', '..', 'app/game-api/src/test/resources/court/vassal/stored-terms-partial-paid.json'), 'utf-8'));
const PREVIEW = { cities: [{ id: 1, name: '허', displayName: '영천군 허현', level: 1, nationId: 1, x: 0, y: 0 }, { id: 2, name: '양적', displayName: '영천군 양적현', level: 1, nationId: 1, x: 0, y: 0 }], nations: [] };
// 지방 관직 읽기(K8-03, 서버 #1406) 행 모양 — 계약 문서 예시에 ACK 키(reason · 한글 이름)를 얹는다(서버는 아직 행을 내지 않는다).
const LOCAL_OFFICES = (() => {
    const body = JSON.parse(readFileSync(join(__dirname, '..', '..', '..', '..', 'docs/development/fixtures/court-local-offices.json'), 'utf-8'));
    body.reason = null;
    const names = [['경조윤', '장안현'], ['예주', '초현']];
    body.localOffices.forEach((t: Record<string, unknown>, i: number) => {
        t.officeLabel = null;
        t.jurisdictionName = names[i][0];
        t.seatCountyName = names[i][1];
    });
    return body;
})();
const LOCAL_PREVIEW = {
    cities: [
        { id: 100, name: '장안', displayName: '경조윤 장안현', commanderyName: '경조윤', regionName: '사례', level: 1, nationId: 1, x: 0, y: 0 },
        { id: 200, name: '초', displayName: '패국 초현', commanderyName: '패국', regionName: '예주', level: 1, nationId: 1, x: 0, y: 0 },
    ],
    nations: [],
};

async function open(page: Page, nationId = 1, serve: Record<string, unknown> = {}) {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const path = new URL(route.request().url()).pathname.slice(API.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId, officerLevel: 5, permission: 0, showSecret: false },
                nation: nationId ? { id: 1, name: '조조', color: '#4f7fbf' } : null, city: null, recentRecord: {},
            });
        }
        if (path in serve) return json(route, 200, serve[path]);
        if (path === '/court/local-offices') return json(route, 404, { error: { code: 'NOT_FOUND', message: 'not yet' } }); // 경로 아직 없음 = 서버 대기
        return json(route, 503, {});
    });
    await page.goto('/game/court/offices', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { level: 2, name: '관직 · 봉신' })).toBeVisible({ timeout: 60_000 });
}

const MAIN = 'main[aria-label="게임 콘텐츠"]';

async function rules(page: Page) {
    expect(await smallTouchTargets(page, MAIN)).toEqual([]);
    expect(await titleOnlyInfo(page, MAIN)).toEqual([]);
    expect(await page.locator(`${MAIN} :disabled`).count()).toBe(0);
    await expectNoHorizontalOverflow(page);
}

async function serverWaits(page: Page): Promise<string[]> {
    return page.locator(`${MAIN} [role="tabpanel"] [data-server-wait]`).evaluateAll((els) => els.map((el) => el.getAttribute('data-server-wait') ?? ''));
}

test.describe('관직 · 봉신', () => {
    test('그려짐: 지방 관직 — 보드 칸 셋이 서버 대기(K8-03 · K8-03 · K8-02), 입력 단추 0, 데스크톱은 두 열 · 모바일은 한 열', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        await expect(page.getByRole('navigation', { name: '하위 화면' }).getByRole('link', { name: '관직 · 봉신' })).toHaveAttribute('aria-current', 'page');
        expect(await serverWaits(page)).toEqual(['K8-03', 'K8-03', 'K8-02']);
        await expect(page.getByText('현령은 배치 · 발령으로 정합니다')).toBeVisible();
        await expect(page.locator(`${MAIN} [data-input-id]`)).toHaveCount(0);
        await rules(page);
        const [left, right] = await Promise.all([
            page.getByRole('region', { name: '관할과 앉은 사람' }).boundingBox(),
            page.getByRole('region', { name: '고른 관할' }).boundingBox(),
        ]);
        if (isMobile(testInfo)) expect(Math.round(right!.x)).toBe(Math.round(left!.x)); // 한 열
        else {
            expect(Math.round(right!.y)).toBe(Math.round(left!.y)); // 두 열 — 나란히
            expect(Math.round(right!.width)).toBe(440); // 보드 grid2(440, …) — 오른쪽 열이 440, 왼쪽이 나머지
            expect(left!.width).toBeGreaterThan(right!.width);
        }
    });

    test('조작됨: 탭 → 내 속관(K8-05 둘) · 추천 · 자칭(K8-05) · 중앙 관직(K8-05) · 봉신(K8-04 · K8-02 · K8-17)', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        const cases: Array<[string, string[]]> = [['내 속관', ['K8-05', 'K8-05']], ['추천 · 자칭', ['K8-05']], ['중앙 관직', ['K8-05']], ['봉신', ['K8-04', 'K8-02', 'K8-17']], ['지방 관직', ['K8-03', 'K8-03', 'K8-02']]];
        for (const [tab, rows] of cases) {
            await press(page.getByRole('tab', { name: tab }), testInfo);
            await expect(page.getByRole('tab', { name: tab })).toHaveAttribute('aria-selected', 'true');
            await expect(page.getByRole('tabpanel', { name: tab })).toBeVisible();
            expect(await serverWaits(page)).toEqual(rows);
            await rules(page);
        }
    });

    test('봉신: 저장된 계약 조건(C5 #1373) — 목록 줄 · 계약 상세 · 상납 이력, 서버가 아직 판정하지 않는 칸은 서버 대기(K8-04)', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page, 1, { '/court/vassals': VASSALS, '/map/preview': PREVIEW });
        await press(page.getByRole('tab', { name: '봉신' }), testInfo);
        const list = page.getByRole('region', { name: '봉신 계약' });
        const row = list.getByRole('button', { name: /표본 봉신/ });
        await expect(row).toHaveAttribute('aria-pressed', 'true');
        await expect(row).toContainText('봉토 영천군 허현 · 영천군 양적현 · 상납 20%');
        await expect(row).toContainText('이번 달 완납');
        const detail = page.getByRole('region', { name: '표본 봉신 — 봉신 계약' });
        await expect(detail).toContainText('20% — 봉토 수입에서');
        await expect(detail.getByRole('table')).toContainText('200년 1월');
        expect(await serverWaits(page)).toEqual(['K8-04', 'K8-04', 'K8-04', 'K8-04', 'K8-02', 'K8-17']); // 세우기 후보 · 원군 응답 기한 · 맺은 때 · 지금 유효한지 순
        await expect(page.locator(`${MAIN} [data-input-id]`)).toHaveCount(0);
        await rules(page);
        await press(row, testInfo); // 줄을 누르면 그 계약이 고른 계약으로 남는다(터치 포함)
        await expect(row).toHaveAttribute('aria-pressed', 'true');
    });

    test('지방 관직: 계약 고정 응답(K8-03 미리 짓기) — 관할 표 · 고른 관할 · 임명할 수 있는 자리 · 보낸 임명 제안, 받은 임명 제안만 서버 대기', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page, 1, { '/court/local-offices': LOCAL_OFFICES, '/map/preview': LOCAL_PREVIEW });
        const table = page.getByRole('list', { name: '지방 관직' });
        const rows = table.getByRole('button');
        await expect(rows).toHaveCount(2);
        // 주 먼저 — 예주(명목) · 경조윤(실권 있음) 순, 첫 줄이 골라져 있다.
        await expect(rows.nth(0)).toContainText('예주');
        await expect(rows.nth(0)).toContainText('명목');
        await expect(rows.nth(0)).toHaveAttribute('aria-pressed', 'true');
        await expect(rows.nth(1)).toContainText('경조윤');
        await expect(rows.nth(1)).toContainText('실권 있음');
        await expect(page.getByRole('region', { name: '임명할 수 있는 자리' })).toContainText('이미 해당 관할에 재임자가 있습니다.');
        await expect(page.getByRole('region', { name: '보낸 임명 제안' })).toContainText('기한 196년 1월 하순');
        // 받은 임명 제안(K8-02)과 보낸 제안의 이름 빈칸(K8-03 계약 빈칸)만 서버 대기로 남는다.
        expect(await serverWaits(page)).toEqual(['K8-02', 'K8-03', 'K8-03']);
        await expect(page.locator(`${MAIN} [data-input-id]`)).toHaveCount(0);
        await rules(page);
        await press(rows.nth(1), testInfo); // 다른 자리를 눌렀다가(터치 포함)
        await expect(rows.nth(1)).toHaveAttribute('aria-pressed', 'true');
        const nominal = rows.nth(0);
        await press(nominal, testInfo); // 명목 자리를 누르면 경고와 실효 판정
        await expect(nominal).toHaveAttribute('aria-pressed', 'true');
        const detail = page.getByRole('region', { name: '고른 관할' });
        await expect(detail.getByRole('note')).toContainText('명목입니다');
        await expect(detail.getByRole('list', { name: '실효 판정' }).getByRole('listitem')).toHaveCount(8);
        await rules(page);
    });

    test('그려짐: 재야 — 「세력에 속해야 관직이 있습니다」만', { tag: [BOTH] }, async ({ page }) => {
        await open(page, 0);
        await expect(page.getByText('세력에 속해야 관직이 있습니다')).toBeVisible();
        await expect(page.getByRole('tablist', { name: '관직 · 봉신 보기' })).toHaveCount(0);
        await rules(page);
    });
});
