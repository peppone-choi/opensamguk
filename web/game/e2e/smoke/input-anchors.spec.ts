// 입력 UI 증거(O3, K6 몫) — 명령 흐름(P-W02)의 직접 행동 43개를 하나씩 앵커로 연다(백엔드 없이 합성 자료, command-flow.spec 과 같은 방식).
// 입력마다: `/game?do=<id>` → 흐름 보내기 단추 `[data-input-id="<id>"][data-input-status]` → 인자 고르기 → 누르면
// `POST /api/game/api/command/<id>` 의 경로 · 본문 · 순을 단언한다. 원장 PLANNED(처리기 없음)는 「준비 중」(NOT_DELIVERED)이고
// 눌러도 아무것도 보내지 않는다. 시험 제목의 `[<입력 id>]` 가 원장 evidence `ui-e2e:…#<입력 id>` 가 가리키는 자리다.
// 흐름 밖 K6 화면(입력 도달 표 V31K6InputReach): 외교 세력 줄의 제의 다섯(court.*)과 계책 덱 「걸기」(stratagem.play) — 모두 PLANNED.
// 공성 화면(P-C02)은 K4 화면이라 여기서 다루지 않는다(K6 경로는 흐름).
// 대역 값(사람 · 장소 · 선택지)은 「검증용」으로만 쓴다 — 실제 규칙 수치를 흉내 내지 않는다.
import { expect, test, type Locator, type Page, type Route, type TestInfo } from '@playwright/test';
import { FLOW_COMMANDS } from '../../lib/command-flow/catalog';
import { BOTH, press } from '../support/parity';

const API = '/api/game/api';
const GENERAL_ID = 7;
const PERSON = { generalId: 8, name: '검증용 인물', available: true };

/** 인자 읽기 대역 — 경로(API 뒤) → 본문. */
type Reads = Readonly<Record<string, unknown>>;

interface Case {
    readonly inputId: string;
    readonly reads: Reads;
    /** 흐름에서 차례로 고를 후보(role=option 이름). */
    readonly picks?: readonly RegExp[];
    /** 수량 칸(「얼마나」)에 넣을 값. */
    readonly amount?: number;
    /** 서버로 가야 할 본문. */
    readonly args: Readonly<Record<string, unknown>>;
}

const field = (inputId: string, name: string): Case => ({
    inputId, reads: { [`/commands/${name}-options`]: { inputId, available: true, countyId: 30, countyName: '검증용 현' } }, args: {},
});
const military = (inputId: string, name: string): Case => ({
    inputId, reads: { [`/commands/${name}-options`]: { inputId, available: true, countyName: '검증용 현', troops: 100, troopsAfter: 200 } }, args: {},
});
const travel = (inputId: string, name: string, destinations: boolean): Case => ({
    inputId,
    reads: { [`/commands/${name}-options`]: { inputId, available: true, destinations: destinations ? [{ provinceId: 'P-1', name: '검증용 목적지', available: true }] : [] } },
    picks: destinations ? [/검증용 목적지/] : [],
    args: destinations ? { destinationProvinceId: 'P-1' } : {},
});
const direct = (inputId: string, amount?: number): Case => ({
    inputId,
    reads: { [`/commands/legacy-direct-options?inputId=${inputId}`]: {
        inputId, available: true, choices: [{ label: '검증용 선택지', arguments: { option: 'A' }, available: true, maxAmount: 300 }],
    } },
    picks: [/검증용 선택지/],
    amount,
    args: amount == null ? { option: 'A' } : { option: 'A', amount },
});
const political = [
    { inputId: 'action.foundState', available: true },
    { inputId: 'action.abdicate', available: true, targets: [PERSON] },
    { inputId: 'action.oath', available: true, targets: [PERSON] },
];

