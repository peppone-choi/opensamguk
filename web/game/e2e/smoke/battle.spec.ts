// 전투 · 부재 대비(P-C04) — /game/corps/battle 을 백엔드 없이 합성 자료로 돈다(로그인 · front-info 합성, 나머지 게임 읽기는 503).
// 두 프로필(@both): 허브 안 누를 영역 44 · 네이티브 disabled 0 · title 0 · 가로 넘침 0 · 영어 원문 0, 전투 목록은 서버 대기,
// 부재 대비는 방침 행 · 고치러 가는 길(영지 · 계책 덱). 방침 읽기가 실패해도 화면 글자는 한국어뿐(K3 #1133 규칙). 전투 방은 서버 대기.
// 내 전투 목록(K6-11): 서버가 C2 활성 목록(#1396) 모양으로 행을 주면 목록 · 입장(방 주소), 빈 배열은 아직 서버 대기(A안). 고정 자료는 시험 안에만.
import { expect, test, type Page, type Route } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';
// 서버 대기 표지 읽기(#1335) — 의존 없는 도우미 파일만 가져온다.
import { serverWaitRows } from '../../../shared/src/serverWaitTesting';

const API = '/api/game/api';
const HUB = '[data-testid="battle-hub"]';

type Active = 'none' | 'empty' | 'rows';

/** #1396 BattleActiveEntry 모양 — worldId · sourceId 는 문자열, 장소 · 양쪽 · 리플레이는 null + SOURCE_NOT_AVAILABLE. */
const activeRow = (over: Record<string, unknown>) => ({
    battleId: '9001', worldId: '7', kind: 'FIELD', sourcePhase: 'JOINING', phase: 'JOINING', joinDeadlineAt: new Date(Date.now() + 10 * 60_000).toISOString(),
    observedAt: new Date().toISOString(), mySeat: { sourceKeys: [{ kind: 'RETINUE', sourceId: '11' }, { kind: 'RETINUE', sourceId: '12' }] },
    pacingMode: null, pacingModeUnavailableReason: 'SOURCE_NOT_AVAILABLE', controller: null, controllerUnavailableReason: 'SOURCE_NOT_AVAILABLE',
    place: null, placeUnavailableReason: 'SOURCE_NOT_AVAILABLE', sides: null, sidesUnavailableReason: 'SOURCE_NOT_AVAILABLE',
    replayId: null, replayIdUnavailableReason: 'SOURCE_NOT_AVAILABLE', ...over,
});

async function serve(page: Page, policies: 'ok' | 'fail', active: Active = 'none') {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const path = new URL(route.request().url()).pathname.slice(API.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 5, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/policies') {
            if (policies === 'fail') return route.fulfill({ status: 500, contentType: 'text/plain', body: 'Internal Server Error' });
            return json(route, 200, {
                status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: { code: 'DEFEND', label: '수비' },
                corps: [{ orderId: 'O-1', commanderName: '하후돈', active: { policy: 'INTERCEPT', label: '요격' }, pending: { policy: 'EVADE', label: '회피' }, settable: true, blocked: null }],
                counties: [
                    { countyId: 30, name: '허현', commanderyName: '영천군', active: null, pending: null, effective: { policy: 'DEFEND', label: '수비', source: 'DEFAULT' }, seat: { generalId: 7, name: '하후돈', placed: false }, settable: true, blocked: null },
                    { countyId: 31, name: '양적현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: { generalId: 8, name: '수하', placed: true }, settable: true, blocked: null },
                    { countyId: 32, name: '장사현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: { generalId: 9, name: '다른 장수', placed: false }, settable: false, blocked: null },
                ],
            });
        }
        if (path === '/battles/active' && active !== 'none') {
            return json(route, 200, active === 'empty' ? [] : [
                activeRow({ battleId: '9100', sourcePhase: 'RESULT_BLOCKED', phase: 'RESULT_BLOCKED', joinDeadlineAt: null }),
                activeRow({}),
                activeRow({ battleId: '9200', sourcePhase: 'APPLIED', phase: 'APPLIED', joinDeadlineAt: null }),
            ]);
        }
        return json(route, 503, {});
    });
}

/** 화면 글자 속 영어 낱말(3글자 이상) — 서버 원문(「Internal Server Error」 등)이 새면 걸린다. */
async function englishWords(page: Page, root: string): Promise<string[]> {
    const text = await page.locator(root).first().innerText();
    return text.match(/[A-Za-z]{3,}/g) ?? [];
}

async function open(page: Page, policies: 'ok' | 'fail' = 'ok', active: Active = 'none') {
    await serve(page, policies, active);
    await page.goto('/game/corps/battle', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: '전투 · 부재 대비', exact: true })).toBeVisible({ timeout: 60_000 });
    await expect(page.locator(HUB)).toBeVisible({ timeout: 60_000 });
}

