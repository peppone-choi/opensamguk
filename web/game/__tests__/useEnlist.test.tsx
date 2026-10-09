// useEnlist ownership — owner epochs (actor/server, A→B→A, unmount), receipts, duplicate lock and read-back proof.
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { readEnlistOptions, readEnlistSlots, sendEnlist, type EnlistCalendar, type EnlistSlotsRead } from '@/lib/api/enlist-slots';
import { submitCommandAndAwaitResult, type CommandSubmitResult } from '@/lib/commandSubmit';
import { resetEnlistReceipts, useEnlist } from '@/hooks/useEnlist';
import type { EnlistmentOptionsResponse, IntakeOutcome } from '@/lib/types';

const h = vi.hoisted(() => ({ server: 'alpha' }));
vi.mock('@/lib/api/enlist-slots', async importOriginal => ({
  ...await importOriginal<typeof import('@/lib/api/enlist-slots')>(),
  selectedEnlistServer: () => h.server, readEnlistOptions: vi.fn(), readEnlistSlots: vi.fn(), sendEnlist: vi.fn(),
}));
vi.mock('@/lib/commandSubmit', () => ({ submitCommandAndAwaitResult: vi.fn() }));

type Deferred<T> = { promise: Promise<T>; resolve: (v: T) => void; reject: (e: unknown) => void };
function deferred<T>(): Deferred<T> {
  let resolve!: (v: T) => void; let reject!: (e: unknown) => void;
  return { promise: new Promise<T>((a, b) => { resolve = a; reject = b; }), resolve, reject };
}
const calendar: EnlistCalendar = { year: 190, month: 1, turnPhase: 1, turnTime: '2026-10-09 21:40:00', turnTerm: 60 };
const nation = { mode: 'NATION' as const, targetId: 2, label: '조조', availability: { status: 'AVAILABLE' as const } };
const general = { mode: 'GENERAL' as const, targetId: 2, label: '유비', availability: { status: 'AVAILABLE' as const } };
const opts = (...options: EnlistmentOptionsResponse['options']): EnlistmentOptionsResponse => ({ result: true, inputId: 'action.enlist', maxReservedTurns: 12, options });
const ring = (generalId = 7, slots: EnlistSlotsRead['slots'] = [], cal: EnlistCalendar = calendar): EnlistSlotsRead => ({ generalId, slots, calendar: cal });
const enlisted = (turnIdx: number, arg: Record<string, unknown>) => ({ turnIdx, action: 'action.enlist', brief: '출사', arg });
const refresh = vi.fn();
const optionsMock = vi.mocked(readEnlistOptions);
const slotsMock = vi.mocked(readEnlistSlots);
const sendMock = vi.mocked(sendEnlist);
const submitMock = vi.mocked(submitCommandAndAwaitResult);

/** sendEnlist double that runs the hook's real guards against a chosen fresh ring. */
function sendWith(fresh: () => Promise<EnlistSlotsRead>, posted: () => Promise<IntakeOutcome> = async () => ({ status: 'AVAILABLE', requestId: 'r' })) {
  sendMock.mockImplementation(async (_g, _o, _t, guards) => {
    const read = await fresh();
    const stop = guards.preflight(read);
    if (stop) return { status: 'BLOCKED', reason: stop };
    guards.onPost?.();
    return posted();
  });
}
function outcome(result: () => Promise<CommandSubmitResult> | CommandSubmitResult) {
  submitMock.mockImplementation(async submit => {
    const accepted = await submit();
    return accepted.status === 'BLOCKED' ? { status: 'rejected', reason: accepted.reason } : result();
  });
}
async function mount(id = 7) {
  const view = renderHook(({ generalId }) => useEnlist(generalId, refresh), { initialProps: { generalId: id } });
  await waitFor(() => expect(view.result.current.loading).toBe(false));
  return view;
}

beforeEach(() => {
  vi.clearAllMocks();
  resetEnlistReceipts();
  h.server = 'alpha';
  optionsMock.mockResolvedValue(opts(nation, general));
  slotsMock.mockImplementation(async generalId => ring(generalId));
  sendWith(async () => ring());
  outcome(() => ({ status: 'reserved', reason: '명령이 예약되었습니다.' }));
});

