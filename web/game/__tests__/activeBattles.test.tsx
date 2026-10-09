// 내 전투 목록(P-C04, K6-11) — 고정 자료는 C2 활성 목록(#1396 BattleActiveEntry) 모양 그대로이고 이 시험 안에만 있다.
// 해석: worldId · sourceId 는 10진 문자열(숫자도 받되 0 · 음수 · 선행 0 거절) · 정정 표 단계만 이름 · 허위 JOINING 금지 · 장소/양쪽 없으면 null.
// 표시: 단계별 칩 · 입장은 내 부곡이 있을 때만(일기토 제외) · 정렬 JOINING(마감 순) → LIVE → 나머지.
// 끝난 전투(APPLIED)는 활성 목록에 오지 않는다 — ENDED · 리플레이는 이 목록의 일이 아니다(와도 「상태 확인 중」, CEO 10-06 정정).
// 읽기: 정상 빈 배열·인증·권한·HTTP 실패·명시 원천 불가를 구분한다. 빈 배열은 producer 활성화 증거가 아니다.
import { act, fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { expectServerWait } from '@opensamguk/ui';
import { BattleHub } from '../components/battle/BattleHub';
import { fetchGame } from '../lib/api';
import { decodeActiveBattles, kindLabel, rowAction, sortActiveBattles, type ActiveBattleRow } from '../lib/battle/active-list';
import { useActiveBattles } from '../lib/battle/use-active-battles';

vi.mock('../lib/api', () => ({ fetchGame: vi.fn() }));

const KEY = (id: string) => ({ kind: 'RETINUE', sourceId: id });
/** #1396 BattleActiveEntry 한 행 — 장소 · 양쪽 · 리플레이는 아직 null + SOURCE_NOT_AVAILABLE. */
const entry = (over: Record<string, unknown> = {}) => ({
    battleId: '9001', worldId: '7', kind: 'FIELD', sourcePhase: 'JOINING', phase: 'JOINING', joinDeadlineAt: '2026-10-06T01:00:42Z',
    observedAt: '2026-10-06T01:00:00Z', mySeat: { sourceKeys: [KEY('11'), KEY('12')] },
    pacingMode: null, pacingModeUnavailableReason: 'SOURCE_NOT_AVAILABLE', controller: null, controllerUnavailableReason: 'SOURCE_NOT_AVAILABLE',
    place: null, placeUnavailableReason: 'SOURCE_NOT_AVAILABLE', sides: null, sidesUnavailableReason: 'SOURCE_NOT_AVAILABLE',
    replayId: null, replayIdUnavailableReason: 'SOURCE_NOT_AVAILABLE', ...over,
});
const one = (over: Record<string, unknown> = {}) => decodeActiveBattles([entry(over)])![0];

afterEach(() => vi.clearAllMocks());

describe('해석', () => {
    it('#1396 모양 — worldId · sourceId 문자열, 내 부곡 수 · 단계 · 마감, 장소 · 양쪽은 null', () => {
        expect(one()).toEqual({
            battleId: '9001', worldId: '7', kind: 'FIELD', phase: 'JOINING', joinDeadlineAt: Date.parse('2026-10-06T01:00:42Z'),
            seatCount: 2, place: null, sides: null,
        });
    });

    it('숫자 id 도 받아 문자열로, 0 · 음수 · 선행 0 · 빈 값 · 배열 아님 · 내 부곡 칸 없음은 null(목록을 지어내지 않음)', () => {
        expect(one({ worldId: 7, mySeat: { sourceKeys: [KEY('11'), { kind: 'RETINUE', sourceId: 12 }] } })).toMatchObject({ worldId: '7', seatCount: 2 });
        for (const bad of [{ worldId: '0' }, { worldId: '-7' }, { worldId: '07' }, { worldId: '' }, { mySeat: { sourceKeys: [KEY('011')] } }, { mySeat: null }, { battleId: '' }]) {
            expect(decodeActiveBattles([entry(bad)])).toBeNull();
        }
        expect(decodeActiveBattles({})).toBeNull();
    });

    it('활성 목록 단계만 이름 — READY 원문(C2 답 대기) · 이 목록에 오지 않는 ENDED/APPLIED · 모르는 값은 null(상태 확인 중), 마감 없는 JOINING 도 null', () => {
        expect(one({ phase: 'LIVE', joinDeadlineAt: null }).phase).toBe('LIVE');
        expect(one({ phase: 'READY' }).phase).toBeNull();
        expect(one({ phase: 'ENDED' }).phase).toBeNull();
        expect(one({ phase: 'APPLIED' }).phase).toBeNull();
        expect(one({ phase: 'JOINING', joinDeadlineAt: null })).toMatchObject({ phase: null, joinDeadlineAt: null });
        expect(kindLabel('SIEGE')).toBe('공성');
        expect(kindLabel('SOMETHING')).toBe('전투');
    });

    it('장소 · 양쪽 — 문자열(#1396) · {name}/{commanderName}(계약 v2) 둘 다, 이름이 빠지면 null', () => {
        expect(one({ place: '영천 북쪽 구릉', sides: ['조조', '원소'] })).toMatchObject({ place: '영천 북쪽 구릉', sides: ['조조', '원소'] });
        expect(one({ place: { name: '허현' }, sides: [{ commanderName: '하후돈' }, { commanderName: '안량' }] })).toMatchObject({ place: '허현', sides: ['하후돈', '안량'] });
        expect(one({ sides: [{ commanderName: '하후돈' }, { nationId: 3 }] }).sides).toBeNull();
    });
});

describe('표시', () => {
    it('행동 — 입장은 참가 대기 · 진행 중이고 내 부곡이 있을 때만(일기토 제외), 막힘 · 판정 중 · 모르는 단계는 없음', () => {
        expect(rowAction(one())).toBe('enter');
        expect(rowAction(one({ phase: 'LIVE' }))).toBe('enter');
        expect(rowAction(one({ mySeat: { sourceKeys: [] } }))).toBeNull();
        expect(rowAction(one({ kind: 'DUEL' }))).toBeNull();
        expect(rowAction(one({ phase: 'RESULT_BLOCKED' }))).toBeNull();
        expect(rowAction(one({ phase: 'READY' }))).toBeNull();
        expect(rowAction(one({ phase: 'ENDED', replayId: 'R-1' }))).toBeNull();
    });

    it('정렬 — 참가 대기(마감 빠른 순) → 진행 중 → 나머지(받은 차례)', () => {
        const rows = decodeActiveBattles([
            entry({ battleId: 'a', phase: 'RESOLVING' }),
            entry({ battleId: 'b', phase: 'LIVE' }),
            entry({ battleId: 'c', joinDeadlineAt: '2026-10-06T01:05:00Z' }),
            entry({ battleId: 'd', phase: 'QUARANTINED' }),
            entry({ battleId: 'e', joinDeadlineAt: '2026-10-06T01:01:00Z' }),
        ])!;
        expect(sortActiveBattles(rows).map((r) => r.battleId)).toEqual(['e', 'c', 'b', 'a', 'd']);
    });
});

describe('읽기', () => {
    const respond = (status: number, body?: unknown) => vi.mocked(fetchGame).mockResolvedValue(new Response(body === undefined ? null : JSON.stringify(body), { status }));

    it.each([
        [401, {}, { state: 'unauthorized' }],
        [403, {}, { state: 'forbidden' }],
        [404, {}, { state: 'error', errorCode: 'HTTP_404' }],
        [500, {}, { state: 'error', errorCode: 'HTTP_500' }],
        [503, {}, { state: 'error', errorCode: 'HTTP_503' }],
        [503, { error: { code: 'SOURCE_UNAVAILABLE' } }, { state: 'source-unavailable' }],
        [200, [], { state: 'empty' }],
        [200, { error: { code: 'SOURCE_UNAVAILABLE' } }, { state: 'error', errorCode: 'INVALID_RESPONSE' }],
    ])('내 장수 번호로 읽고 HTTP %s와 응답 상태를 구분한다', async (status, body, expected) => {
        respond(status as number, body);
        const { result } = renderHook(() => useActiveBattles(7));
        await waitFor(() => expect(result.current).toMatchObject(expected));
        expect(vi.mocked(fetchGame).mock.calls[0][0]).toBe('/api/battles/active?generalId=7');
    });

    it('망 오류·JSON 형식 오류를 구분하고 다시 읽기로 정상 빈 응답을 확인한다', async () => {
        vi.mocked(fetchGame).mockRejectedValueOnce(new TypeError('fetch failed'));
        const { result } = renderHook(() => useActiveBattles(7));
        await waitFor(() => expect(result.current).toMatchObject({ state: 'error', errorCode: 'NETWORK_ERROR' }));
        vi.mocked(fetchGame).mockResolvedValueOnce(new Response('<html>not json</html>', { status: 200 }));
        act(() => (result.current as Extract<typeof result.current, { state: 'error' }>).onRetry());
        await waitFor(() => expect(result.current).toMatchObject({ state: 'error', errorCode: 'INVALID_RESPONSE' }));
        respond(200, []);
        act(() => (result.current as Extract<typeof result.current, { state: 'error' }>).onRetry());
        await waitFor(() => expect(result.current.state).toBe('empty'));
    });

    it('장수가 바뀐 뒤 늦게 온 응답은 새 장수의 인증 상태를 덮지 않는다', async () => {
        let finish: (response: Response) => void = () => { throw new Error('request not started'); };
        vi.mocked(fetchGame).mockImplementationOnce(() => new Promise((resolve) => { finish = resolve; }));
        const { result, rerender } = renderHook(({ id }) => useActiveBattles(id), { initialProps: { id: 7 } });
        respond(401, {});
        rerender({ id: 8 });
        await waitFor(() => expect(result.current.state).toBe('unauthorized'));
        await act(async () => finish(new Response('[]', { status: 200 })));
        expect(result.current.state).toBe('unauthorized');
    });

    it('행이 오면 정렬해 ready, 모양이 틀리면 error(다시 시도로 다시 읽음), 장수가 없으면 읽지 않음', async () => {
        respond(200, [entry({ battleId: 'live', phase: 'LIVE' }), entry({ battleId: 'join' })]);
        const a = renderHook(() => useActiveBattles(7));
        await waitFor(() => expect(a.result.current.state).toBe('ready'));
        const ready = a.result.current as Extract<typeof a.result.current, { state: 'ready' }>;
        expect(ready.rows.map((r) => r.battleId)).toEqual(['join', 'live']);
        a.unmount();
        respond(200, [{ battleId: '9001' }]);
        const b = renderHook(() => useActiveBattles(7));
        await waitFor(() => expect(b.result.current.state).toBe('error'));
        respond(200, [entry()]);
        act(() => (b.result.current as Extract<typeof b.result.current, { state: 'error' }>).onRetry());
        await waitFor(() => expect(b.result.current.state).toBe('ready'));
        b.unmount();
        vi.mocked(fetchGame).mockClear();
        renderHook(() => useActiveBattles(null));
        expect(fetchGame).not.toHaveBeenCalled();
    });
});

describe('허브의 내 전투', () => {
    const absence = { state: 'loading' } as const;
    const rows = (list: Record<string, unknown>[]): ActiveBattleRow[] => sortActiveBattles(decodeActiveBattles(list)!);

    it('읽기 상태가 없으면 확인 전 상태이고 서버의 전투 미구현을 단정하지 않는다', () => {
        const { container } = render(<BattleHub absence={absence} battles={{ state: 'waiting' }} />);
        const battles = within(screen.getByRole('region', { name: '내 전투' }));
        expect(battles.getByText('전투 목록을 아직 확인하지 못했습니다')).toBeInTheDocument();
        expect(battles.queryByText('전투가 열리지 않습니다(서버 준비 중)')).toBeNull();
        expectServerWait(container.querySelector('[aria-label="내 전투"]')!, ['K6-11']);
    });

    it('정상 빈 목록은 조회 결과만 안내하고 다시 읽을 수 있다', () => {
        const onRetry = vi.fn();
        const { container } = render(<BattleHub absence={absence} battles={{ state: 'empty', onRetry }} />);
        const battles = within(screen.getByRole('region', { name: '내 전투' }));
        expect(battles.getByText('조회된 전투가 없습니다')).toBeInTheDocument();
        expect(battles.getByText(/실시간 전투 제공 여부는 아직 확인되지 않았습니다/)).toBeInTheDocument();
        expect(container.querySelector('[data-server-wait="K6-11"]')).toBeNull();
        expect(battles.queryByRole('link', { name: '입장' })).toBeNull();
        fireEvent.click(battles.getByRole('button', { name: '다시 읽기' }));
        expect(onRetry).toHaveBeenCalledOnce();
    });

    it.each([
        ['unauthorized', '전투 목록을 보려면 로그인해 주세요'],
        ['forbidden', '이 장수의 전투 목록을 볼 권한이 없습니다'],
    ] as const)('인증·권한 상태 %s는 서버 준비 중이나 빈 목록으로 표시하지 않는다', (state, title) => {
        render(<BattleHub absence={absence} battles={{ state }} />);
        const battles = within(screen.getByRole('region', { name: '내 전투' }));
        expect(battles.getByText(title)).toBeInTheDocument();
        expect(battles.queryByText('조회된 전투가 없습니다')).toBeNull();
        expect(battles.queryByText('전투가 열리지 않습니다(서버 준비 중)')).toBeNull();
    });

    it('명시적 목록 원천 불가는 empty·HTTP 오류·producer 꺼짐과 구분하고 재조회한다', () => {
        const onRetry = vi.fn();
        render(<BattleHub absence={absence} battles={{ state: 'source-unavailable', onRetry }} />);
        const battles = within(screen.getByRole('region', { name: '내 전투' }));
        expect(battles.getByText('전투 목록 원천을 사용할 수 없습니다')).toBeInTheDocument();
        expect(battles.getByText(/전투가 없다는 뜻은 아닙니다/)).toBeInTheDocument();
        expect(battles.queryByText('전투 목록을 읽지 못했습니다')).toBeNull();
        expect(battles.queryByText('조회된 전투가 없습니다')).toBeNull();
        fireEvent.click(battles.getByRole('button', { name: /다시/ }));
        expect(onRetry).toHaveBeenCalledOnce();
    });

    it('HTTP 실패 안내에는 모양 불일치 사유를 지어내지 않는다', () => {
        render(<BattleHub absence={absence} battles={{ state: 'error', errorCode: 'HTTP_500', onRetry: vi.fn() }} />);
        expect(screen.getByText('전투 목록을 읽지 못했습니다')).toBeInTheDocument();
        expect(screen.getByText(/전투 목록 요청이 실패했습니다/)).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '오류 번호 HTTP_500 복사' })).toBeInTheDocument();
        expect(screen.queryByText(/모양이 약속과 다릅니다/)).toBeNull();
    });

    it('행 — 종류 · 장소/양쪽 서버 대기 · 내 부곡 n개 · 단계 칩 · 참가 대기 남은 시간(약) · 입장은 방 주소, 막힘은 성공처럼 보이지 않음', () => {
        vi.useFakeTimers({ now: Date.parse('2026-10-06T01:00:00Z') });
        try {
            const { container } = render(<BattleHub absence={absence} roomBase="/game/pep/corps/battle" battles={{
                state: 'ready', onRetry: vi.fn(), rows: rows([
                    entry({ battleId: 'blocked', phase: 'RESULT_BLOCKED', joinDeadlineAt: null }),
                    entry({ battleId: '9001' }),
                    entry({ battleId: 'ready', phase: 'READY', joinDeadlineAt: null }),
                ]),
            }} />);
            const items = within(screen.getByRole('list', { name: '내 전투 목록' })).getAllByRole('listitem');
            expect(items).toHaveLength(3);
            const [joining, blocked, ready] = items;
            expect(within(joining).getByText('야전')).toBeInTheDocument();
            expect(within(joining).getByText('내 부곡 2개')).toBeInTheDocument();
            expect(within(joining).getByText('참가 대기')).toBeInTheDocument();
            expect(within(joining).getByRole('timer', { name: '개전까지 남은 시간' })).toHaveTextContent('약 0:42');
            expect(within(joining).getByRole('link', { name: '입장' })).toHaveAttribute('href', '/game/pep/corps/battle/9001?world=7');
            expect(within(blocked).getByText('결과 반영이 막힘 — 운영 확인 중')).toBeInTheDocument();
            expect(within(blocked).queryByRole('link')).toBeNull();
            expect(within(ready).getByText('상태 확인 중')).toBeInTheDocument();
            expect(within(ready).queryByRole('link')).toBeNull();
            expectServerWait(screen.getByRole('list', { name: '내 전투 목록' }), ['K6-11 · place', 'K6-11 · sides']);
            expect(container.textContent).not.toMatch(/FIELD|READY|RESULT_BLOCKED/);
        } finally {
            vi.useRealTimers();
        }
    });

    it('모양이 틀리면 「전투 목록을 읽지 못했습니다」 + 다시 시도', () => {
        const onRetry = vi.fn();
        render(<BattleHub absence={absence} battles={{ state: 'error', onRetry }} />);
        expect(screen.getByText('전투 목록을 읽지 못했습니다')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
        expect(onRetry).toHaveBeenCalled();
    });
});
