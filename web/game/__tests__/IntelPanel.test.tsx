// 시야 · 첩보(P-C06) — 단계 글자 · 「N순 전 첩보」 일반 문구 · 역정보 표식 없음 · 첩보 단추는 옵션 상태 · 출처는 서버 대기.
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { IntelPanel } from '../components/intel/IntelPanel';
import { __resetHelpCache } from '../lib/help';
import { toIntelView } from '../lib/intel/intel-model';
import type { ScoutOptions, Visibility } from '../lib/campaign-reads';
import HelpLinkScope from '../components/shell/HelpLinkScope';

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }));
vi.mock('next/navigation', () => ({
    usePathname: () => '/game/pep/corps/intel',
    useSearchParams: () => new URLSearchParams(''),
    useRouter: () => router,
}));
/** 도움말 실패 사유 응답 — 없으면 원장에 없는 사유(404). */
const failures = new Map<string, unknown>();
beforeEach(() => {
    __resetHelpCache();
    failures.clear();
    const respond = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
        const m = /\/api\/game\/api\/help\/failures\/([^?]+)\?inputId=(.+)$/.exec(url);
        const hit = m ? failures.get(`${decodeURIComponent(m[1])}@${decodeURIComponent(m[2])}`) : undefined;
        return hit ? respond(200, hit) : respond(404, { error: { code: 'FAILURE_REASON_NOT_FOUND', message: '' } });
    }));
});
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });
/** 막힌 첩보 단추(사유 코드)가 연 도움말 읽기를 act 안에서 끝낸다 — 가짜 fetch 라 마이크로태스크뿐이다. */
const settleHelp = () => act(async () => { await new Promise((r) => setTimeout(r, 0)); });

const vision: Visibility = {
    status: 'READY',
    commanderies: [
        { no: 1, id: 'c1', name: '영천군', tier: 'FULL' },
        { no: 2, id: 'c2', name: '진류군', tier: 'INTEL', ageTurns: 3 },
        { no: 3, id: 'c3', name: '양국', tier: 'FOG' },
        { no: 4, id: 'c4', name: '패국', tier: 'INTEL', ageTurns: 9 },
    ],
};
const scout: ScoutOptions = {
    status: 'READY', available: true,
    options: [
        { no: 2, id: 'c2', name: '진류군', tier: 'INTEL', available: true },
        { no: 3, id: 'c3', name: '양국', tier: 'FOG', available: false, code: 'TOO_FAR', reason: '너무 멉니다' },
    ],
};

describe('시야 모델', () => {
    it('단계별 묶음 · 첩보는 오래된 것부터 · 첩보 후보만 단추', () => {
        const v = toIntelView(vision, scout);
        if (v.state !== 'ready') throw new Error('ready');
        expect(v.groups.map((g) => [g.tier, g.rows.map((r) => r.name)])).toEqual([['INTEL', ['패국', '진류군']], ['FOG', ['양국']], ['FULL', ['영천군']]]);
        expect(v.groups[2].rows[0].scout).toBeNull();
        expect(toIntelView({ status: 'UNAVAILABLE' }, scout)).toEqual({ state: 'unreadable', status: 'UNAVAILABLE' });
    });
});

