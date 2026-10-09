import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import CommandFlow, { type CommandFlowProps } from '../components/command-flow/CommandFlow';
import { submitCommandAndAwaitResult } from '../lib/commandSubmit';
import type { ReservedSlot } from '../lib/types';

vi.setConfig({ testTimeout: 20_000 });

vi.mock('next/navigation', () => ({
    usePathname: () => '/game/pep/war-room',
    useSearchParams: () => new URLSearchParams('do=action.employ'),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));
// Only result polling is held by the test. CommandFlow, options adapters, the reservation ring and the API client are real;
// the command POST still goes through fetch so its actor · server · slot are recorded.
vi.mock('../lib/commandSubmit', () => ({ submitCommandAndAwaitResult: vi.fn() }));

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
});
type Target = { generalId: number; name: string; available: boolean; reason?: string };
const employRead = (...targets: Target[]) => json({ inputId: 'action.employ', available: true, targets });
const sunquan: Target = { generalId: 9, name: '손권', available: true };
const withdrawn: Target = { ...sunquan, available: false, reason: '이미 다른 세력에 있습니다.' };
const caocao: Target = { generalId: 22, name: '조조', available: true };
const liubei: Target = { generalId: 21, name: '유비', available: true };
const selfTrain: ReservedSlot = { turnIdx: 0, action: 'action.selfTrain', brief: '수련', arg: { stat: 'strength' }, revision: '00000000-0000-4000-8000-0000000000d0' };
const replacementRevision = '00000000-0000-4000-8000-0000000000d1';

/** A held read — every caller gets its own Response once released. */
const held = () => {
    let release: (make: () => Response) => void = () => {};
    const ready = new Promise<() => Response>((r) => { release = r; });
    return { read: () => ready.then((make) => make()), release };
};
const deferred = <T,>() => {
    let resolve: (value: T) => void = () => {};
    const promise = new Promise<T>((r) => { resolve = r; });
    return { promise, resolve };
};

type Call = { generalId: number; server: string | undefined };
let respond: (call: Call) => Promise<Response>;
let calls: Call[];
let rings: Record<number, ReservedSlot[]>;
let writes: (Call & { turnIdx: number; arg: Record<string, unknown> })[];
let outcome: () => Promise<unknown>;
const server = () => document.cookie.split('; ').find((row) => row.startsWith('sam_server='))?.split('=')[1];
const setServer = (id: string) => { document.cookie = `sam_server=${id}; path=/`; };

beforeEach(() => {
    setServer('pep');
    calls = [];
    rings = {};
    writes = [];
    respond = async () => employRead(sunquan);
    outcome = async () => ({ status: 'reserved', reason: '명령이 예약되었습니다.' });
    vi.mocked(submitCommandAndAwaitResult).mockImplementation(async (send) => { await send(); return (await outcome()) as never; });
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
        const path = new URL(url, 'http://localhost');
        const generalId = Number(path.searchParams.get('generalId'));
        if (path.pathname === '/api/game/api/reserved-commands') return json({ result: true, generalId, slots: rings[generalId] ?? [] });
        if (path.pathname === '/api/game/api/commands/employ-options') {
            const call = { generalId, server: server() };
            calls.push(call);
            return respond(call);
        }
        if (path.pathname === '/api/game/api/command/action.employ' && init?.method === 'POST') {
            const turnIdx = Number(path.searchParams.get('turnIdx'));
            const arg = JSON.parse(String(init.body)) as Record<string, unknown>;
            writes.push({ generalId, server: server(), turnIdx, arg });
            rings[generalId] = [...(rings[generalId] ?? []).filter((s) => s.turnIdx !== turnIdx), { turnIdx, action: 'action.employ', brief: '등용', arg, revision: replacementRevision }];
            return json({ status: 'AVAILABLE', requestId: '00000000-0000-4000-8000-000000000606', turnIdx }, 202);
        }
        return json({}, 404);
    }));
});
afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = 'sam_server=; max-age=0; path=/';
});

const flow = (props: Partial<CommandFlowProps> = {}) =>
    <CommandFlow generalId={7} initialInputId="action.employ" onClose={vi.fn()} {...props} />;
const person = (name: string) => screen.findByRole('option', { name: new RegExp(name) });
const submitButton = () => screen.getByTestId('command-flow').querySelector<HTMLElement>('[data-input-status]')!;
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 0)); });
const sent = (generalId: number, srv = 'pep') => ({ generalId, server: srv, turnIdx: 0, arg: { targetGeneralId: 9 } });