/** 처리기가 있는 입력(원장 HANDLER_READY · UI_READY) — 인자 읽기 → 고르기 → 보내기. */
const CASES: readonly Case[] = [
    field('action.farm', 'farm'), field('action.commerce', 'commerce'), field('action.fortify', 'fortify'),
    field('action.repairWall', 'repair-wall'), field('action.security', 'security'), field('action.settle', 'settle'),
    field('action.selectResidents', 'select-residents'), field('action.tour', 'tour'),
    military('action.conscript', 'conscript'), military('action.raiseVolunteers', 'raise-volunteers'), military('action.train', 'train'),
    military('action.boostMorale', 'boost-morale'), military('action.demobilize', 'demobilize'), military('action.muster', 'muster'),
    {
        inputId: 'action.deploy',
        reads: { '/deploy/options': {
            available: true, maxReservedTurns: 12,
            bugoks: [{ id: 7, name: '검증용 부곡', troops: 100, available: true }],
            destinations: [{ provinceId: 'P-1', name: '검증용 목적지' }],
        } },
        picks: [/검증용 부곡/, /검증용 목적지/],
        args: { bugokIds: [7], destinationProvinceId: 'P-1' },
    },
    {
        inputId: 'action.scout',
        reads: { '/scout-options': { status: 'READY', inputId: 'action.scout', available: true, options: [{ no: 1, id: 'C-1', name: '검증용 군', tier: 'FOG', available: true }] } },
        picks: [/검증용 군/],
        args: { commanderyId: 'C-1' },
    },
    ...['action.assault', 'action.demandSurrender'].map((inputId): Case => ({
        inputId,
        reads: { '/sieges': { status: 'READY', sieges: [{ countyId: 30, countyName: '검증용 현', besieger: { generalId: GENERAL_ID }, canAct: true }] } },
        args: {},
    })),
    {
        inputId: 'action.siegeRoadFort',
        reads: { '/road-forts': {
            status: 'READY', roadMode: true, gates: [],
            forts: [{ id: 'F-1', edgeId: 'E-1', provinceId: 'P-1', row: 0, col: 0, ownerNationId: 2, wall: 10, garrison: 10, besiegerGeneralId: null, siegeProgress: 0, canBesiege: true }],
        } },
        picks: [/보루 · P-1/],
        args: { fortId: 'F-1' },
    },
    travel('action.move', 'move', true), travel('action.forcedMarch', 'forced-march', true), travel('action.return', 'return', false),
    { inputId: 'action.search', reads: { '/commands/search-options': { inputId: 'action.search', available: true, undiscoveredCount: 2, targets: [] } }, args: {} },
    {
        inputId: 'action.employ',
        reads: { '/commands/employ-options': { inputId: 'action.employ', available: true, targets: [PERSON] } },
        picks: [/검증용 인물/],
        args: { targetGeneralId: 8 },
    },
    { inputId: 'action.travel', reads: { '/commands/travel-options': { inputId: 'action.travel', available: true } }, args: {} },
    {
        inputId: 'action.selfTrain',
        reads: { '/commands/self-train-options': { inputId: 'action.selfTrain', available: true, stats: [{ stat: 'strength', available: true }] } },
        picks: [/무력/],
        args: { stat: 'strength' },
    },
    { inputId: 'action.recuperate', reads: { '/commands/recuperate-options': { inputId: 'action.recuperate', available: true } }, args: {} },
    direct('action.convertProficiency'),
    {
        inputId: 'action.enlist',
        reads: { '/commands/enlistment-options': {
            result: true, inputId: 'action.enlist', maxReservedTurns: 12,
            options: [{ mode: 'GENERAL', targetId: 8, label: '검증용 주공', availability: { status: 'AVAILABLE' } }],
        } },
        picks: [/검증용 주공/],
        args: { mode: 'GENERAL', targetId: 8 },
    },
    { inputId: 'action.foundState', reads: { '/commands/political-options': political }, args: {} },
    { inputId: 'action.abdicate', reads: { '/commands/political-options': political }, picks: [/검증용 인물/], args: { targetGeneralId: 8 } },
    { inputId: 'action.oath', reads: { '/commands/political-options': political }, picks: [/검증용 인물/], args: { targetGeneralId: 8 } },
    {
        inputId: 'action.gift',
        reads: { '/commands/gift-options': {
            inputId: 'action.gift', available: true,
            targets: [PERSON], resources: [{ resource: 'MONEY', available: true, maxAmount: 300 }],
        } },
        picks: [/검증용 인물/, /^금/],
        amount: 100,
        args: { targetGeneralId: 8, resource: 'MONEY', amount: 100 },
    },
    direct('action.tradeGrain'),
    direct('action.transport', 100),
];

interface Server { commands: { inputId: string; turnIdx: number; generalId: string | null; args: unknown }[] }

