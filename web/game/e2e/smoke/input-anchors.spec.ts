// 입력 UI 증거(O3, K6 몫) — 명령 흐름(P-W02)의 직접 행동 43개를 하나씩 앵커로 연다(백엔드 없이 합성 자료, command-flow.spec 과 같은 방식).
// 입력마다: `/game?do=<id>` → 흐름 보내기 단추 `[data-input-id="<id>"][data-input-status]` → 인자 고르기 → 누르면
// 그 Request 의 정확한 경로(pathname) · 본문(postDataJSON) · generalId · 순을 단언한다. 원장 PLANNED(처리기 없음)는 「준비 중」
// (NOT_DELIVERED)이고 눌러도 그 경로로 아무것도 보내지 않는다.
// 흐름 밖 K6 화면(입력 도달 표 V31K6InputReach): 시야 · 첩보 「첩보」, 군단 화면(편성 해제 · 부대 모으기 · 출병), 외교 세력 줄의 종전 제의와 나머지 준비 중 입력 넷,
// 계책 덱 「걸기」(stratagem.play, PLANNED).
// 공성 화면(P-C02)은 K4 화면이라 여기서 다루지 않는다(K6 경로는 흐름).
//
// 원장 evidence `ui-e2e:web/game/e2e/smoke/input-anchors.spec.ts#<입력 id>` 의 자리(C1 O3 게이트 계획 2026-10-02):
// - 시험 제목은 `[<입력 id>] …`. 사례는 고정 배열(`as const`)의 한 줄이고, 입력 id · 정확한 경로 · 보낼 본문이 같은 줄에 있다.
// - 앵커는 page 에서 바로 만든 locator, 누르기는 parity `press`, 본문은 page.waitForRequest 로 받은 그 Request 로 단언한다
//   (route 대역 안에서 받은 값으로 단언하지 않는다).
// 대역 값(사람 · 장소 · 선택지)은 「검증용」으로만 쓴다 — 실제 규칙 수치를 흉내 내지 않는다.
import { expect, test, type Locator, type Page, type Route } from '@playwright/test';
import { FLOW_COMMANDS } from '../../lib/command-flow/catalog';
import equipmentCatalog from '../../../../data/curated/han/equipment-v1.json';
import type { ReservedSlot } from '../../lib/types';
import { BOTH, press } from '../support/parity';

const GENERAL_ID = 7;
const PERSON = { generalId: 8, name: '검증용 인물', available: true };
const FIELD_READ = { available: true, countyId: 30, countyName: '검증용 현' };
const MILITARY_READ = { available: true, countyName: '검증용 현', troops: 100, troopsAfter: 200 };
const DESTINATIONS = [{ provinceId: 'P-1', name: '검증용 목적지', available: true }];
const POLITICAL = [
    { inputId: 'action.rise', available: true },
    { inputId: 'action.foundState', available: true },
    { inputId: 'action.resign', available: true },
    { inputId: 'action.abdicate', available: true, targets: [PERSON] },
    { inputId: 'action.oath', available: true, targets: [PERSON] },
];
const CONVERT_CHOICE = { label: '3번 부곡 → 1100번 병종', arguments: { bugokId: 3, crewTypeId: 1100 }, available: true };
const EQUIPMENT_ID = equipmentCatalog.equipment[0].id;
const EQUIPMENT_CHOICE = { label: '노기(+1) 매입 · 전 1000', arguments: { equipmentId: EQUIPMENT_ID, side: 'BUY' }, available: true };
const EQUIPMENT_READ = { inputId: 'action.tradeEquipment', available: true, choices: [EQUIPMENT_CHOICE], equipmentNames: { [EQUIPMENT_ID]: '노기(+1)' } };
const GRAIN_CHOICE = { label: '쌀 매입', arguments: { side: 'BUY', amount: 1 }, available: true };
const TRANSPORT_CHOICE = { label: '진류현 · 금', arguments: { targetCountyId: 30, cargo: 'MONEY', amount: 1 }, available: true, maxAmount: 300 };

/**
 * 처리기가 있는 흐름 입력(원장 HANDLER_READY · UI_READY).
 * reads = 인자 읽기 대역(게이트웨이 `/api/game/api` 뒤 경로 → 본문), picks = 흐름에서 차례로 고를 후보(role=option 이름),
 * amount = 수량 칸(「얼마나」), path = 보내야 할 정확한 경로(lib/api.ts `command`), args = 보내야 할 본문.
 */
