import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useReservationCancel } from '../hooks/useReservationCancel';
import { restoreIntent, runFirstCancel, type CancelRun } from '../lib/command-flow/reservation-cancel-flow';
import { IDLE, freezeIntent, restoredPhase, type CancelPhase, type CancelTarget } from '../lib/command-flow/reservation-cancel-state';
import { journalRecord, persistIntent, readJournal } from '../lib/command-flow/reservation-cancel-journal';
import type { CancelHttp } from '../lib/api/reservation-cancel-contract';

const fixture = vi.hoisted(() => ({
  account: { user: { id: 1 }, loading: false },
  fetchGame: vi.fn(),
  ring: vi.fn(),
  send: vi.fn(),
  result: vi.fn(),
}));
vi.mock('@/lib/auth-context', () => ({ useAuthOptional: () => fixture.account }));
vi.mock('@/lib/serverGameUrl', () => ({ selectedTabServer: () => 'pep' }));
vi.mock('@/lib/turn-slots', () => ({ announceTurnSlotsChanged: vi.fn() }));
vi.mock('@/lib/api', () => ({ fetchGame: fixture.fetchGame }));
vi.mock('@/lib/api/reservation-cancel', () => ({
  readReservedRing: fixture.ring, sendReservationCancel: fixture.send, readCancelResultHttp: fixture.result,
}));
const revision = '00000000-0000-4000-8000-000000000001';
const requestId = '00000000-0000-4000-8000-000000000002';
const target: CancelTarget = { accountId: 1, server: 'pep', generation: null, actor: 7, turnIdx: 0, revision, sentence: '농사' };
const intent = freezeIntent(target, requestId);
const recovery = {
  kind: 'response', status: 200,
  body: { status: 'RESOLVED', requestId, type: 'reservationCancelled', ok: true,
    result: { type: 'reservationCancelled', ok: true, commandKind: 'QUEUE_MUTATION', actionCode: 'cancelReservedTurn',
      generalId: 7, turnIdx: 0, reservationRevision: revision, slotEmpty: true } },
};
const microtasks = async () => { for (let i = 0; i < 6; i++) await Promise.resolve(); };
function running(initial: CancelPhase): { run: CancelRun; phase: () => CancelPhase; leave: () => void } {
  let phase = initial; let alive = true;
  return {
    run: { alive: () => alive, current: () => phase, commit: next => { if (alive) phase = next; },
      listChanged: vi.fn(), tokens: { result: 0, readback: 0 } },
    phase: () => phase, leave: () => { alive = false; },
  };
}