test('refreshKey drops the old options in the same commit; a withdrawn target is cleared and does not revive', async () => {
    const { rerender } = render(flow());
    fireEvent.click(await person('손권'));
    expect(await person('손권')).toHaveAttribute('aria-selected', 'true');
    const refreshed = held();
    respond = refreshed.read;
    rerender(flow({ refreshKey: 1 }));
    // No frame of the new scope offers the old candidate or submits it.
    expect(screen.queryByRole('option', { name: /손권/ })).toBeNull();
    fireEvent.click(submitButton());
    expect(await screen.findByText('선택지를 불러오는 중입니다 — 잠시 뒤 다시 눌러 주세요.')).toBeInTheDocument();
    await act(async () => refreshed.release(() => employRead(withdrawn)));
    expect(await person('손권')).toHaveAttribute('aria-disabled', 'true');
    expect(await screen.findByText('「데려올 사람」을 고르세요.')).toBeInTheDocument();

    respond = async () => employRead(sunquan);
    rerender(flow({ refreshKey: 2 }));
    expect(await person('손권')).toHaveAttribute('aria-selected', 'false');
    expect(calls).toEqual([{ generalId: 7, server: 'pep' }, { generalId: 7, server: 'pep' }, { generalId: 7, server: 'pep' }]);
    fireEvent.click(submitButton());
    await settle();
    expect(writes).toEqual([]);
    fireEvent.click(await person('손권'));
    fireEvent.click(submitButton());
    await waitFor(() => expect(writes).toEqual([sent(7)]));
});

test.each([
    { kind: 'actor', actors: [7, 8, 7], servers: ['pep', 'pep', 'pep'] },
    { kind: 'server', actors: [7, 7, 7], servers: ['pep', 'che', 'pep'] },
])('$kind A→B→A: late reads of the first A and of B, resolved in reverse, never show or submit', async ({ actors, servers }) => {
    const reads = [held(), held(), held()];
    respond = () => reads[calls.length - 1].read();
    const { rerender } = render(flow({ generalId: actors[0] }));
    await waitFor(() => expect(calls).toHaveLength(1));
    setServer(servers[1]);
    rerender(flow({ generalId: actors[1] }));
    await waitFor(() => expect(calls).toHaveLength(2));
    setServer(servers[2]);
    rerender(flow({ generalId: actors[2] }));
    await waitFor(() => expect(calls).toHaveLength(3));
    expect(calls).toEqual(actors.map((generalId, i) => ({ generalId, server: servers[i] })));

    await act(async () => reads[1].release(() => employRead(liubei)));
    await act(async () => reads[0].release(() => employRead(caocao)));
    await settle();
    expect(screen.queryByRole('option')).toBeNull();
    fireEvent.click(submitButton());
    await settle();
    expect(writes).toEqual([]);

    await act(async () => reads[2].release(() => employRead(sunquan)));
    fireEvent.click(await person('손권'));
    expect(screen.queryByRole('option', { name: /유비|조조/ })).toBeNull();
    fireEvent.click(submitButton());
    await waitFor(() => expect(writes).toEqual([sent(7)]));
});

test('a retry and a refresh resolving out of order: only the newest scope read is used', async () => {
    respond = async () => json({}, 503);
    const { rerender } = render(flow());
    await screen.findByText('이 명령의 선택지를 불러오지 못했습니다');
    const retried = held();
    respond = retried.read;
    fireEvent.click(screen.getByRole('button', { name: /다시/ }));
    await waitFor(() => expect(calls).toHaveLength(2));
    const refreshed = held();
    respond = refreshed.read;
    rerender(flow({ refreshKey: 1 }));
    await waitFor(() => expect(calls).toHaveLength(3));
    await act(async () => refreshed.release(() => employRead(sunquan)));
    await person('손권');
    await act(async () => retried.release(() => employRead(caocao)));
    await settle();
    expect(screen.queryByRole('option', { name: /조조/ })).toBeNull();
    fireEvent.click(await person('손권'));
    fireEvent.click(submitButton());
    await waitFor(() => expect(writes).toEqual([sent(7)]));
});

test('a late submission of actor 7 leaves actor 8 selection, slot, busy state and result alone', async () => {
    const held7 = deferred<unknown>();
    outcome = () => held7.promise;
    const onReserved = vi.fn();
    const { rerender } = render(flow({ onReserved }));
    fireEvent.click(await person('손권'));
    fireEvent.click(submitButton());
    await waitFor(() => expect(writes).toEqual([sent(7)]));
    expect(submitButton()).toHaveTextContent('예약하는 중');

    rerender(flow({ generalId: 8, onReserved }));
    // The compatible choice stays once actor 8's own options offer it.
    expect(await person('손권')).toHaveAttribute('aria-selected', 'true');
    expect(submitButton()).toHaveTextContent('01순에 예약');
    await act(async () => held7.resolve({ status: 'reserved', reason: '명령이 예약되었습니다.' }));
    await settle();
    expect(onReserved).not.toHaveBeenCalled();
    expect(screen.queryByText(/예약했습니다|접수했습니다/)).toBeNull();
    expect(submitButton()).toHaveTextContent('01순에 예약');
    expect(await person('손권')).toHaveAttribute('aria-selected', 'true');
    expect(calls.map((c) => c.generalId)).toEqual([7, 8]);
});