async function serve(page: Page, server: Server, reads: Reads) {
    const json = (route: Route, status: number, body: unknown) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
    await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
    await page.route((url) => url.pathname.startsWith('/api/game/'), async (route) => {
        const url = new URL(route.request().url());
        const path = url.pathname.slice(API.length);
        if (url.pathname.endsWith('/front-info')) {
            return json(route, 200, {
                result: true,
                global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
                general: { hasGeneral: true, generalId: GENERAL_ID, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
                nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
            });
        }
        if (path === '/reserved-commands') return json(route, 200, { result: true, generalId: GENERAL_ID, slots: [] });
        const inputQuery = url.searchParams.get('inputId');
        const read = reads[inputQuery ? `${path}?inputId=${inputQuery}` : path];
        if (read !== undefined && route.request().method() === 'GET') return json(route, 200, read);
        if (path.startsWith('/command/action.') && route.request().method() === 'POST') {
            server.commands.push({
                inputId: path.slice('/command/'.length), turnIdx: Number(url.searchParams.get('turnIdx')),
                generalId: url.searchParams.get('generalId'), args: route.request().postDataJSON(),
            });
            return json(route, 202, { status: 'AVAILABLE', requestId: 'r-1', turnIdx: 0 });
        }
        if (path === '/command/result/r-1') {
            return json(route, 200, { status: 'RESOLVED', requestId: 'r-1', ok: true, type: 'reservationAccepted', result: { commandKind: 'RESERVED_TURN' } });
        }
        return json(route, 503, {});
    });
}

const flow = (page: Page) => page.getByTestId('command-flow');
const submitOf = (page: Page, inputId: string) => flow(page).locator(`[data-input-id="${inputId}"][data-input-status]`);

async function openInput(page: Page, server: Server, inputId: string, reads: Reads) {
    await serve(page, server, reads);
    await page.goto(`/game?do=${inputId}`, { waitUntil: 'domcontentloaded' });
    await expect(flow(page)).toBeVisible({ timeout: 60_000 });
    await expect(flow(page).locator('[data-turn-idx="0"]')).toHaveAttribute('aria-pressed', 'true');
}

test('흐름 직접 행동 표와 이 스펙의 입력이 한 줄도 빠지지 않는다', () => {
    const planned = FLOW_COMMANDS.filter((c) => c.delivery === 'PLANNED').map((c) => c.inputId);
    const covered = [...CASES.map((c) => c.inputId), ...planned];
    expect([...covered].sort()).toEqual(FLOW_COMMANDS.map((c) => c.inputId).sort());
    expect(new Set(covered).size).toBe(covered.length);
    for (const c of CASES) expect(FLOW_COMMANDS.find((f) => f.inputId === c.inputId)?.delivery).not.toBe('PLANNED');
});

test.describe('입력 앵커 — 명령 흐름', () => {
    for (const c of CASES) {
        const name = FLOW_COMMANDS.find((f) => f.inputId === c.inputId)!.name;
        test(`[${c.inputId}] ${name}: 흐름 앵커에서 고르고 보내면 그 입력 · 순 · 인자로 접수를 청한다`, { tag: [BOTH] }, async ({ page }, info) => {
            const server: Server = { commands: [] };
            await openInput(page, server, c.inputId, c.reads);
            for (const pick of c.picks ?? []) await press(flow(page).getByRole('option', { name: pick }).first(), info);
            if (c.amount != null) await flow(page).getByRole('spinbutton', { name: '얼마나' }).fill(String(c.amount));
            const submit = submitOf(page, c.inputId);
            await expect(submit).toHaveAttribute('data-input-status', 'AVAILABLE');
            await expect(submit).toHaveText('01순에 예약');
            const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === `${API}/command/${c.inputId}`);
            await press(submit, info);
            await sent;
            await expect(flow(page).getByText(`「${name}」 — 01순에 예약했습니다.`)).toBeVisible();
            expect(server.commands).toEqual([{ inputId: c.inputId, turnIdx: 0, generalId: String(GENERAL_ID), args: c.args }]);
        });
    }

    for (const c of FLOW_COMMANDS.filter((f) => f.delivery === 'PLANNED')) {
        test(`[${c.inputId}] ${c.name}: 원장 PLANNED — 「준비 중」이고 눌러도 보내지 않는다`, { tag: [BOTH] }, async ({ page }, info) => {
            const server: Server = { commands: [] };
            await openInput(page, server, c.inputId, {});
            await expect(flow(page).getByText('아직 열리지 않은 명령입니다')).toBeVisible();
            const submit = submitOf(page, c.inputId);
            await expect(submit).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
            await press(submit, info);
            const sheet = page.getByRole('dialog', { name: `${c.name} — 지금은 할 수 없습니다` });
            await expect(sheet).toBeVisible();
            await expect(sheet).toContainText('준비 중');
            expect(server.commands).toEqual([]);
        });
    }
});

