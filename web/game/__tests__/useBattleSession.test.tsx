// 전투 세션 훅(P-C03) — join-ticket · WS 는 흉내(고정 자료는 이 시험 안에만). 옮기기는 하나씩 · 받아들인 것만 적용 ·
// 배치 · 지휘권 · 회차가 서버에서 먼저 바뀐 거절(STALE_*)은 새 티켓 · 새 접속으로 SNAPSHOT 을 다시 받아 기대 값 · 자리를 맞춘다(그동안 옮기기 막음) ·
// 그 밖의 거절은 사유만 보이고 다시 접속하지 않는다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchGame } from '../lib/api';
import { useBattleSession } from '../lib/battle/use-battle-session';

vi.mock('../lib/api', () => ({ fetchGame: vi.fn() }));

class FakeSocket {
    static readonly OPEN = 1;
    static instances: FakeSocket[] = [];
    readyState = 1;
    readonly sent: Record<string, unknown>[] = [];
    onopen: (() => void) | null = null;
    onmessage: ((event: { data: string }) => void) | null = null;
    onclose: (() => void) | null = null;
    constructor(readonly url: string, readonly protocols: string[]) {
        FakeSocket.instances.push(this);
    }
    send(data: string) {
        this.sent.push(JSON.parse(data) as Record<string, unknown>);
    }
    close() {
        this.readyState = 3;
    }
    receive(frame: unknown) {
        act(() => this.onmessage?.({ data: JSON.stringify(frame) }));
    }
}

const R = (sourceId: number) => ({ kind: 'RETINUE', sourceId });
const own = (r11: { row: number; col: number }) => [
    { sourceKey: R(11), cell: r11 }, { sourceKey: R(12), cell: { row: 31, col: 10 } },
];
const snapshot = (revision: string, r11 = { row: 30, col: 10 }) => ({
    schemaVersion: 2, t: 'SNAPSHOT', battleId: '9001', sessionEpoch: '3', worldId: 7, tick: 0, eventSeq: '12', pacingMode: 'REALTIME',
    joinDeadlineAt: null, authorityRevision: '5', field: { boardId: 4, kind: 'FIELD' },
    units: [
        { sourceKey: R(11), ownerGeneralId: 7, cell: { row: 30, col: 10 }, troops: 800, morale: 100 },
        { sourceKey: R(12), ownerGeneralId: 7, cell: { row: 31, col: 10 }, troops: 600, morale: 90 },
    ],
    deployment: {
        revision, defaultPinned: false, remainingMillis: 42_000,
        allowedCells: [{ row: 30, col: 10 }, { row: 31, col: 10 }, { row: 31, col: 12 }, { row: 33, col: 12 }], ownPositions: own(r11),
    },
    environment: { weather: null, night: null, season: null, objective: null, unavailableReason: 'SOURCE_NOT_PINNED' },
});
const reject = (clientCommandId: unknown, reasonCode: string) => ({
    schemaVersion: 2, t: 'ACK', battleId: '9001', sessionEpoch: '3', clientCommandId, inputId: 'battle.deployment_move', replayed: false,
    receipt: { verdict: 'REJECTED', reasonCode, receiptSessionEpoch: '3', receiptAuthorityRevision: '5', serverTick: 0 },
});

beforeEach(() => {
    FakeSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeSocket);
    vi.mocked(fetchGame).mockImplementation(async () => new Response(JSON.stringify({ joinTicket: 'BTJ2.test' }), { status: 200 }));
});

afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
});

async function connected() {
    const hook = renderHook(() => useBattleSession('pep', 7, '9001'));
    await waitFor(() => expect(FakeSocket.instances).toHaveLength(1));
    const ws = FakeSocket.instances[0];
    ws.receive(snapshot('0'));
    expect(hook.result.current.session.state).toBe('ready');
    return { hook, ws };
}

const cellOf = (hook: Awaited<ReturnType<typeof connected>>['hook'], id: string) => {
    const s = hook.result.current.session;
    return s.state === 'ready' ? s.view.units.find((u) => u.id === id)?.cell : undefined;
};