const FLOW_CASES = [
    { inputId: 'action.tradeEquipment', name: '장비매매', reads: { '/commands/legacy-direct-options?inputId=action.tradeEquipment': EQUIPMENT_READ }, picks: ['노기(+1) 매입'], path: '/api/game/api/command/action.tradeEquipment', args: { equipmentId: EQUIPMENT_ID, side: 'BUY' } },
    { inputId: 'action.farm', name: '농지개간', reads: { '/commands/farm-options': FIELD_READ }, picks: [], path: '/api/game/api/command/action.farm', args: {} },
    { inputId: 'action.commerce', name: '상업투자', reads: { '/commands/commerce-options': FIELD_READ }, picks: [], path: '/api/game/api/command/action.commerce', args: {} },
    { inputId: 'action.fortify', name: '수비강화', reads: { '/commands/fortify-options': FIELD_READ }, picks: [], path: '/api/game/api/command/action.fortify', args: {} },
    { inputId: 'action.repairWall', name: '성벽보수', reads: { '/commands/repair-wall-options': FIELD_READ }, picks: [], path: '/api/game/api/command/action.repairWall', args: {} },
    { inputId: 'action.security', name: '치안강화', reads: { '/commands/security-options': FIELD_READ }, picks: [], path: '/api/game/api/command/action.security', args: {} },
    { inputId: 'action.settle', name: '정착장려', reads: { '/commands/settle-options': FIELD_READ }, picks: [], path: '/api/game/api/command/action.settle', args: {} },
    { inputId: 'action.selectResidents', name: '주민선정', reads: { '/commands/select-residents-options': FIELD_READ }, picks: [], path: '/api/game/api/command/action.selectResidents', args: {} },
    { inputId: 'action.tour', name: '순행', reads: { '/commands/tour-options': FIELD_READ }, picks: [], path: '/api/game/api/command/action.tour', args: {} },
    { inputId: 'action.conscript', name: '징병', reads: { '/commands/conscript-options': MILITARY_READ }, picks: [], path: '/api/game/api/command/action.conscript', args: {} },
    { inputId: 'action.raiseVolunteers', name: '모병', reads: { '/commands/raise-volunteers-options': MILITARY_READ }, picks: [], path: '/api/game/api/command/action.raiseVolunteers', args: {} },
    { inputId: 'action.train', name: '훈련', reads: { '/commands/train-options': MILITARY_READ }, picks: [], path: '/api/game/api/command/action.train', args: {} },
    { inputId: 'action.boostMorale', name: '사기진작', reads: { '/commands/boost-morale-options': MILITARY_READ }, picks: [], path: '/api/game/api/command/action.boostMorale', args: {} },
    { inputId: 'action.demobilize', name: '소집해제', reads: { '/commands/demobilize-options': MILITARY_READ }, picks: [], path: '/api/game/api/command/action.demobilize', args: {} },
    { inputId: 'action.muster', name: '집합', reads: { '/commands/muster-options': MILITARY_READ }, picks: [], path: '/api/game/api/command/action.muster', args: {} },
    {
        inputId: 'action.deploy', name: '출병',
        reads: { '/deploy/options': { available: true, maxReservedTurns: 12, bugoks: [{ id: 7, name: '검증용 부곡', troops: 100, available: true }], destinations: [{ provinceId: 'P-1', name: '검증용 목적지', available: true }] } },
        picks: [/검증용 부곡/, /검증용 목적지/], path: '/api/game/api/command/action.deploy', args: { bugokIds: [7], destinationProvinceId: 'P-1' },
    },
    {
        inputId: 'action.scout', name: '첩보',
        reads: { '/scout-options': { status: 'READY', inputId: 'action.scout', available: true, options: [{ no: 1, id: 'C-1', name: '검증용 군', tier: 'FOG', available: true }] } },
        picks: [/검증용 군/], path: '/api/game/api/command/action.scout', args: { commanderyId: 'C-1' },
    },
    {
        inputId: 'action.assault', name: '강공',
        reads: { '/sieges': { status: 'READY', sieges: [{ countyId: 30, countyName: '진류현', status: 'ACTIVE',
            besieger: { generalId: GENERAL_ID }, canAct: true, canAssault: true, assaultCode: null, assaultReason: null }] } },
        picks: [/진류현/], path: '/api/game/api/command/action.assault', args: { targetCountyId: 30 },
    },
    {
        inputId: 'action.demandSurrender', name: '항복 권고',
        reads: { '/sieges': { status: 'READY', sieges: [{ countyId: 30, countyName: '검증용 현', status: 'ACTIVE', besieger: { generalId: GENERAL_ID }, canAct: true }] } },
        picks: [], path: '/api/game/api/command/action.demandSurrender', args: {},
    },
    {
        inputId: 'action.siegeRoadFort', name: '보루 포위',
        reads: { '/road-forts': { status: 'READY', roadMode: true, gates: [], forts: [{ id: 'F-1', edgeId: 'E-1', provinceId: 'P-1', provinceName: '검증용 보루 지명', row: 0, col: 0, ownerNationId: 2, wall: 10, garrison: 10, besiegerGeneralId: null, siegeProgress: 0, canBesiege: true }] } },
        picks: [/보루 · 검증용 보루 지명/], path: '/api/game/api/command/action.siegeRoadFort', args: { fortId: 'F-1' },
    },
    { inputId: 'action.move', name: '이동', reads: { '/commands/move-options': { inputId: 'action.move', available: true, destinations: DESTINATIONS } }, picks: [/검증용 목적지/], path: '/api/game/api/command/action.move', args: { destinationProvinceId: 'P-1' } },
    { inputId: 'action.forcedMarch', name: '강행', reads: { '/commands/forced-march-options': { inputId: 'action.forcedMarch', available: true, destinations: DESTINATIONS } }, picks: [/검증용 목적지/], path: '/api/game/api/command/action.forcedMarch', args: { destinationProvinceId: 'P-1' } },
    { inputId: 'action.return', name: '귀환', reads: { '/commands/return-options': { inputId: 'action.return', available: true, destinations: [] } }, picks: [], path: '/api/game/api/command/action.return', args: {} },
    { inputId: 'action.search', name: '인재탐색', reads: { '/commands/search-options': { inputId: 'action.search', available: true, undiscoveredCount: 2, targets: [] } }, picks: [], path: '/api/game/api/command/action.search', args: {} },
    { inputId: 'action.employ', name: '등용', reads: { '/commands/employ-options': { inputId: 'action.employ', available: true, targets: [PERSON] } }, picks: [/검증용 인물/], path: '/api/game/api/command/action.employ', args: { targetGeneralId: 8 } },
    { inputId: 'action.persuadeCaptive', name: '포로 설득', reads: { '/commands/persuade-captive-options': { inputId: 'action.persuadeCaptive', available: true, targets: [PERSON] } }, picks: [/검증용 인물/], path: '/api/game/api/command/action.persuadeCaptive', args: { targetGeneralId: 8 } },
    { inputId: 'action.travel', name: '견문', reads: { '/commands/travel-options': { inputId: 'action.travel', available: true } }, picks: [], path: '/api/game/api/command/action.travel', args: {} },
    { inputId: 'action.selfTrain', name: '단련', reads: { '/commands/self-train-options': { inputId: 'action.selfTrain', available: true, stats: [{ stat: 'strength', available: true }] } }, picks: [/무력/], path: '/api/game/api/command/action.selfTrain', args: { stat: 'strength' } },
    { inputId: 'action.recuperate', name: '요양', reads: { '/commands/recuperate-options': { inputId: 'action.recuperate', available: true } }, picks: [], path: '/api/game/api/command/action.recuperate', args: {} },
    {
        inputId: 'action.convertProficiency', name: '병종 바꿔 익히기',
        reads: { '/commands/legacy-direct-options?inputId=action.convertProficiency': { inputId: 'action.convertProficiency', available: true, choices: [CONVERT_CHOICE] } },
        picks: [/부곡 #3 — 보병으로 병종 바꿔 익히기/], path: '/api/game/api/command/action.convertProficiency', args: { bugokId: 3, crewTypeId: 1100 },
    },
    {
        inputId: 'action.enlist', name: '출사',
        reads: { '/commands/enlistment-options': { result: true, inputId: 'action.enlist', maxReservedTurns: 12, options: [{ mode: 'GENERAL', targetId: 8, label: '검증용 주공', availability: { status: 'AVAILABLE' } }] } },
        picks: [/검증용 주공/], path: '/api/game/api/command/action.enlist', args: { mode: 'GENERAL', targetId: 8 },
    },
    { inputId: 'action.foundState', name: '건국', reads: { '/commands/political-options': POLITICAL }, picks: [], path: '/api/game/api/command/action.foundState', args: {} },
    { inputId: 'action.rise', name: '거병', reads: { '/commands/political-options': POLITICAL }, picks: [], path: '/api/game/api/command/action.rise', args: {} },
    { inputId: 'action.resign', name: '하야', reads: { '/commands/political-options': POLITICAL }, picks: [], path: '/api/game/api/command/action.resign', args: {} },
    { inputId: 'action.abdicate', name: '선양', reads: { '/commands/political-options': POLITICAL }, picks: [/검증용 인물/], path: '/api/game/api/command/action.abdicate', args: { targetGeneralId: 8 } },
    { inputId: 'action.oath', name: '결의', reads: { '/commands/political-options': POLITICAL }, picks: [/검증용 인물/], path: '/api/game/api/command/action.oath', args: { targetGeneralId: 8 } },
    {
        inputId: 'action.gift', name: '증여',
        reads: { '/commands/gift-options': { inputId: 'action.gift', available: true, targets: [PERSON], resources: [{ resource: 'MONEY', available: true, maxAmount: 300 }] } },
        picks: [/검증용 인물/, /^금/], amount: 100, path: '/api/game/api/command/action.gift', args: { targetGeneralId: 8, resource: 'MONEY', amount: 100 },
    },
    {
        inputId: 'action.tradeGrain', name: '쌀 사고팔기',
        reads: { '/commands/legacy-direct-options?inputId=action.tradeGrain': { inputId: 'action.tradeGrain', available: true, choices: [GRAIN_CHOICE] } },
        picks: [/쌀 매입/], path: '/api/game/api/command/action.tradeGrain', args: { side: 'BUY', amount: 1 },
    },
    {
        inputId: 'action.transport', name: '물자조달',
        reads: { '/commands/legacy-direct-options?inputId=action.transport': { inputId: 'action.transport', available: true, choices: [TRANSPORT_CHOICE] } },
        picks: [/진류현 · 금/], amount: 100, path: '/api/game/api/command/action.transport', args: { targetCountyId: 30, cargo: 'MONEY', amount: 100 },
    },
] as const;

/** 원장 PLANNED 흐름 입력 — 「준비 중」 · 사유 시트 · path 로 POST 0. */
const FLOW_PLANNED = [
    { inputId: 'action.retire', name: '은퇴', path: '/api/game/api/command/action.retire' },
    { inputId: 'action.independence', name: '독립', path: '/api/game/api/command/action.independence' },
    { inputId: 'action.dissolve', name: '세력 해산', path: '/api/game/api/command/action.dissolve' },
] as const;

/** 대역 서버: 로그인 · front-info · 사례의 읽기, 흐름 예약 · 조정 POST 는 202 접수 · 결과 조회 RESOLVED, 나머지 게임 읽기는 503. */
async function serve(page: Page, reads: Readonly<Record<string, unknown>>) {
    const slots = new Map<number, ReservedSlot>();
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.slice('/api/game/api'.length);
        if (path === '/front-info') {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: GENERAL_ID, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/reserved-commands') return json(route, 200, { result: true, generalId: GENERAL_ID, slots: [...slots.values()] });
        if (path === '/const') return json(route, 200, { result: true, gameUnitConst: [{ id: 1100, name: '보병' }] });
        if (path === '/map/preview') return json(route, 200, {
            serverName: 'qa', year: 200, month: 3, mapCode: 'qa', width: 1, height: 1, nations: [],
            cities: [{ id: 30, name: '진류', displayName: '진류현', level: 1, nationId: 1, x: 0, y: 0, state: 0, supply: true, isCapital: false }],
        });
        const inputQuery = url.searchParams.get('inputId');
        const read = reads[inputQuery ? `${path}?inputId=${inputQuery}` : path];
        if (read !== undefined && route.request().method() === 'GET') return json(route, 200, read);
        if (path.startsWith('/command/action.') && route.request().method() === 'POST') {
            const action = path.slice('/command/'.length);
            const turnIdx = Number(url.searchParams.get('turnIdx'));
            if (url.searchParams.has('turnIdx')) slots.set(turnIdx, {
                turnIdx, action, brief: FLOW_COMMANDS.find(c => c.inputId === action)?.name ?? action,
                arg: structuredClone(route.request().postDataJSON()),
            });
            return json(route, 202, { status: 'AVAILABLE', requestId: 'r-1', turnIdx });
        }
        if (path.startsWith('/commands/court/') && route.request().method() === 'POST') return json(route, 202, { status: 'AVAILABLE', requestId: 'r-1' });
        if (path === '/command/result/r-1') {
            return json(route, 200, { status: 'RESOLVED', requestId: 'r-1', ok: true, type: 'reservationAccepted', result: { commandKind: 'RESERVED_TURN' } });
        }
        return json(route, 503, {});
    });
}