test.describe('전투 · 부재 대비', () => {
    test('규칙: 44 · disabled 0 · title 0 · 넘침 0 · 영어 원문 0, 전투 목록은 서버 대기, 부재 대비는 방침 행', { tag: [BOTH] }, async ({ page }) => {
        await open(page);
        await expect(page.getByText('전투가 열리지 않습니다(서버 준비 중)')).toBeVisible();
        const rows = page.getByRole('list', { name: '없을 때 싸우는 것' });
        await expect(rows).toContainText('하후돈 군단');
        await expect(rows).toContainText('다음 순부터 회피');
        await expect(rows).toContainText('허현');
        await expect(rows).toContainText('직접 맡은 현');
        await expect(rows.getByRole('listitem')).toHaveCount(2);
        await expect(rows).not.toContainText('양적현');
        await expect(rows).not.toContainText('장사현');
        expect(await smallTouchTargets(page, HUB)).toEqual([]);
        expect(await titleOnlyInfo(page, HUB)).toEqual([]);
        expect(await page.locator(`${HUB} :disabled`).count()).toBe(0);
        expect(await englishWords(page, HUB)).toEqual([]);
        await expectNoHorizontalOverflow(page);
    });

    test('내 전투 목록(K6-11) — 행: 종류 · 장소/양쪽 서버 대기 · 내 부곡 n개 · 단계 칩(막힘 · 모르는 단계는 입장 없음) · 입장 → 전투 방, 규칙 44 · 영어 원문 0 · 넘침 0', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page, 'ok', 'rows');
        const list = page.getByRole('list', { name: '내 전투 목록' });
        await expect(list.getByRole('listitem')).toHaveCount(3);
        const [joining, blocked, unknown] = [0, 1, 2].map((i) => list.getByRole('listitem').nth(i));
        await expect(joining).toContainText('야전');
        await expect(joining).toContainText('내 부곡 2개');
        await expect(joining).toContainText('참가 대기');
        await expect(joining.getByRole('timer', { name: '개전까지 남은 시간' })).toHaveText(/^약 (9|10):\d\d$/);
        await expect(blocked).toContainText('결과 반영이 막힘 — 운영 확인 중');
        await expect(blocked.getByRole('link')).toHaveCount(0);
        await expect(unknown).toContainText('상태 확인 중');
        await expect(unknown.getByRole('link')).toHaveCount(0);
        expect([...new Set(await list.evaluate(serverWaitRows))].sort()).toEqual(['K6-11 · place', 'K6-11 · sides']);
        expect(await smallTouchTargets(page, HUB)).toEqual([]);
        expect(await titleOnlyInfo(page, HUB)).toEqual([]);
        expect(await englishWords(page, HUB)).toEqual([]);
        await expectNoHorizontalOverflow(page);
        const enter = joining.getByRole('link', { name: '입장' });
        await expect(enter).toHaveAttribute('href', /\/corps\/battle\/9001\?world=7$/);
        await press(enter, testInfo);
        // 방은 운영처럼 티켓 경로가 없으면(503) 「전투가 열리지 않습니다」 — 가짜 전투 없음.
        await expect(page.getByTestId('battle-room')).toBeVisible({ timeout: 60_000 });
        await expect(page).toHaveURL(/\/corps\/battle\/9001\?world=7$/);
    });

    test('내 전투 목록 — 빈 배열은 아직 「전투 없음」이 아니라 서버 대기(A안)', { tag: [BOTH] }, async ({ page }) => {
        await open(page, 'ok', 'empty');
        await expect(page.getByRole('region', { name: '내 전투' }).getByText('전투가 열리지 않습니다(서버 준비 중)')).toBeVisible();
        await expect(page.getByRole('list', { name: '내 전투 목록' })).toHaveCount(0);
    });

    test('「방침 고치기」는 영지(배치 · 방침), 「대응 계책 칸 보기」는 계책 덱으로 간다', { tag: [BOTH] }, async ({ page }, testInfo) => {
        await open(page);
        const policy = page.getByRole('button', { name: '방침 고치기' });
        // 모바일 하단 탭(sticky)이 화면 맨 아래 끝을 덮는다 — 셸 scroll-padding 대기(K3). 고쳐지면 이 줄을 지운다.
        await policy.evaluate((el) => el.scrollIntoView({ block: 'center' }));
        await press(policy, testInfo);
        await expect(page).toHaveURL(/\/game\/territory/);
    });

    test('방침 읽기가 실패해도 화면 글자는 한국어뿐 · 다시 시도', { tag: [BOTH] }, async ({ page }) => {
        await open(page, 'fail');
        await expect(page.getByText('방침을 불러오지 못했습니다')).toBeVisible();
        await expect(page.getByRole('button', { name: /다시 시도/ })).toBeVisible();
        expect(await englishWords(page, HUB)).toEqual([]);
    });

    test('전투 방은 서버 대기 — 돌아가기는 전투 · 부재 대비', { tag: [BOTH] }, async ({ page }) => {
        await serve(page, 'ok');
        await page.goto('/game/corps/battle/B-1', { waitUntil: 'domcontentloaded' });
        const room = page.getByTestId('battle-room');
        await expect(room).toBeVisible({ timeout: 60_000 });
        await expect(room).toContainText('전투가 열리지 않습니다(서버 준비 중)');
        expect(await englishWords(page, '[data-testid="battle-room"]')).toEqual([]);
    });
});