describe('useEnlist ownership epochs', () => {
  it('A→B→A ignores late reads from the first A and never shows A data on the first B render', async () => {
    const first = [deferred<EnlistmentOptionsResponse>(), deferred<EnlistmentOptionsResponse>(), deferred<EnlistmentOptionsResponse>()];
    let call = 0;
    optionsMock.mockImplementation(() => first[call++].promise);
    const seen: (string | null)[] = [];
    const view = renderHook(({ generalId }) => {
      const state = useEnlist(generalId, refresh);
      seen.push(`${generalId}:${state.options?.map(o => o.label).join(',') ?? 'none'}`);
      return state;
    }, { initialProps: { generalId: 7 } });
    view.rerender({ generalId: 8 });
    view.rerender({ generalId: 7 });
    await act(async () => { first[0].resolve(opts({ ...nation, label: '옛 A' })); first[1].resolve(opts({ ...nation, label: 'B' })); });
    expect(view.result.current.options).toBeNull();
    await act(async () => { first[2].resolve(opts(nation)); });
    expect(view.result.current.options?.map(o => o.label)).toEqual(['조조']);
    expect(seen.some(s => s?.includes('옛 A') || s?.includes('B'))).toBe(false);
  });

  it('a server change with the same actor hides the old ring on the very first render', async () => {
    const view = await mount();
    expect(view.result.current.slotsLoad.state).toBe('ready');
    const later = deferred<EnlistSlotsRead>();
    slotsMock.mockImplementation(() => later.promise);
    h.server = 'beta';
    view.rerender({ generalId: 7 });
    expect(view.result.current.slotsLoad.state).toBe('loading');
    expect(view.result.current.options).toBeNull();
  });

  it('no POST while loading, after a failed or malformed ring, or with nothing chosen', async () => {
    slotsMock.mockRejectedValue(new Error('순 정보를 확인하지 못해 예약하지 않았습니다.'));
    const view = await mount();
    act(() => view.result.current.chooseCandidate('NATION:2'));
    await act(() => view.result.current.reserve());
    expect(view.result.current.slotsLoad.state).toBe('error');
    expect(sendMock).not.toHaveBeenCalled();
    slotsMock.mockImplementation(() => new Promise(() => {}));
    act(() => view.result.current.retry());
    await act(() => view.result.current.reserve());
    expect(sendMock).not.toHaveBeenCalled();
  });
});