/** 이 입력으로 가는 POST(`…/command/court.x` · `…/commands/court/x` 둘 다) — 「준비 중」 입력은 0이어야 한다. */
function postsFor(page: Page, inputId: string): string[] {
    const sent: string[] = [];
    const slash = inputId.replace('.', '/');
    page.on('request', (r) => {
        const path = new URL(r.url()).pathname;
        if (r.method() === 'POST' && (path.endsWith(`/${inputId}`) || path.endsWith(`/${slash}`))) sent.push(path);
    });
    return sent;
}

/** 원장 PLANNED 화면 단추: 「준비 중」 → 누르면 그 사유 시트 → 아무것도 보내지 않는다. */
async function expectPlannedAction(page: Page, info: TestInfo, action: Locator, label: string, reasonTitle: string, sent: string[]) {
    await expect(action).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
    await expect(action).toHaveText(label);
    // 모바일 하단 탭(sticky)이 화면 맨 아래를 덮는다 — stratagem.spec 과 같이 가운데로 올린 뒤 누른다.
    await action.evaluate((el) => el.scrollIntoView({ block: 'center' }));
    await press(action, info);
    const sheet = page.getByRole('dialog', { name: reasonTitle });
    await expect(sheet).toBeVisible();
    await expect(sheet).toContainText('준비 중');
    expect(sent).toEqual([]);
}

const nation = (id: number, name: string, color: string) => ({ nation: id, name, color, type: '', level: 1, capital: 0, gennum: 1, cities: [], power: 0 });
// 내 세력(1)과의 관계: 원소 교전(0) · 유표 불가침(7) · 손책 관계 없음(2) · 원술 선전포고 유예(1).
const DIPLOMACY: Reads = {
    '/diplomacy/conflict': {
        result: true, conflict: [], myNationID: 1,
        nations: [nation(1, '조조', '#4f7fbf'), nation(2, '원소', '#b04a3c'), nation(3, '유표', '#4f8f5a'), nation(4, '손책', '#b9b2a3'), nation(5, '원술', '#9a7a3a')],
        diplomacyList: { 1: { 2: 0, 3: 7, 4: 2, 5: 1 } },
    },
};
/** [입력, 단추 이름(승인 보드 V31K6InputReach), 그 제의가 보이는 세력]. */
const DIPLOMACY_CASES = [
    ['court.diplomacy', '원조', 4],
    ['court.nonAggression', '불가침 제의', 4],
    ['court.declareWar', '선전포고', 4],
    ['court.offerPeace', '종전 제의', 2],
    ['court.breakNonAggression', '불가침 파기', 3],
] as const;

test.describe('입력 앵커 — 외교 · 계책 화면', () => {
    for (const [inputId, label, nationId] of DIPLOMACY_CASES) {
        test(`[${inputId}] ${label}: 외교 세력 줄 — 원장 PLANNED라 「준비 중」이고 눌러도 보내지 않는다`, { tag: [BOTH] }, async ({ page }, info) => {
            const sent = postsFor(page, inputId);
            await serve(page, { commands: [] }, DIPLOMACY);
            await page.goto('/game/global-diplomacy', { waitUntil: 'domcontentloaded' });
            const list = page.getByRole('list', { name: '세력별 관계' });
            await expect(list).toBeVisible({ timeout: 60_000 });
            const action = list.locator(`li[data-nation-id="${nationId}"] [data-input-id="${inputId}"][data-input-status]`);
            await expectPlannedAction(page, info, action, label, `${label} — 아직 열리지 않았습니다`, sent);
        });
    }

    test('[stratagem.play] 손패 카드 쓰기: 계책 덱 「걸기」 — 원장 PLANNED라 「준비 중」이고 눌러도 보내지 않는다', { tag: [BOTH] }, async ({ page }, info) => {
        const sent = postsFor(page, 'stratagem.play');
        await serve(page, { commands: [] }, {
            '/commands/stratagem-hand': { status: 'READY', handLimit: 5, canUse: false, cards: [{ instanceId: 1, type: 'INSIGHT', label: '간파' }] },
        });
        await page.goto('/game/stratagem', { waitUntil: 'domcontentloaded' });
        await expect(page.getByRole('listbox', { name: '손패 카드' })).toBeVisible({ timeout: 60_000 });
        const action = page.locator('[data-input-id="stratagem.play"][data-input-status]');
        await expect(action).toHaveCount(1);
        await expectPlannedAction(page, info, action, '간파 — 대응 칸에 걸기', '계책 쓰기 — 아직 열리지 않았습니다', sent);
    });
});