/** 정확히 이 경로로 간 POST — 「준비 중」 입력은 0이어야 한다. */
function postsTo(page: Page, path: string): string[] {
    const sent: string[] = [];
    page.on('request', (r) => {
        if (r.method() === 'POST' && new URL(r.url()).pathname === path) sent.push(path);
    });
    return sent;
}

test('흐름 직접 행동 표와 이 스펙의 사례가 한 줄도 빠지지 않고 이름 · 전달 상태가 같다', () => {
    const cases = [...FLOW_CASES, ...FLOW_PLANNED, { inputId: 'action.donate', name: '헌납' }]; // donation-input.spec.ts
    expect(cases.map((c) => c.inputId).sort()).toEqual(FLOW_COMMANDS.map((c) => c.inputId).sort());
    expect(new Set(cases.map((c) => c.inputId)).size).toBe(cases.length);
    for (const c of cases) expect(FLOW_COMMANDS.find((f) => f.inputId === c.inputId)?.name).toBe(c.name);
    for (const c of FLOW_CASES) expect(FLOW_COMMANDS.find((f) => f.inputId === c.inputId)?.delivery).not.toBe('PLANNED');
    for (const c of FLOW_PLANNED) expect(FLOW_COMMANDS.find((f) => f.inputId === c.inputId)?.delivery).toBe('PLANNED');
    expect(FLOW_COMMANDS.find((f) => f.inputId === 'action.rise')?.delivery).toBe('HANDLER_READY');
    expect(FLOW_COMMANDS.find((f) => f.inputId === 'action.donate')?.delivery).toBe('UI_READY');
});