describe('시야 · 첩보 칸', () => {
    it('「N순 전 첩보」 일반 문구만 — 역정보 표식이 없다', async () => {
        const { container } = render(<IntelPanel load={{ state: 'ready', view: toIntelView(vision, scout), onRetry: vi.fn() }} onScout={vi.fn()} />);
        await settleHelp();
        expect(screen.getByText('3순 전 첩보 · 다시 첩보하면 갱신됩니다')).toBeInTheDocument();
        expect(container.textContent).not.toMatch(/가짜|역정보|의심/);
        // 이 응답에는 sources 키가 없다(옛 서버) — 빈 목록과 다른 문구, 기록 수도 0 으로 두지 않는다.
        const box = within(screen.getByRole('region', { name: '내 시야 출처' }));
        expect(box.getByText('시야 출처를 받지 못했습니다')).toBeInTheDocument();
        expect(box.getByText('읽지 못한 출처 기록 수를 받지 못했습니다.')).toBeInTheDocument();
    });

    it('첩보 단추 — 가능하면 명령 흐름을 열고, 막히면 서버 사유', async () => {
        const onScout = vi.fn();
        render(<IntelPanel load={{ state: 'ready', view: toIntelView(vision, scout), onRetry: vi.fn() }} onScout={onScout} />);
        const intel = within(screen.getByRole('region', { name: '첩보' }));
        fireEvent.click(within(intel.getByText('진류군').closest('li')!).getByRole('button', { name: '첩보' }));
        expect(onScout).toHaveBeenCalledWith(expect.objectContaining({ id: 'c2' }));
        const fog = within(within(screen.getByRole('region', { name: '안 보임' })).getByText('양국').closest('li')!);
        expect(fog.getByRole('button', { name: /첩보/ })).toHaveAttribute('data-input-status', 'BLOCKED');
        expect(fog.getAllByText('너무 멉니다').length).toBeGreaterThan(0);
        await settleHelp();
    });

    it('첩보 자체가 막히면(물 위 등) 모든 첩보 단추가 그 사유로 막힌다', async () => {
        const blocked = { ...scout, available: false, code: 'POSITION_UNAVAILABLE', reason: '장수가 물 위에 있어 첩보할 수 없습니다' };
        render(<IntelPanel load={{ state: 'ready', view: toIntelView(vision, blocked), onRetry: vi.fn() }} onScout={vi.fn()} />);
        await settleHelp();
        expect(screen.getByText('지금은 첩보를 보낼 수 없습니다 — 장수가 물 위에 있어 첩보할 수 없습니다')).toBeInTheDocument();
        const intel = within(screen.getByRole('region', { name: '첩보' }));
        expect(within(intel.getByText('진류군').closest('li')!).getByRole('button', { name: /첩보/ })).toHaveAttribute('data-input-status', 'BLOCKED');
    });

    it('막힌 첩보 단추를 누르면 사유 시트에 「이렇게 하면 됩니다」와 도움말(지금 주소를 두고 서랍)', async () => {
        failures.set('TOO_FAR@action.scout', {
            schemaVersion: 1, reason: 'TOO_FAR', reviewState: 'DRAFT', explanation: '너무 멉니다',
            recoveryAdvice: '이웃한 군으로 먼저 옮긴 뒤 첩보하세요.', relatedTopicIds: [],
        });
        // 서랍을 여는 법은 /game 레이아웃(HelpLinkScope)이 준다(K7 10-03).
        render(<HelpLinkScope><IntelPanel load={{ state: 'ready', view: toIntelView(vision, scout), onRetry: vi.fn() }} onScout={vi.fn()} /></HelpLinkScope>);
        const fog = screen.getByRole('region', { name: '안 보임' });
        fireEvent.click(within(fog).getByRole('button', { name: '첩보' }));
        expect(await screen.findByText('이웃한 군으로 먼저 옮긴 뒤 첩보하세요.')).toBeInTheDocument();
        const click = new MouseEvent('click', { bubbles: true, cancelable: true });
        fireEvent(screen.getByRole('link', { name: /^도움말 — / }), click);
        expect(click.defaultPrevented).toBe(true);
        expect(router.push).toHaveBeenCalledWith('/game/pep/corps/intel?help=input%3Aaction.scout%21TOO_FAR', { scroll: false });
    });
});