beforeEach(() => {
  vi.clearAllMocks(); window.sessionStorage.clear();
  fixture.account.loading = false;
  vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue(requestId);
});
afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('adversarial cancellation races from the acceptance plan', () => {
  it.each([
    ['lost response', { kind: 'network' } as const],
    ['later nonadmission', { kind: 'response', status: 409, body: {
      status: 'BLOCKED', code: 'REVISION_MISMATCH', accepted: false, receiptRecorded: false, retryable: false,
    } } as const],
  ])('a late old DELETE %s cannot erase a recovered receipt kept after a readback error', async (_label, late) => {
    let finish!: (http: CancelHttp) => void;
    fixture.send.mockReturnValue(new Promise<CancelHttp>(resolve => { finish = resolve; }));
    fixture.ring.mockResolvedValueOnce({ kind: 'ok', ring: { result: true, generalId: 7,
      slots: [{ turnIdx: 0, action: 'action.farm', brief: '', arg: {}, revision }] } })
      .mockResolvedValue({ kind: 'error', status: 503 });
    fixture.result.mockResolvedValue(recovery);
    const old = running({ kind: 'preflight', target });
    const oldDispatch = runFirstCancel(old.run, target);
    await microtasks();
    expect(fixture.send).toHaveBeenCalledTimes(1);
    old.leave();
    const newer = running(IDLE);
    expect(restoreIntent(newer.run, { accountId: 1, server: 'pep', generation: null, actor: 7, turnIdx: 0 })).toBe('restored');
    await microtasks();
    expect(newer.phase().kind).toBe('readbackError');
    const recovered = readJournal();
    expect(recovered.ok && recovered.records[0]?.cancelState).toBe('confirmed');
    finish(late);
    await oldDispatch;
    const afterLate = readJournal();
    expect(afterLate.ok && afterLate.records[0]?.cancelState).toBe('confirmed');
    expect(afterLate.ok && afterLate.records[0]?.requestId).toBe(requestId);
    expect(newer.phase().kind).toBe('readbackError');
  });

  it('an unanswered transport has a finite wait and aborts without losing the original intent', async () => {
    vi.useFakeTimers();
    const actual = await vi.importActual<typeof import('../lib/api/reservation-cancel')>('../lib/api/reservation-cancel');
    fixture.fetchGame.mockImplementation((_path: string, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true });
    }));
    expect(persistIntent(journalRecord(intent, 'sending'))).toBe('ok');
    let outcome: CancelHttp | undefined;
    void actual.sendReservationCancel(intent, 'pep', () => true).then(value => { outcome = value; });
    await vi.advanceTimersByTimeAsync(60_000);
    expect(outcome?.kind).toBe('network');
    expect(fixture.fetchGame.mock.calls[0]?.[1]?.signal?.aborted).toBe(true);
    const journal = readJournal();
    expect(journal.ok && journal.records[0]?.requestId).toBe(requestId);
  });

  it('a hanging 401 refresh also ends at the deadline, and its late success cannot resend DELETE', async () => {
    vi.useFakeTimers();
    const transport = await vi.importActual<typeof import('../lib/api/reservation-cancel')>('../lib/api/reservation-cancel');
    const api = await vi.importActual<typeof import('../lib/api')>('../lib/api');
    fixture.fetchGame.mockImplementation(api.fetchGame);
    let finishRefresh!: (response: Response) => void;
    const fetch = vi.fn().mockResolvedValueOnce(new Response('', { status: 401 }))
      .mockImplementationOnce(() => new Promise<Response>(resolve => { finishRefresh = resolve; }));
    vi.stubGlobal('fetch', fetch);
    expect(persistIntent(journalRecord(intent, 'sending'))).toBe('ok');
    let outcome: CancelHttp | undefined;
    void transport.sendReservationCancel(intent, 'pep', () => true).then(value => { outcome = value; });
    await microtasks();
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(fetch.mock.calls[1]?.[0]).toBe('/api/auth/me');
    await vi.advanceTimersByTimeAsync(60_000);
    expect(outcome?.kind).toBe('network');
    const journal = readJournal();
    expect(journal.ok && journal.records[0]?.requestId).toBe(requestId);
    finishRefresh(new Response(JSON.stringify({ user: { id: 1 } }), { status: 200 }));
    await microtasks();
    expect(fetch).toHaveBeenCalledTimes(2);
  });

  it('does not restore or show the previous account journal while AuthProvider is still verifying login', async () => {
    expect(persistIntent(journalRecord(intent, 'unknown'))).toBe('ok');
    fixture.account.loading = true;
    fixture.result.mockResolvedValue({ kind: 'response', status: 200, body: { status: 'PENDING', requestId } });
    const readStorage = vi.spyOn(Storage.prototype, 'getItem');
    const { result: hook, unmount } = renderHook(() => useReservationCancel({
      actor: 7, turnIdx: 0, row: { turnIdx: 0, state: 'reserved', revision, name: '농사' },
    }));
    await act(microtasks);
    expect(readStorage).not.toHaveBeenCalled();
    expect(fixture.result).not.toHaveBeenCalled();
    expect(hook.current.status).toBeNull();
    expect(hook.current.entry.kind).not.toBe('available');
    unmount();
  });
});