test.describe('입력 앵커 — 명령 흐름', () => {
    const savedSentences: Readonly<Record<string, string>> = {
        'action.conscript': '징병 (병종·인원 미기록)',
        'action.raiseVolunteers': '모병 (병종·인원 미기록)',
        'action.deploy': '부곡 #7 — 검증용 목적지로 출병',
        'action.scout': '지정 군 (이름 확인 불가) 첩보',
        'action.assault': '진류현 공격',
        'action.demandSurrender': '항복 권고 (대상 현 확인 불가)',
        'action.siegeRoadFort': '지정 보루 (이름 확인 불가) 포위',
        'action.move': '검증용 목적지로 이동',
        'action.forcedMarch': '검증용 목적지로 강행',
        'action.return': '귀환 (목적지 미기록)',
        'action.employ': '검증용 인물 등용',
        'action.persuadeCaptive': '장수 #8 (이름 확인 불가) 포로 설득',
        'action.selfTrain': '무력 단련',
        'action.convertProficiency': '부곡 #3 — 보병으로 병종 바꿔 익히기',
        'action.enlist': '장수 #8 (이름 확인 불가)에게 출사',
        'action.abdicate': '장수 #8 (이름 확인 불가) 선양',
        'action.oath': '장수 #8 (이름 확인 불가) 결의',
        'action.gift': '금 100 — 장수 #8 (이름 확인 불가)에게 증여',
        'action.tradeEquipment': '노기(+1) 매입',
        'action.tradeGrain': '쌀 매입 — 1',
        'action.transport': '금 100 — 진류현으로 물자조달',
    };
    for (const c of FLOW_CASES) {
        test(`[${c.inputId}] ${c.name}: 흐름 앵커에서 고르고 보내면 그 입력 · 순 · 인자로 접수를 청한다`, { tag: [BOTH] }, async ({ page }, info) => {
            await serve(page, c.reads);
            await page.goto(`/game?do=${c.inputId}`, { waitUntil: 'domcontentloaded' });
            const flow = page.getByTestId('command-flow');
            await expect(flow).toBeVisible({ timeout: 60_000 });
            await expect(flow.locator('[data-turn-idx="0"]')).toHaveAttribute('aria-pressed', 'true');
            for (const pick of c.picks) await press(flow.getByRole('option', { name: pick }).first(), info);
            if ('amount' in c) await flow.getByRole('spinbutton', { name: '얼마나' }).fill(String(c.amount));
            const submit = flow.locator(`[data-input-id="${c.inputId}"][data-input-status]`);
            await expect(submit).toHaveAttribute('data-input-status', 'AVAILABLE');
            await expect(submit).toHaveText('01순에 예약');
            const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === c.path);
            const saved = page.waitForResponse(async response => {
                if (new URL(response.url()).pathname !== '/api/game/api/reserved-commands' || !response.ok()) return false;
                const body = await response.json() as { slots: ReservedSlot[] };
                return body.slots.some(slot => slot.turnIdx === 0 && slot.action === c.inputId);
            });
            await press(submit, info);
            const request = await sent;
            expect(request.postDataJSON()).toEqual(c.args);
            const query = new URL(request.url()).searchParams;
            expect(query.get('generalId')).toBe(String(GENERAL_ID));
            expect(query.get('turnIdx')).toBe('0');
            expect(await (await saved).json()).toMatchObject({
                result: true, generalId: GENERAL_ID,
                slots: [{ turnIdx: 0, action: c.inputId, brief: c.name, arg: c.args }],
            });
            await expect(flow.getByText(`「${savedSentences[c.inputId] ?? c.name}」 — 01순에 예약했습니다.`)).toBeVisible();
        });
    }

    test('[action.tradeEquipment] 보물과 상태 거절 선택은 예약 POST를 보내지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
        const path = '/api/game/api/command/action.tradeEquipment';
        const sent = postsTo(page, path);
        await serve(page, { '/commands/legacy-direct-options?inputId=action.tradeEquipment': {
            ...EQUIPMENT_READ, available: false, code: 'INSUFFICIENT_SECURITY', reason: '치안이 부족합니다.', choices: [
                { ...EQUIPMENT_CHOICE, available: false, code: 'INSUFFICIENT_SECURITY', reason: '치안이 부족합니다.' },
                { label: '보물 매입', arguments: { treasureId: 12, side: 'BUY' }, available: false, code: 'NOT_DELIVERED', reason: '아직 제공되지 않았습니다.' },
            ],
        } });
        await page.goto('/game?do=action.tradeEquipment', { waitUntil: 'domcontentloaded' });
        const flow = page.getByTestId('command-flow');
        await expect(flow.getByRole('option', { name: '노기(+1) 매입' })).toHaveAttribute('aria-disabled', 'true');
        await expect(flow.getByRole('option', { name: '보물 매입' })).toHaveAttribute('aria-disabled', 'true');
        await expect(flow.locator('[data-input-id="action.tradeEquipment"][data-input-status]')).not.toHaveAttribute('data-input-status', 'AVAILABLE');
        await press(flow.locator('[data-input-id="action.tradeEquipment"][data-input-status]'), info);
        await expect(page.getByRole('dialog', { name: '장비매매 — 지금은 할 수 없습니다' })).toBeVisible();
        expect(sent).toEqual([]);
    });

    for (const c of FLOW_PLANNED) {
        test(`[${c.inputId}] ${c.name}: 원장 PLANNED — 「준비 중」이고 눌러도 보내지 않는다`, { tag: [BOTH] }, async ({ page }, info) => {
            const sent = postsTo(page, c.path);
            await serve(page, {});
            await page.goto(`/game?do=${c.inputId}`, { waitUntil: 'domcontentloaded' });
            const flow = page.getByTestId('command-flow');
            await expect(flow).toBeVisible({ timeout: 60_000 });
            await expect(flow.getByText('아직 열리지 않은 명령입니다')).toBeVisible();
            const submit = flow.locator(`[data-input-id="${c.inputId}"][data-input-status]`);
            await expect(submit).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
            await press(submit, info);
            const sheet = page.getByRole('dialog', { name: `${c.name} — 지금은 할 수 없습니다` });
            await expect(sheet).toBeVisible();
            await expect(sheet).toContainText('준비 중');
            expect(sent).toEqual([]);
        });
    }
});

