// 전투 세션 훅(P-C03) — join-ticket · WS 는 흉내(고정 자료는 이 시험 안에만). 옮기기는 하나씩 · 받아들인 것만 적용 ·
// 배치 · 지휘권 · 회차가 서버에서 먼저 바뀐 거절(STALE_*)은 새 티켓 · 새 접속으로 SNAPSHOT 을 다시 받아 기대 값 · 자리를 맞춘다(그동안 옮기기 막음) ·
// 그 밖의 거절은 사유만 보이고 다시 접속하지 않는다.
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