describe('useEnlist selection', () => {
  it('starts on the first empty slot and keeps an explicit slot that later became occupied', async () => {
    slotsMock.mockResolvedValue(ring(7, [enlisted(0, { mode: 'RANDOM' })]));
    const view = await mount();
    expect(view.result.current.turnIdx).toBe(1);
    act(() => { view.result.current.chooseCandidate('NATION:2'); view.result.current.chooseSlot(5); });
    slotsMock.mockResolvedValue(ring(7, [enlisted(0, { mode: 'RANDOM' }), { turnIdx: 5, action: 'che_훈련', brief: '훈련', arg: {} }]));
    act(() => view.result.current.retry());
    await waitFor(() => expect(view.result.current.availability.reason).toContain('06순에는 이미'));
    expect(view.result.current.turnIdx).toBe(5);
  });

  it('the auto-selected first slot is frozen: a refresh that fills it blocks instead of moving, until the user picks', async () => {
    const drill = { turnIdx: 0, action: 'che_훈련', brief: '훈련', arg: {} };
    const view = await mount();
    act(() => view.result.current.chooseCandidate('NATION:2'));
    expect(view.result.current.turnIdx).toBe(0);
    expect(view.result.current.availability.status).toBe('AVAILABLE');
    slotsMock.mockImplementation(async () => ring(7, [drill, ...(sendMock.mock.calls.length ? [enlisted(5, { mode: 'NATION', targetId: 2 })] : [])]));
    act(() => view.result.current.retry());
    await waitFor(() => expect(view.result.current.availability.reason).toContain('01순에는 이미'));
    expect(view.result.current.turnIdx).toBe(0);
    await act(() => view.result.current.reserve());
    expect(sendMock).not.toHaveBeenCalled();
    act(() => view.result.current.chooseSlot(5));
    expect(view.result.current.turnIdx).toBe(5);
    await act(() => view.result.current.reserve());
    expect(sendMock).toHaveBeenCalledTimes(1);
    expect(sendMock.mock.calls[0].slice(0, 3)).toEqual([7, nation, 5]);
    expect(view.result.current.receipt).toMatchObject({ status: 'reserved', turnIdx: 5 });
  });

  it('a new owner starts again from its own first empty slot', async () => {
    const view = await mount();
    act(() => view.result.current.chooseSlot(5));
    view.rerender({ generalId: 8 });
    await waitFor(() => expect(view.result.current.slotsLoad.state).toBe('ready'));
    expect(view.result.current.turnIdx).toBe(0);
  });

  it('a full ring selects nothing and never overwrites', async () => {
    slotsMock.mockResolvedValue(ring(7, Array.from({ length: 12 }, (_, turnIdx) => ({ turnIdx, action: 'che_훈련', brief: '훈련', arg: {} }))));
    const view = await mount();
    act(() => view.result.current.chooseCandidate('NATION:2'));
    expect(view.result.current.turnIdx).toBeNull();
    expect(view.result.current.availability.reason).toContain('12순이 모두 차 있습니다');
    await act(() => view.result.current.reserve());
    expect(sendMock).not.toHaveBeenCalled();
  });

  it('candidate reorder keeps the original target; disappearance blocks instead of moving', async () => {
    const view = await mount();
    act(() => view.result.current.chooseCandidate('NATION:2'));
    optionsMock.mockResolvedValue(opts(general, { ...nation, label: '조조(갱신)' }));
    act(() => view.result.current.retry());
    await waitFor(() => expect(view.result.current.selected?.label).toBe('조조(갱신)'));
    expect(view.result.current.selected).toMatchObject({ mode: 'NATION', targetId: 2 });
    optionsMock.mockResolvedValue(opts(general));
    act(() => view.result.current.retry());
    await waitFor(() => expect(view.result.current.availability.reason).toContain('사라졌습니다'));
    await act(() => view.result.current.reserve());
    expect(sendMock).not.toHaveBeenCalled();
  });
});