const nation = (id: number, name: string, color: string) => ({ nation: id, name, color, type: '', level: 1, capital: 0, gennum: 1, cities: [], power: 0 });
// 내 세력(1)과의 관계: 원소 교전(0) · 유표 불가침(7) · 손책 관계 없음(2) · 원술 선전포고 유예(1).
const DIPLOMACY_READS = {
    '/diplomacy/conflict': {
        result: true, conflict: [], myNationID: 1,
        nations: [nation(1, '조조', '#4f7fbf'), nation(2, '원소', '#b04a3c'), nation(3, '유표', '#4f8f5a'), nation(4, '손책', '#b9b2a3'), nation(5, '원술', '#9a7a3a')],
        diplomacyList: { 1: { 2: 0, 3: 7, 4: 2, 5: 1 } },
    },
};
/** 아직 제공되지 않는 외교 입력 넷 — 종전 제의는 peace-offer-input.spec.ts에서 확인한다. */
const DIPLOMACY_PLANNED = [
    { inputId: 'court.diplomacy', label: '원조', nationId: 4, path: '/api/game/api/commands/court/diplomacy' },
    { inputId: 'court.nonAggression', label: '불가침 제의', nationId: 4, path: '/api/game/api/commands/court/nonAggression' },
    { inputId: 'court.declareWar', label: '선전포고', nationId: 4, path: '/api/game/api/commands/court/declareWar' },
    { inputId: 'court.breakNonAggression', label: '불가침 파기', nationId: 3, path: '/api/game/api/commands/court/breakNonAggression' },
] as const;