// ── 시야 출처(계약 §3–4) ─────────────────────────────────────────────────────
/** 郡國 0 을 포함한 응답 — 0 은 올바른 번호다. */
const visionZero: Visibility = { ...vision, commanderies: [{ no: 0, id: 'PARENT-0000', name: '하남윤', tier: 'FULL' }, ...vision.commanderies!] };
const sixKinds = [
    { kind: 'SELF', commanderyNo: 0, radius: 0, provinceId: '82828', refId: 1 },
    { kind: 'OWN_CORPS', commanderyNo: 1, radius: 0, provinceId: '82829', refId: 31 },
    { kind: 'RETINUE', commanderyNo: 2, radius: 0, refId: 4401 },
    { kind: 'TERRITORY', commanderyNo: 0, radius: 0 },
    { kind: 'SCOUT_POST', commanderyNo: 3, radius: 1, provinceId: '77001', refId: 9 },
    { kind: 'WATCHTOWER_BEACON', commanderyNo: 4, radius: 1, refId: 5501 },
];
const readySources = (v: Visibility) => {
    const view = toIntelView(v, scout);
    if (view.state !== 'ready') throw new Error('ready');
    return view;
};

describe('시야 출처 모델', () => {
    it('여섯 종류 · 郡國 0 도 같은 응답의 이름으로 맞춘다 · 반경은 서버 값 그대로', () => {
        const v = readySources({ ...visionZero, sources: sixKinds, invalidSourceRecords: 0 });
        expect(v.sources).toEqual({
            state: 'listed', malformedRows: 0, rows: [
                { kind: 'SELF', place: '하남윤', radius: 0, malformed: false },
                { kind: 'OWN_CORPS', place: '영천군', radius: 0, malformed: false },
                { kind: 'RETINUE', place: '진류군', radius: 0, malformed: false },
                { kind: 'TERRITORY', place: '하남윤', radius: 0, malformed: false },
                { kind: 'SCOUT_POST', place: '양국', radius: 1, malformed: false },
                { kind: 'WATCHTOWER_BEACON', place: '패국', radius: 1, malformed: false },
            ],
        });
        expect(v.serverInvalidSources).toEqual({ state: 'count', count: 0 });
    });

    it('키 없음 · 빈 목록 · 목록이 아님을 서로 다르게 가른다', () => {
        expect(readySources(vision).sources).toEqual({ state: 'absent' });
        expect(readySources({ ...vision, sources: [] }).sources).toEqual({ state: 'listed', rows: [], malformedRows: 0 });
        for (const bad of [null, {}, 'SELF', 3, { 0: sixKinds[0] }]) {
            expect(readySources({ ...vision, sources: bad }).sources).toEqual({ state: 'malformed' });
        }
    });

    it('알 수 없는 행 · 종류 · 칸은 버리지 않고 「알 수 없음」으로 남기고 따로 센다', () => {
        const v = readySources({ ...visionZero, sources: [
            null, 7, ['SELF'],
            { kind: 'SPY_NET', commanderyNo: 1, radius: 2 },
            { kind: 'self', commanderyNo: 1, radius: 0 },
            { kind: 'SELF', commanderyNo: -1, radius: 0 },
            { kind: 'SELF', commanderyNo: 1.5, radius: 0 },
            { kind: 'SELF', commanderyNo: '0', radius: 0 },
            { kind: 'SELF', commanderyNo: 0, radius: -1 },
            { kind: 'SELF', commanderyNo: 0, radius: 0.5 },
            { kind: 'SELF', commanderyNo: 0, radius: 2_147_483_648 },
            { kind: 'SELF', commanderyNo: 0 },
            { kind: 'SELF', commanderyNo: 0, radius: 0, provinceId: 82828 },
            { kind: 'SELF', commanderyNo: 0, radius: 0, refId: 1.5 },
            { kind: 'SCOUT_POST', commanderyNo: 99, radius: 1 },
        ] });
        if (v.sources.state !== 'listed') throw new Error('listed');
        const unknownRow = { kind: null, place: null, radius: null, malformed: true };
        expect(v.sources.rows).toEqual([
            unknownRow, unknownRow, unknownRow,
            { kind: null, place: '영천군', radius: 2, malformed: true },
            { kind: null, place: '영천군', radius: 0, malformed: true },
            { kind: 'SELF', place: null, radius: 0, malformed: true },
            { kind: 'SELF', place: null, radius: 0, malformed: true },
            { kind: 'SELF', place: null, radius: 0, malformed: true },
            { kind: 'SELF', place: '하남윤', radius: null, malformed: true },
            { kind: 'SELF', place: '하남윤', radius: null, malformed: true },
            { kind: 'SELF', place: '하남윤', radius: null, malformed: true },
            { kind: 'SELF', place: '하남윤', radius: null, malformed: true },
            { kind: 'SELF', place: '하남윤', radius: 0, malformed: true },
            { kind: 'SELF', place: '하남윤', radius: 0, malformed: true },
            // 올바른 번호지만 응답에 그 郡國이 없다 — 위치만 모르고, 행 모양은 맞다.
            { kind: 'SCOUT_POST', place: null, radius: 1, malformed: false },
        ]);
        expect(v.sources.malformedRows).toBe(14);
    });

    it('refId 는 서버 Int? — 없으면 맞고, 있으면 Int 범위 정수만(부호 규칙 없음), 어긋난 행도 버리지 않는다', () => {
        const withRef = (refId: unknown) => ({ kind: 'SELF', commanderyNo: 0, radius: 0, refId });
        const inRange = [0, -1, -2_147_483_648, 2_147_483_647];
        const outOfRange = [2_147_483_648, -2_147_483_649, Number.MAX_SAFE_INTEGER, 1e100];
        // 이 값들은 Number.isInteger 로는 정수다 — 범위로 가려야 한다.
        for (const n of outOfRange) expect(Number.isInteger(n)).toBe(true);
        const notInt = [null, 1.5, '7', Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY];
        const v = readySources({ ...visionZero, sources: [
            { kind: 'SELF', commanderyNo: 0, radius: 0 },
            ...inRange.map(withRef), ...outOfRange.map(withRef), ...notInt.map(withRef),
        ] });
        if (v.sources.state !== 'listed') throw new Error('listed');
        const good = { kind: 'SELF', place: '하남윤', radius: 0, malformed: false };
        const bad = { ...good, malformed: true };
        expect(v.sources.rows).toEqual([
            good,
            ...inRange.map(() => good),
            ...outOfRange.map(() => bad),
            ...notInt.map(() => bad),
        ]);
        expect(v.sources.malformedRows).toBe(outOfRange.length + notInt.length);
    });

    it('서버 기록 수 — 없음 · 잘못된 값은 0 이 아니다, 0 이상 서버 정수만 수', () => {
        const diag = (invalidSourceRecords: unknown) => readySources({ ...vision, sources: [], invalidSourceRecords }).serverInvalidSources;
        expect(readySources({ ...vision, sources: [] }).serverInvalidSources).toEqual({ state: 'missing' });
        for (const bad of [null, -1, 1.5, 2_147_483_648, Number.NaN, Number.POSITIVE_INFINITY, '3', true]) expect(diag(bad)).toEqual({ state: 'invalid' });
        expect(diag(0)).toEqual({ state: 'count', count: 0 });
        expect(diag(3)).toEqual({ state: 'count', count: 3 });
        expect(diag(2_147_483_647)).toEqual({ state: 'count', count: 2_147_483_647 });
    });

    it('출처가 있어도 단계 묶음 · 정렬 · 첩보 후보 · 막힘은 그대로', () => {
        const plain = readySources(visionZero);
        const withSources = readySources({ ...visionZero, sources: [...sixKinds, null], invalidSourceRecords: 5 });
        expect(withSources.groups).toEqual(plain.groups);
        expect(withSources.scoutBlocked).toEqual(plain.scoutBlocked);
        const blocked = { ...scout, available: false, code: 'POSITION_UNAVAILABLE', reason: '물 위' };
        expect(toIntelView({ ...visionZero, sources: sixKinds }, blocked)).toMatchObject({ scoutBlocked: { code: 'POSITION_UNAVAILABLE', reason: '물 위' } });
        expect(toIntelView({ status: 'UNAVAILABLE', sources: sixKinds, invalidSourceRecords: 2 }, scout)).toEqual({ state: 'unreadable', status: 'UNAVAILABLE' });
    });
});