describe('useEnlist submission', () => {
  it('two synchronous clicks send once; GENERAL keeps the original id and slot', async () => {
    sendWith(async () => ring(), async () => ({ status: 'AVAILABLE', requestId: 'r' }));
    slotsMock.mockImplementation(async () => ring(7, sendMock.mock.calls.length ? [enlisted(3, { mode: 'GENERAL', targetId: 2 })] : []));
    const view = await mount();
    act(() => { view.result.current.chooseMode('GENERAL'); view.result.current.chooseCandidate('GENERAL:2'); view.result.current.chooseSlot(3); });
    await act(async () => { void view.result.current.reserve(); void view.result.current.reserve(); });
    await waitFor(() => expect(view.result.current.receipt?.status).toBe('reserved'));
    expect(sendMock).toHaveBeenCalledTimes(1);
    expect(sendMock.mock.calls[0].slice(0, 3)).toEqual([7, general, 3]);
    expect(refresh).toHaveBeenCalledTimes(1);
  });

  it('reserved without a matching fresh read-back is unknown, not success', async () => {
    slotsMock.mockImplementation(async () => ring(7, sendMock.mock.calls.length ? [enlisted(0, { mode: 'NATION', targetId: 3 })] : []));
    const view = await mount();
    act(() => view.result.current.chooseCandidate('NATION:2'));
    await act(() => view.result.current.reserve());
    expect(view.result.current.receipt?.status).toBe('unknown');
    expect(refresh).not.toHaveBeenCalled();
  });

  it('a changed calendar or late-occupied slot in the preflight blocks without posting', async () => {
    const view = await mount();
    act(() => view.result.current.chooseCandidate('NATION:2'));
    const posted = vi.fn(async (): Promise<IntakeOutcome> => ({ status: 'AVAILABLE', requestId: 'r' }));
    sendWith(async () => ring(7, [], { ...calendar, turnPhase: 2 }), posted);
    await act(() => view.result.current.reserve());
    expect(view.result.current.receipt).toMatchObject({ status: 'rejected', reason: '순 시각이 바뀌었습니다. 12순을 다시 확인한 뒤 예약해 주세요.' });
    expect(posted).not.toHaveBeenCalled();
    act(() => view.result.current.acknowledge());
    expect(view.result.current.receipt).toBeNull();
  });

  it('a scope change during the preflight posts nothing and leaves no receipt', async () => {
    const fresh = deferred<EnlistSlotsRead>();
    const posted = vi.fn(async (): Promise<IntakeOutcome> => ({ status: 'AVAILABLE', requestId: 'r' }));
    sendWith(() => fresh.promise, posted);
    const view = await mount();
    act(() => view.result.current.chooseCandidate('NATION:2'));
    let pending!: Promise<void>;
    act(() => { pending = view.result.current.reserve(); });
    view.rerender({ generalId: 8 });
    await act(async () => { fresh.resolve(ring()); await pending; });
    expect(posted).not.toHaveBeenCalled();
    view.rerender({ generalId: 7 });
    expect(view.result.current.receipt).toBeNull();
  });

  it('A→B→A: a late result for the first A is kept only as unknown and never re-accepted as success', async () => {
    const result = deferred<CommandSubmitResult>();
    outcome(() => result.promise);
    const view = await mount();
    act(() => view.result.current.chooseCandidate('NATION:2'));
    let pending!: Promise<void>;
    act(() => { pending = view.result.current.reserve(); });
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(1));
    view.rerender({ generalId: 8 });
    view.rerender({ generalId: 7 });
    await act(async () => { result.resolve({ status: 'reserved', reason: '명령이 예약되었습니다.' }); await pending; });
    expect(view.result.current.receipt?.status).toBe('unknown');
    expect(refresh).not.toHaveBeenCalled();
    await act(() => view.result.current.reserve());
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('unmount before the result: no refresh, receipt survives a remount and blocks duplicates', async () => {
    const result = deferred<CommandSubmitResult>();
    outcome(() => result.promise);
    const view = await mount();
    act(() => view.result.current.chooseCandidate('NATION:2'));
    let pending!: Promise<void>;
    act(() => { pending = view.result.current.reserve(); });
    await waitFor(() => expect(sendMock).toHaveBeenCalledTimes(1));
    view.unmount();
    await act(async () => { result.resolve({ status: 'reserved', reason: '명령이 예약되었습니다.' }); await pending; });
    expect(refresh).not.toHaveBeenCalled();
    const again = await mount();
    expect(again.result.current.receipt?.status).toBe('unknown');
    act(() => again.result.current.chooseCandidate('NATION:2'));
    await act(() => again.result.current.reserve());
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('pending and parent refresh keep the exact-scope receipt; another actor has none', async () => {
    outcome(() => ({ status: 'pending', reason: '처리 지연' }));
    const view = await mount();
    act(() => view.result.current.chooseCandidate('NATION:2'));
    await act(() => view.result.current.reserve());
    act(() => view.result.current.retry());
    view.rerender({ generalId: 7 });
    expect(view.result.current.receipt?.status).toBe('pending');
    view.rerender({ generalId: 8 });
    expect(view.result.current.receipt).toBeNull();
    view.rerender({ generalId: 7 });
    expect(view.result.current.receipt?.status).toBe('pending');
    await act(() => view.result.current.reserve());
    expect(sendMock).toHaveBeenCalledTimes(1);
  });

  it('valid null calendar metadata does not block an otherwise valid submission', async () => {
    const none: EnlistCalendar = { year: null, month: null, turnPhase: null, turnTime: null, turnTerm: null };
    slotsMock.mockImplementation(async () => ring(7, sendMock.mock.calls.length ? [enlisted(0, { mode: 'NATION', targetId: 2 })] : [], none));
    sendWith(async () => ring(7, [], none));
    const view = await mount();
    act(() => view.result.current.chooseCandidate('NATION:2'));
    await act(() => view.result.current.reserve());
    expect(view.result.current.receipt?.status).toBe('reserved');
  });
});