describe('전투 세션 — 거절 뒤', () => {
    it('STALE_DEPLOYMENT — 판은 그대로 두고 옮기기를 막은 채 새 티켓 · 새 접속, 새 SNAPSHOT 이 오면 자리 · 기대 값을 맞추고 「다시 맞췄다」', async () => {
        const { hook, ws } = await connected();
        act(() => hook.result.current.move('RETINUE:11', { row: 33, col: 12 }));
        expect(ws.sent).toHaveLength(1);
        ws.receive(reject(ws.sent[0].clientCommandId, 'STALE_DEPLOYMENT'));
        expect(hook.result.current.session).toMatchObject({ state: 'ready', pending: null, notice: { kind: 'resyncing', code: 'STALE_DEPLOYMENT' } });
        await waitFor(() => expect(FakeSocket.instances).toHaveLength(2));
        expect(fetchGame).toHaveBeenCalledTimes(2);
        expect(ws.readyState).toBe(3);
        // 다시 받는 동안은 옮기기를 보내지 않는다(옛 기대 값으로 또 거절당하지 않게).
        const ws2 = FakeSocket.instances[1];
        act(() => hook.result.current.move('RETINUE:11', { row: 33, col: 12 }));
        expect([...ws.sent, ...ws2.sent]).toHaveLength(1);
        ws2.receive(snapshot('2', { row: 31, col: 12 }));
        expect(hook.result.current.session).toMatchObject({ state: 'ready', notice: { kind: 'resynced', code: 'STALE_DEPLOYMENT' } });
        expect(cellOf(hook, 'RETINUE:11')).toEqual({ row: 31, col: 12 });
        act(() => hook.result.current.move('RETINUE:11', { row: 33, col: 12 }));
        expect(ws2.sent).toHaveLength(1);
        expect(ws2.sent[0]).toMatchObject({ t: 'DEPLOYMENT_MOVE', expectedDeploymentRevision: '2', targetCell: { row: 33, col: 12 } });
    });

    it('실시간 명령 STALE_AUTHORITY — 명령도 막은 채 새 접속, 새 SNAPSHOT 의 지휘권 revision 으로 다시 명령', async () => {
        const hook = renderHook(() => useBattleSession('pep', 7, '9001'));
        await waitFor(() => expect(FakeSocket.instances).toHaveLength(1));
        const ws = FakeSocket.instances[0];
        ws.receive({ ...snapshot('0'), deployment: null });
        act(() => hook.result.current.command({ sourceKeys: [{ kind: 'RETINUE' as const, sourceId: 11 }] }, 1, 'CHARGE', 'HOME'));
        expect(ws.sent).toHaveLength(1);
        ws.receive(reject(ws.sent[0].clientCommandId, 'STALE_AUTHORITY'));
        expect(hook.result.current.session).toMatchObject({ state: 'ready', pendingCommand: null, notice: { kind: 'resyncing', code: 'STALE_AUTHORITY' } });
        await waitFor(() => expect(FakeSocket.instances).toHaveLength(2));
        const ws2 = FakeSocket.instances[1];
        act(() => hook.result.current.command({ sourceKeys: [{ kind: 'RETINUE' as const, sourceId: 11 }] }, 1, 'CHARGE', 'HOME'));
        expect(ws2.sent).toHaveLength(0);
        ws2.receive({ ...snapshot('0'), deployment: null, authorityRevision: '9' });
        expect(hook.result.current.session).toMatchObject({ notice: { kind: 'resynced', code: 'STALE_AUTHORITY' } });
        act(() => hook.result.current.command({ sourceKeys: [{ kind: 'RETINUE' as const, sourceId: 11 }] }, 1, 'CHARGE', 'HOME'));
        expect(ws2.sent[0]).toMatchObject({ t: 'COMMAND', expectedAuthorityRevision: '9' });
    });

    it('INVALID_SPAWN — 사유만 보이고 다시 접속하지 않는다, 자리는 그대로', async () => {
        const { hook, ws } = await connected();
        act(() => hook.result.current.move('RETINUE:11', { row: 33, col: 12 }));
        ws.receive(reject(ws.sent[0].clientCommandId, 'INVALID_SPAWN'));
        expect(hook.result.current.session).toMatchObject({ state: 'ready', pending: null, notice: { kind: 'rejected', code: 'INVALID_SPAWN' } });
        expect(cellOf(hook, 'RETINUE:11')).toEqual({ row: 30, col: 10 });
        await new Promise((r) => setTimeout(r, 50));
        expect(FakeSocket.instances).toHaveLength(1);
        expect(fetchGame).toHaveBeenCalledTimes(1);
    });
});

const producerSnapshot = readFileSync(resolve(__dirname, '../../../app/game-api/src/test/resources/battle/v2-joining-snapshot.json'), 'utf8');
const invalidProducerSnapshot = JSON.stringify({ ...JSON.parse(producerSnapshot), ownUnits: null });
const receiveText = (socket: FakeSocket, data: string) => act(() => socket.onmessage?.({ data }));
const assertNoInput = (hook: Awaited<ReturnType<typeof connected>>['hook'], socket: FakeSocket) => {
    act(() => {
        hook.result.current.move('RETINUE:11', { row: 33, col: 12 });
        hook.result.current.command({ allMine: true }, 1, 'CHARGE', 'HOME');
    });
    expect(socket.sent).toEqual([]);
};