test('close and reopen: the closed flow\'s late read and late submission never reach the reopened flow', async () => {
    const closedSubmit = deferred<unknown>();
    outcome = () => closedSubmit.promise;
    const closedReserved = vi.fn();
    const closed = render(flow({ onReserved: closedReserved }));
    fireEvent.click(await person('손권'));
    fireEvent.click(submitButton());
    await waitFor(() => expect(writes).toHaveLength(1));
    // The stored slot also makes the ring look up employ names, so reads are counted as "at least".
    const closedRead = held();
    respond = closedRead.read;
    closed.rerender(flow({ onReserved: closedReserved, refreshKey: 1 }));
    await waitFor(() => expect(calls.length).toBeGreaterThanOrEqual(2));
    closed.unmount();

    const reopenedRead = held();
    respond = reopenedRead.read;
    const before = calls.length;
    render(flow());
    await waitFor(() => expect(calls.length).toBeGreaterThan(before));
    await act(async () => closedRead.release(() => employRead(caocao)));
    await act(async () => closedSubmit.resolve({ status: 'reserved', reason: '명령이 예약되었습니다.' }));
    await settle();
    expect(closedReserved).not.toHaveBeenCalled();
    expect(screen.queryByRole('option', { name: /조조/ })).toBeNull();
    expect(screen.queryByText(/예약했습니다|접수했습니다/)).toBeNull();
    expect(submitButton()).not.toHaveTextContent('예약하는 중');
    await act(async () => reopenedRead.release(() => employRead(sunquan)));
    expect(await person('손권')).toHaveAttribute('aria-selected', 'false');
    expect(screen.queryByRole('option', { name: /조조/ })).toBeNull();
});

test('an open overwrite confirmation closes on refresh and cannot send a target the new read withdrew', async () => {
    rings[7] = [selfTrain];
    const { rerender } = render(flow({ initialSlot: 0 }));
    await screen.findByTestId('slot-reserved');
    fireEvent.click(await person('손권'));
    fireEvent.click(submitButton());
    expect(await screen.findByText('01순을 바꿉니다')).toBeInTheDocument();
    const refreshed = held();
    respond = refreshed.read;
    rerender(flow({ initialSlot: 0, refreshKey: 1 }));
    expect(screen.queryByText('01순을 바꿉니다')).toBeNull();
    await act(async () => refreshed.release(() => employRead(withdrawn)));
    expect(await person('손권')).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(submitButton());
    await settle();
    expect(screen.queryByText('01순을 바꿉니다')).toBeNull();
    expect(writes).toEqual([]);
    expect(rings[7]).toEqual([selfTrain]);
});

test('an unchanged scope still confirms and replaces the reserved slot', async () => {
    rings[7] = [selfTrain];
    render(flow({ initialSlot: 0 }));
    await screen.findByTestId('slot-reserved');
    fireEvent.click(await person('손권'));
    fireEvent.click(submitButton());
    fireEvent.click(await screen.findByRole('button', { name: '바꾸기' }));
    await waitFor(() => expect(writes).toEqual([sent(7)]));
    expect(await screen.findByText(/01순에 예약했습니다/, {}, { timeout: 9000 })).toBeInTheDocument();
});

test('a success keeps its result through the refresh it triggers and keeps the still-offered target', async () => {
    function Room() {
        const [refreshKey, setRefreshKey] = useState(0);
        return flow({ refreshKey, onReserved: () => setRefreshKey((k) => k + 1) });
    }
    render(<Room />);
    fireEvent.click(await person('손권'));
    await waitFor(() => expect(submitButton()).toHaveTextContent('01순에 예약'));
    fireEvent.click(submitButton());
    await waitFor(() => expect(writes).toEqual([sent(7)]));
    await waitFor(() => expect(submitButton()).toHaveTextContent('02순에 예약'));
    expect(await screen.findByText('「손권 등용」 — 01순에 예약했습니다.', {}, { timeout: 9000 })).toBeInTheDocument();
    // The refresh re-read the options of the same actor; the result and the still-offered choice stay.
    await waitFor(() => expect(calls.length).toBeGreaterThanOrEqual(2));
    await settle();
    expect(await screen.findByText('「손권 등용」 — 01순에 예약했습니다.')).toBeInTheDocument();
    expect(await person('손권')).toHaveAttribute('aria-selected', 'true');
});