test.describe('입력 앵커 — 흐름 밖 K6 화면', () => {
    // 시야 · 첩보(P-C06)의 「첩보」는 그 군을 미리 고른 작전실 흐름을 연다 — 화면 앵커에서 접수 요청까지 한 사례로 본다.
    test('[action.scout] 첩보: 시야 · 첩보 화면 「첩보」에서 흐름으로 가 그 군으로 접수를 청한다', { tag: [BOTH] }, async ({ page }, info) => {
        await serve(page, {
            '/visibility': { status: 'READY', commanderies: [{ no: 1, id: 'c1', name: '영천군', tier: 'FULL' }, { no: 2, id: 'c2', name: '진류군', tier: 'INTEL', ageTurns: 3 }] },
            '/scout-options': { status: 'READY', inputId: 'action.scout', available: true, options: [{ no: 2, id: 'c2', name: '진류군', tier: 'INTEL', available: true }] },
        });
        await page.goto('/game/corps/intel', { waitUntil: 'domcontentloaded' });
        const intel = page.getByRole('region', { name: '첩보', exact: true });
        await expect(intel).toBeVisible({ timeout: 60_000 });
        const open = intel.locator('[data-input-id="action.scout"][data-input-status]');
        await expect(open).toHaveAttribute('data-input-status', 'AVAILABLE');
        await press(open, info);
        await expect(page).toHaveURL(/[?&]target=commandery(%3A|:)c2\b/);
        const flow = page.getByTestId('command-flow');
        await expect(flow).toBeVisible({ timeout: 60_000 });
        await expect(flow.getByRole('option', { name: /진류군/ })).toHaveAttribute('aria-selected', 'true');
        const submit = flow.locator('[data-input-id="action.scout"][data-input-status]');
        await expect(submit).toHaveAttribute('data-input-status', 'AVAILABLE');
        const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/game/api/command/action.scout');
        await press(submit, info);
        const request = await sent;
        expect(request.postDataJSON()).toEqual({ commanderyId: 'c2' });
        expect(new URL(request.url()).searchParams.get('generalId')).toBe(String(GENERAL_ID));
    });

    for (const c of DIPLOMACY_PLANNED) {
        test(`[${c.inputId}] ${c.label}: 외교 세력 줄 — 원장 PLANNED라 「준비 중」이고 눌러도 보내지 않는다`, { tag: [BOTH] }, async ({ page }, info) => {
            const sent = postsTo(page, c.path);
            await serve(page, DIPLOMACY_READS);
            await page.goto('/game/court/diplomacy', { waitUntil: 'domcontentloaded' });
            const list = page.getByRole('list', { name: '세력별 관계' });
            await expect(list).toBeVisible({ timeout: 60_000 });
            const action = list.locator(`li[data-nation-id="${c.nationId}"] [data-input-id="${c.inputId}"][data-input-status]`);
            await expect(action).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
            await expect(action).toHaveText(c.label);
            // 모바일 하단 탭(sticky)이 화면 맨 아래를 덮는다 — stratagem.spec 과 같이 가운데로 올린 뒤 누른다.
            await action.evaluate((el) => el.scrollIntoView({ block: 'center' }));
            await press(action, info);
            const sheet = page.getByRole('dialog', { name: `${c.label} — 아직 열리지 않았습니다` });
            await expect(sheet).toBeVisible();
            await expect(sheet).toContainText('준비 중');
            expect(sent).toEqual([]);
        });
    }

    test('[stratagem.play] 손패 카드 쓰기: 계책 덱 「걸기」 → 계책 쓰기 시트 「간파 걸기」 — 원장 PLANNED라 「준비 중」이고 눌러도 보내지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
        const sent = postsTo(page, '/api/game/api/commands/stratagem/play');
        // 손패 v1 공급 규칙의 모양(짝수 instanceId = 간파 INSIGHT, 손패 상한 3)을 따른 대역 — 실제 공급 증거는 아니다.
        await serve(page, { '/commands/stratagem-hand': { status: 'READY', handLimit: 3, canUse: false, cards: [{ instanceId: 2, type: 'INSIGHT', label: '간파' }] } });
        await page.goto('/game/stratagem', { waitUntil: 'domcontentloaded' });
        await expect(page.getByRole('listbox', { name: '손패 카드' })).toBeVisible({ timeout: 60_000 });
        // 덱 단추는 시트(P-S02, ?card=)를 여는 길이고, 입력은 시트 아래 결정 단추다.
        const opener = page.getByRole('button', { name: '간파 — 대응 칸에 걸기' });
        await opener.evaluate((el) => el.scrollIntoView({ block: 'center' }));
        await press(opener, info);
        await expect(page.getByRole('complementary', { name: '계책 걸기' })).toBeVisible();
        const action = page.locator('[data-input-id="stratagem.play"][data-input-status]');
        await expect(action).toHaveCount(1);
        await expect(action).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
        await expect(action).toHaveText('간파 걸기');
        await action.evaluate((el) => el.scrollIntoView({ block: 'center' }));
        await press(action, info);
        const sheet = page.getByRole('dialog', { name: '계책 쓰기 — 아직 열리지 않았습니다' });
        await expect(sheet).toBeVisible();
        await expect(sheet).toContainText('준비 중');
        expect(sent).toEqual([]);
    });
});