describe('시야 출처 칸', () => {
    const panel = (v: Visibility) => render(<IntelPanel load={{ state: 'ready', view: toIntelView(v, scout), onRetry: vi.fn() }} onScout={vi.fn()} />);
    const box = () => within(screen.getByRole('region', { name: '내 시야 출처' }));

    it('여섯 종류의 이름 · 郡國 이름 · 반경 — 날 id(provinceId · refId · 郡國 id)는 글자에 없다', async () => {
        panel({ ...visionZero, sources: sixKinds, invalidSourceRecords: 0 });
        await settleHelp();
        const items = box().getAllByRole('listitem');
        expect(items.map((li) => li.textContent)).toEqual([
            '내 위치하남윤 · 반경 0칸', '내 군단영천군 · 반경 0칸', '부 인물진류군 · 반경 0칸',
            '우리 세력 영토하남윤 · 반경 0칸', '정찰 배치양국 · 반경 1칸', '망루·봉화패국 · 반경 1칸',
        ]);
        const text = screen.getByRole('region', { name: '내 시야 출처' }).textContent ?? '';
        expect(text).not.toMatch(/82828|82829|77001|4401|5501|PARENT|SELF|TERRITORY/);
        // 기록 수 0 은 줄을 그리지 않는다.
        expect(text).not.toMatch(/읽지 못한 출처 기록/);
        expect(text).not.toMatch(/모양이 어긋난/);
    });

    it('빈 목록 · 목록이 아님 · 키 없음은 서로 다른 글자', async () => {
        const { unmount } = panel({ ...vision, sources: [], invalidSourceRecords: 0 });
        expect(box().getByText('지금 시야를 주는 출처가 없습니다.')).toBeInTheDocument();
        unmount();
        const second = panel({ ...vision, sources: { kind: 'SELF' }, invalidSourceRecords: 0 });
        expect(box().getByText('시야 출처 목록을 읽지 못했습니다 — 출처가 없다는 뜻은 아닙니다.')).toBeInTheDocument();
        expect(box().queryByText('지금 시야를 주는 출처가 없습니다.')).toBeNull();
        second.unmount();
        panel({ ...vision, invalidSourceRecords: 0 });
        await settleHelp();
        expect(box().getByText('시야 출처를 받지 못했습니다')).toBeInTheDocument();
        expect(box().queryByText('지금 시야를 주는 출처가 없습니다.')).toBeNull();
    });

    it('알 수 없는 행은 보이는 채 「알 수 없음」, 화면이 가린 줄 수와 서버 기록 수는 따로', async () => {
        panel({ ...visionZero, sources: [sixKinds[0], null, { kind: 'SPY_NET', commanderyNo: 1, radius: 2, provinceId: '99999' }, { kind: 'SCOUT_POST', commanderyNo: 99, radius: 1 }], invalidSourceRecords: 4 });
        await settleHelp();
        const items = box().getAllByRole('listitem');
        expect(items.map((li) => li.textContent)).toEqual([
            '내 위치하남윤 · 반경 0칸',
            '알 수 없는 출처위치 알 수 없음 · 반경 알 수 없음',
            '알 수 없는 출처영천군 · 반경 2칸',
            '정찰 배치위치 알 수 없음 · 반경 1칸',
        ]);
        expect(items.map((li) => li.getAttribute('data-malformed'))).toEqual([null, 'true', 'true', null]);
        expect(box().getByText('모양이 어긋난 출처 2줄 — 알 수 없는 칸은 그대로 「알 수 없음」으로 둡니다.')).toBeInTheDocument();
        expect(box().getByText('읽지 못한 출처 기록 4개')).toBeInTheDocument();
        expect(screen.getByRole('region', { name: '내 시야 출처' }).textContent).not.toMatch(/SPY_NET|99999/);
    });

    it('Int 범위 밖 refId 행은 종류 · 위치 · 반경을 보인 채 어긋난 줄로 센다 — 날 값은 글자에 없다', async () => {
        const withRef = (refId: number) => ({ kind: 'SELF', commanderyNo: 0, radius: 0, refId });
        panel({ ...visionZero, sources: [withRef(2_147_483_647), withRef(2_147_483_648), withRef(1e100)], invalidSourceRecords: 0 });
        await settleHelp();
        const items = box().getAllByRole('listitem');
        expect(items.map((li) => li.textContent)).toEqual(['내 위치하남윤 · 반경 0칸', '내 위치하남윤 · 반경 0칸', '내 위치하남윤 · 반경 0칸']);
        expect(items.map((li) => li.getAttribute('data-malformed'))).toEqual([null, 'true', 'true']);
        expect(box().getByText('모양이 어긋난 출처 2줄 — 알 수 없는 칸은 그대로 「알 수 없음」으로 둡니다.')).toBeInTheDocument();
        expect(screen.getByRole('region', { name: '내 시야 출처' }).textContent).not.toMatch(/2147483647|2147483648|e\+100/);
    });

    it('서버 기록 수 — 양의 정수만 「읽지 못한 출처 기록 N개」, 없음 · 잘못된 값은 다른 글자', () => {
        const cases: readonly [unknown, string | null][] = [
            [undefined, '읽지 못한 출처 기록 수를 받지 못했습니다.'],
            [null, '읽지 못한 출처 기록 수를 알 수 없습니다.'],
            [-1, '읽지 못한 출처 기록 수를 알 수 없습니다.'],
            [1.5, '읽지 못한 출처 기록 수를 알 수 없습니다.'],
            [2_147_483_648, '읽지 못한 출처 기록 수를 알 수 없습니다.'],
            [0, null],
            [1, '읽지 못한 출처 기록 1개'],
            [12, '읽지 못한 출처 기록 12개'],
        ];
        for (const [count, expected] of cases) {
            const v: Visibility = count === undefined ? { ...vision, sources: [] } : { ...vision, sources: [], invalidSourceRecords: count };
            const { unmount } = panel(v);
            const text = screen.getByRole('region', { name: '내 시야 출처' }).textContent ?? '';
            if (expected) expect(text).toContain(expected);
            expect(text.match(/읽지 못한 출처 기록[^.]*/g) ?? []).toEqual(expected ? [expected.replace(/\.$/, '')] : []);
            unmount();
        }
    });

    it('군이 없어도 READY 면 출처를 그린다', () => {
        panel({ status: 'READY', commanderies: [], sources: [], invalidSourceRecords: 0 });
        expect(screen.getByText('보이는 군이 없습니다')).toBeInTheDocument();
        expect(box().getByText('지금 시야를 주는 출처가 없습니다.')).toBeInTheDocument();
    });

    it('읽는 중 · 실패 · 읽을 수 없음에는 출처 칸이 없다', () => {
        const onRetry = vi.fn();
        const loading = render(<IntelPanel load={{ state: 'loading' }} onScout={vi.fn()} />);
        expect(screen.queryByRole('region', { name: '내 시야 출처' })).toBeNull();
        loading.unmount();
        const failed = render(<IntelPanel load={{ state: 'error', onRetry }} onScout={vi.fn()} />);
        expect(screen.queryByRole('region', { name: '내 시야 출처' })).toBeNull();
        failed.unmount();
        render(<IntelPanel load={{ state: 'ready', view: toIntelView({ status: 'UNAVAILABLE', sources: sixKinds, invalidSourceRecords: 3 }, scout), onRetry }} onScout={vi.fn()} />);
        expect(screen.queryByRole('region', { name: '내 시야 출처' })).toBeNull();
        expect(screen.queryByText(/내 위치|읽지 못한 출처 기록/)).toBeNull();
    });
});