describe('전투 세션 — 읽을 수 없는 서버 자료', () => {
    it('필수 ownUnits가 손상된 실제 JOINING 형식은 대기를 끝내고 입력을 보내지 않는다', async () => {
        const hook = renderHook(() => useBattleSession('pep', 1, 'battle-v2'));
        await waitFor(() => expect(FakeSocket.instances).toHaveLength(1));
        const socket = FakeSocket.instances[0];
        act(() => socket.onopen?.());
        receiveText(socket, invalidProducerSnapshot);
        expect(hook.result.current.session).toEqual({ state: 'protocol-error' });
        expect(socket.readyState).toBe(3);
        assertNoInput(hook, socket);
        // 닫힘 또는 늦은 정상 프레임으로 원인을 덮거나 입력을 다시 열지 않는다.
        act(() => socket.onclose?.());
        socket.receive(snapshot('0'));
        expect(hook.result.current.session).toEqual({ state: 'protocol-error' });
        assertNoInput(hook, socket);
    });

    it.each([
        ['JSON', '{synthetic-invalid-json'],
        ['head', JSON.stringify({ ...JSON.parse(snapshotFrameForTest()), worldId: null })],
        ['unit', JSON.stringify({ ...JSON.parse(snapshotFrameForTest()), units: [{ sourceKey: R(11) }] })],
    ])('연결 중 malformed %s 자료는 실패 상태로 닫는다', async (_label, text) => {
        const hook = renderHook(() => useBattleSession('pep', 7, '9001'));
        await waitFor(() => expect(FakeSocket.instances).toHaveLength(1));
        const socket = FakeSocket.instances[0];
        receiveText(socket, text);
        expect(hook.result.current.session).toEqual({ state: 'protocol-error' });
        assertNoInput(hook, socket);
    });

    it('ready 뒤 손상된 자료도 입력을 차단하고 오류 뒤 close는 원인을 보존한다', async () => {
        const { hook, ws } = await connected();
        receiveText(ws, '{synthetic-invalid-json');
        expect(hook.result.current.session).toEqual({ state: 'protocol-error' });
        act(() => ws.onclose?.());
        expect(hook.result.current.session).toEqual({ state: 'protocol-error' });
        assertNoInput(hook, ws);
    });

    it('사람이 재시도하면 새 티켓·접속으로 읽고 이전 연결의 오류/close는 무시한다', async () => {
        const hook = renderHook(() => useBattleSession('pep', 7, '9001'));
        await waitFor(() => expect(FakeSocket.instances).toHaveLength(1));
        const old = FakeSocket.instances[0];
        receiveText(old, invalidProducerSnapshot);
        act(() => hook.result.current.retry());
        expect(hook.result.current.session).toEqual({ state: 'connecting' });
        await waitFor(() => expect(FakeSocket.instances).toHaveLength(2));
        expect(fetchGame).toHaveBeenCalledTimes(2);
        const fresh = FakeSocket.instances[1];
        receiveText(old, '{synthetic-invalid-json');
        act(() => old.onclose?.());
        expect(hook.result.current.session).toEqual({ state: 'connecting' });
        fresh.receive(snapshot('0'));
        expect(hook.result.current.session.state).toBe('ready');
        expect([...old.sent, ...fresh.sent]).toEqual([]);
    });

    it('세션 교체로 취소한 옛 socket의 자료와 close는 새 세션에 영향을 주지 않는다', async () => {
        const hook = renderHook(({ id }) => useBattleSession('pep', 7, id), { initialProps: { id: 'old' } });
        await waitFor(() => expect(FakeSocket.instances).toHaveLength(1));
        const old = FakeSocket.instances[0];
        hook.rerender({ id: 'fresh' });
        await waitFor(() => expect(FakeSocket.instances).toHaveLength(2));
        receiveText(old, producerSnapshot);
        act(() => old.onclose?.());
        expect(hook.result.current.session).toEqual({ state: 'connecting' });
        FakeSocket.instances[1].receive(snapshot('0'));
        expect(hook.result.current.session.state).toBe('ready');
        expect([...old.sent, ...FakeSocket.instances[1].sent]).toEqual([]);
        hook.unmount();
        receiveText(FakeSocket.instances[1], '{synthetic-invalid-json');
        expect(FakeSocket.instances[1].readyState).toBe(3);
    });

    it('모르는 유효 frame은 무시하고 decoder 오류로 바꾸지 않는다', async () => {
        const hook = renderHook(() => useBattleSession('pep', 7, '9001'));
        await waitFor(() => expect(FakeSocket.instances).toHaveLength(1));
        const socket = FakeSocket.instances[0];
        socket.receive({ schemaVersion: 2, t: 'FUTURE_FRAME', battleId: '9001', sessionEpoch: '3' });
        expect(hook.result.current.session).toEqual({ state: 'connecting' });
        expect(socket.readyState).toBe(1);
        socket.receive(snapshot('0'));
        expect(hook.result.current.session.state).toBe('ready');
    });

    it('NO_WORLD는 접속하지 않고 티켓 인증/원천 실패는 기존 TICKET 의미를 유지한다', async () => {
        const none = renderHook(() => useBattleSession('pep', null, '9001'));
        expect(none.result.current.session).toEqual({ state: 'unavailable', reason: 'NO_WORLD' });
        expect(fetchGame).not.toHaveBeenCalled();
        none.unmount();
        for (const status of [401, 403, 503]) {
            vi.mocked(fetchGame).mockResolvedValueOnce(new Response(null, { status }));
            const hook = renderHook(() => useBattleSession('pep', 7, '9001'));
            await waitFor(() => expect(hook.result.current.session).toEqual({ state: 'unavailable', reason: 'TICKET' }));
            hook.unmount();
        }
        expect(FakeSocket.instances).toEqual([]);
    });
});

function snapshotFrameForTest() { return JSON.stringify(snapshot('0')); }