// 군단 · 세력 작전(P-C01, #1192) — 화면 앵커에서 접수 요청까지. 출병 · 부대 모으기는 흐름으로 가고, 편성 해제는 확인 뒤 군단장으로 보낸다.
// 읽기 대역: 군단 · 시야 · 출병 옵션(군단 화면의 「지금 출병 명령」과 흐름의 출병 인자가 같은 읽기) · 방침 · 편성 해제 선택지.
const CORPS_READS = {
    '/corps': { status: 'READY', corps: [
        { corpsId: 'O-1', ownerGeneralId: GENERAL_ID, commanderGeneralId: GENERAL_ID, commanderName: '하후돈', nationId: 1, nationColor: '#4f7fbf', provinceId: 'P-1', commanderyNo: 12, visibility: 'FULL', own: true, troops: 1200, marchPath: [], destinationProvinceId: 'P-1' },
    ] },
    '/visibility': { status: 'READY', commanderies: [{ no: 12, id: 'c12', name: '검증용 군', tier: 'FULL' }] },
    '/deploy/options': {
        available: true, maxReservedTurns: 12,
        bugoks: [{ id: 7, name: '검증용 부곡', troops: 100, available: true }],
        destinations: [{ provinceId: 'P-1', name: '검증용 목적지', available: true }],
    },
    '/policies': { status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: { code: 'DEFEND', label: '수비' }, counties: [], corps: [] },
    '/commands/legacy-court-options?inputId=court.releaseCorps': {
        inputId: 'court.releaseCorps', available: true, choices: [{ label: '하후돈 군단', arguments: { targetGeneralId: GENERAL_ID }, available: true }],
    },
    '/commands/muster-options': { inputId: 'action.muster', available: true, countyName: '검증용 현', gatheringCorps: 1 },
};

/** 군단 화면을 열고 내 군단 줄이 보일 때까지. beforeGoto 는 serve 뒤 · 이동 전에 좁은 대역을 건다(나중에 건 route 가 먼저 받는다). */
async function openCorps(page: Page, beforeGoto?: () => Promise<void>) {
    await serve(page, CORPS_READS);
    await beforeGoto?.();
    await page.goto('/game/corps', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('region', { name: '내 군단' })).toBeVisible({ timeout: 60_000 });
}

/** 흐름 순 띠에서 01순이 읽힌 빈 순으로 골라지고 주소에 적힐 때까지 — 선택지 AVAILABLE 만으로는 예약할 순이 확인된 것이 아니다. */
async function expectFirstSlotReady(page: Page, flow: Locator) {
    const strip = flow.getByTestId('turn-slots-strip');
    await expect(strip).toBeVisible();
    const first = strip.locator('[data-turn-idx="0"]');
    await expect(first).toHaveAttribute('data-state', 'empty');
    await expect(first).toHaveAttribute('aria-pressed', 'true');
    await expect(page).toHaveURL(/[?&]slot=1\b/);
}

test.describe('입력 앵커 — 군단 화면', () => {
    test('[court.releaseCorps] 군단 편성 해제: 군단 카드 앵커 → 확인 → 그 군단장으로 접수를 청한다', { tag: [BOTH] }, async ({ page }, info) => {
        await openCorps(page);
        await press(page.getByRole('region', { name: '내 군단' }).getByRole('button', { name: /하후돈/ }), info);
        const release = page.getByRole('article', { name: '군단 — 하후돈' }).locator('[data-input-id="court.releaseCorps"][data-input-status]');
        await expect(release).toHaveAttribute('data-input-status', 'AVAILABLE');
        // 모바일 하단 탭(sticky)이 화면 맨 아래를 덮는다 — corps.spec 과 같이 가운데로 올린 뒤 누른다.
        await release.evaluate((el) => el.scrollIntoView({ block: 'center' }));
        await press(release, info);
        const confirm = page.getByRole('dialog').getByRole('button', { name: '편성 해제' });
        await expect(confirm).toBeVisible();
        const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/game/api/commands/court/releaseCorps');
        await press(confirm, info);
        const request = await sent;
        expect(request.postDataJSON()).toEqual({ targetGeneralId: GENERAL_ID });
        expect(new URL(request.url()).searchParams.get('generalId')).toBe(String(GENERAL_ID));
    });

    test('[action.muster] 집합: 군단 화면 「부대 모으기」 앵커 → 흐름 → 접수를 청한다', { tag: [BOTH] }, async ({ page }, info) => {
        await openCorps(page);
        const open = page.locator('[data-testid="corps-panel"] [data-input-id="action.muster"]');
        await press(open, info);
        await expect(page).toHaveURL(/[?&]do=action\.muster\b/);
        const flow = page.getByTestId('command-flow');
        await expect(flow).toBeVisible({ timeout: 60_000 });
        const submit = flow.locator('[data-input-id="action.muster"][data-input-status]');
        await expect(submit).toHaveAttribute('data-input-status', 'AVAILABLE');
        await expectFirstSlotReady(page, flow);
        const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/game/api/command/action.muster');
        await press(submit, info);
        const request = await sent;
        expect(request.postDataJSON()).toEqual({});
        const query = new URL(request.url()).searchParams;
        expect(query.get('generalId')).toBe(String(GENERAL_ID));
        expect(query.get('turnIdx')).toBe('0');
    });

    test('[action.muster] 집합: 12순 읽기가 늦으면 「부대 모으기」로 연 흐름은 보내지 않고, 읽힌 01순에서 한 번 눌러 한 번 접수를 청한다', { tag: [BOTH] }, async ({ page }, info) => {
        const path = '/api/game/api/command/action.muster';
        const posts = postsTo(page, path);
        let release = () => {};
        const released = new Promise<void>((r) => { release = r; });
        let markHeld = () => {};
        const held = new Promise<void>((r) => { markHeld = r; });
        try {
            // 선택지는 바로 주고 12순 읽기만 붙잡는다 — serve 의 넓은 /api/game/ 대역 뒤 · 이동 전에 건다.
            await openCorps(page, () => page.route((url) => url.pathname === '/api/game/api/reserved-commands', async (route) => {
                markHeld();
                await released;
                await route.fallback();
            }));
            await press(page.locator('[data-testid="corps-panel"] [data-input-id="action.muster"]'), info);
            await expect(page).toHaveURL(/[?&]do=action\.muster\b/);
            const flow = page.getByTestId('command-flow');
            await expect(flow).toBeVisible({ timeout: 60_000 });
            await held;
            const submit = flow.locator('[data-input-id="action.muster"][data-input-status]');
            await expect(submit).toHaveAttribute('data-input-status', 'AVAILABLE');
            await expect(flow.getByRole('status', { name: '12순을 불러오는 중' })).toBeVisible();
            await expect(page).not.toHaveURL(/[?&]slot=/);
            // 확인 전 한 번 — 막혀야 한다(다시 누르기가 아니다).
            await press(submit, info);
            await expect(flow.getByText('12순을 불러오는 중입니다 — 확인한 뒤 예약해 주세요.')).toBeVisible();
            expect(posts).toEqual([]);
            await expect(page).not.toHaveURL(/[?&]slot=/);

            release();
            await expectFirstSlotReady(page, flow);
            const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === path);
            await press(submit, info);
            const request = await sent;
            expect(request.postDataJSON()).toEqual({});
            const query = new URL(request.url()).searchParams;
            expect(query.get('generalId')).toBe(String(GENERAL_ID));
            expect(query.get('turnIdx')).toBe('0');
            await expect(flow.getByText('「집합」 — 01순에 예약했습니다.')).toBeVisible();
            expect(posts).toEqual([path]);
        } finally {
            release();
        }
    });

    test('[action.deploy] 출병: 군단 화면 「출병」 앵커 → 흐름 → 부곡 · 목적지 → 접수를 청한다', { tag: [BOTH] }, async ({ page }, info) => {
        await openCorps(page);
        const open = page.locator('[data-testid="corps-panel"] [data-input-id="action.deploy"]');
        await press(open, info);
        await expect(page).toHaveURL(/[?&]do=action\.deploy\b/);
        const flow = page.getByTestId('command-flow');
        await expect(flow).toBeVisible({ timeout: 60_000 });
        await press(flow.getByRole('option', { name: /검증용 부곡/ }).first(), info);
        await press(flow.getByRole('option', { name: /검증용 목적지/ }).first(), info);
        const submit = flow.locator('[data-input-id="action.deploy"][data-input-status]');
        await expect(submit).toHaveAttribute('data-input-status', 'AVAILABLE');
        const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/game/api/command/action.deploy');
        await press(submit, info);
        const request = await sent;
        expect(request.postDataJSON()).toEqual({ bugokIds: [7], destinationProvinceId: 'P-1' });
        expect(new URL(request.url()).searchParams.get('generalId')).toBe(String(GENERAL_ID));
    });
});
