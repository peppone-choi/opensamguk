import type { ReactElement } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import CommandFlow from '../components/command-flow/CommandFlow';
import { announceTurnSlotsChanged } from '../lib/turn-slots';
import type { ReservedSlot } from '../lib/types';

vi.setConfig({ testTimeout: 20_000 });

vi.mock('next/navigation', () => ({
    usePathname: () => '/game/pep/war-room',
    useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));

// Only the HTTP boundary is replaced. CommandFlow, the reservation ring hook, API client and result polling are real.
const requestId = '00000000-0000-4000-8000-000000000561';
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
});
const deferred = () => {
    let resolve: (response: Response) => void = () => {};
    const promise = new Promise<Response>(r => { resolve = r; });
    return { promise, resolve };
};
const employSlot: ReservedSlot = { turnIdx: 0, action: 'action.employ', brief: '등용', arg: { targetGeneralId: 9 } };
const employRead = (...targets: { generalId: number; name: string; available: boolean; reason?: string }[]) =>
    json({ inputId: 'action.employ', available: true, targets });
const hahudun = { generalId: 9, name: '하후돈', available: false, reason: '이미 다른 세력에 있습니다.' };
const FALLBACK = '01순 — 대상 장수 이름 확인 불가 등용';

let rings: Record<number, ReservedSlot[]>;
let employ: (generalId: number) => Promise<Response>;
let employCalls: { generalId: number; server: string | undefined }[];
let writes: { turnIdx: number; arg: Record<string, unknown> }[];
const server = () => document.cookie.split('; ').find(row => row.startsWith('sam_server='))?.split('=')[1];

beforeEach(() => {
    document.cookie = 'sam_server=pep; path=/';
    rings = { 1: [{ ...employSlot }], 2: [{ ...employSlot }] };
    employCalls = [];
    writes = [];
    employ = async () => employRead(hahudun);
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
        const path = new URL(url, 'http://localhost');
        const generalId = Number(path.searchParams.get('generalId'));
        if (path.pathname === '/api/game/api/reserved-commands') return json({ result: true, generalId, slots: rings[generalId] ?? [] });
        if (path.pathname === '/api/game/api/commands/employ-options') {
            employCalls.push({ generalId, server: server() });
            return employ(generalId);
        }
        if (path.pathname === '/api/game/api/command/action.employ' && init?.method === 'POST') {
            const turnIdx = Number(path.searchParams.get('turnIdx'));
            const arg = JSON.parse(String(init.body)) as Record<string, unknown>;
            writes.push({ turnIdx, arg });
            // The server's reserve contract: the slot is stored and the request is only accepted, not executed.
            rings[generalId] = [...(rings[generalId] ?? []).filter(s => s.turnIdx !== turnIdx), { turnIdx, action: 'action.employ', brief: '등용', arg }];
            return json({ status: 'AVAILABLE', requestId, turnIdx }, 202);
        }
        if (path.pathname === `/api/game/api/command/result/${requestId}`) return json({
            status: 'PENDING', requestId, phase: 'reservationAccepted',
        });
        return json({}, 404);
    }));
});
afterEach(() => {
    vi.unstubAllGlobals();
    document.cookie = 'sam_server=; max-age=0; path=/';
});

const slotButton = (name: string) => screen.findByRole('button', { name });
const flow = (generalId: number, refreshKey = 0) =>
    <CommandFlow generalId={generalId} initialSlot={0} refreshKey={refreshKey} onClose={vi.fn()} />;
const settle = () => act(async () => { await new Promise(r => setTimeout(r, 0)); });

test('a saved employ reservation reads its stored target name, including a target that is no longer available', async () => {
    render(flow(1));
    await slotButton('01순 — 하후돈 등용');
    expect(screen.getByTestId('slot-reserved')).toHaveTextContent('01순 지금 예약: 하후돈 등용');
    expect(employCalls).toEqual([{ generalId: 1, server: 'pep' }]);
    expect(screen.queryByText(/#9/)).not.toBeInTheDocument();
});

test.each([
    ['403', () => Promise.resolve(json({ error: { code: 'FORBIDDEN' } }, 403))],
    ['empty targets', () => Promise.resolve(employRead())],
    ['targets missing', () => Promise.resolve(json({ inputId: 'action.employ', available: false }))],
    ['another command response', () => Promise.resolve(json({ inputId: 'action.persuadeCaptive', available: true, targets: [hahudun] }))],
    ['conflicting names', () => Promise.resolve(employRead(hahudun, { ...hahudun, name: '하후연' }))],
])('%s falls back without a technical ID and keeps the ring', async (_, read) => {
    employ = read;
    render(flow(1));
    await slotButton(FALLBACK);
    await waitFor(() => expect(employCalls).toHaveLength(1));
    await settle();
    expect(screen.getByTestId('slot-reserved')).toHaveTextContent('01순 지금 예약: 대상 장수 이름 확인 불가 등용');
    expect(screen.queryByText(/하후|#9/)).not.toBeInTheDocument();
});

test('other people commands are not looked up through employ options and keep their own wording', async () => {
    rings[1] = [{ turnIdx: 0, action: 'action.persuadeCaptive', brief: '포로 설득', arg: { targetGeneralId: 9 } }];
    render(flow(1));
    await slotButton('01순 — 장수 #9 (이름 확인 불가) 포로 설득');
    await settle();
    expect(employCalls).toEqual([]);
    expect(screen.queryByText(/하후돈/)).not.toBeInTheDocument();
});

test('switching actor clears the previous actor names and a late response for that actor cannot restore them', async () => {
    const late = deferred();
    employ = generalId => generalId === 1 ? late.promise : Promise.resolve(json({}, 403));
    const { rerender } = render(flow(1));
    await slotButton(FALLBACK);
    rerender(flow(2));
    await waitFor(() => expect(employCalls.map(c => c.generalId)).toEqual([1, 2]));
    await act(async () => { late.resolve(employRead(hahudun)); });
    await settle();
    expect(await slotButton(FALLBACK)).toBeInTheDocument();
    expect(screen.queryByText(/하후돈/)).not.toBeInTheDocument();
});

test('a shown name does not survive into another actor while that actor\'s read is pending', async () => {
    const pending = deferred();
    employ = generalId => generalId === 1 ? Promise.resolve(employRead(hahudun)) : pending.promise;
    const { rerender } = render(flow(1));
    await slotButton('01순 — 하후돈 등용');
    rerender(flow(2));
    await slotButton(FALLBACK);
    expect(screen.queryByText(/하후돈/)).not.toBeInTheDocument();
    await act(async () => { pending.resolve(employRead({ generalId: 9, name: '하후돈', available: true })); });
    await slotButton('01순 — 하후돈 등용');
    expect(employCalls.map(c => c.generalId)).toEqual([1, 2]);
});

test('switching server clears names; the new server read decides the name', async () => {
    const che = deferred();
    employ = () => server() === 'che' ? che.promise : Promise.resolve(employRead(hahudun));
    const { rerender } = render(flow(1));
    await slotButton('01순 — 하후돈 등용');
    document.cookie = 'sam_server=che; path=/';
    rerender(flow(1));
    await slotButton(FALLBACK);
    expect(screen.queryByText(/하후돈/)).not.toBeInTheDocument();
    await act(async () => { che.resolve(employRead({ generalId: 9, name: '조인', available: true })); });
    await slotButton('01순 — 조인 등용');
    expect(employCalls).toEqual([{ generalId: 1, server: 'pep' }, { generalId: 1, server: 'che' }]);
});

test('turn refresh and refreshKey reread names instead of keeping the previous generation', async () => {
    const { rerender } = render(flow(1));
    await slotButton('01순 — 하후돈 등용');
    const next = deferred();
    employ = () => next.promise;
    await act(async () => announceTurnSlotsChanged());
    await slotButton(FALLBACK);
    expect(screen.queryByText(/하후돈/)).not.toBeInTheDocument();
    await act(async () => { next.resolve(employRead({ generalId: 9, name: '하후돈', available: true })); });
    await slotButton('01순 — 하후돈 등용');

    employ = async () => json({}, 403);
    rerender(flow(1, 1));
    await slotButton(FALLBACK);
    await settle();
    expect(screen.queryByText(/하후돈/)).not.toBeInTheDocument();
    expect(employCalls).toHaveLength(3);
});

// A → B → A reuses the first scope's key; the first lookup's names must stay gone until the new read answers.
async function roundTrip(enter: (scope: 'A' | 'B') => ReactElement) {
    const reads: ReturnType<typeof deferred>[] = [];
    employ = () => { const read = deferred(); reads.push(read); return read.promise; };
    const { rerender } = render(enter('A'));
    await waitFor(() => expect(reads).toHaveLength(1));
    await act(async () => { reads[0].resolve(employRead(hahudun)); });
    await slotButton('01순 — 하후돈 등용');
    rerender(enter('B'));
    expect(screen.queryByText(/하후돈/)).not.toBeInTheDocument();
    await waitFor(() => expect(reads).toHaveLength(2));
    rerender(enter('A'));
    expect(screen.queryByText(/하후돈/)).not.toBeInTheDocument();
    await slotButton(FALLBACK);
    await waitFor(() => expect(reads).toHaveLength(3));
    await settle();
    expect(screen.queryByText(/하후돈/)).not.toBeInTheDocument();
    // B's late answer cannot write into the new A lookup.
    await act(async () => { reads[1].resolve(employRead(hahudun)); });
    await settle();
    expect(await slotButton(FALLBACK)).toBeInTheDocument();
    expect(screen.queryByText(/하후돈/)).not.toBeInTheDocument();
    await act(async () => { reads[2].resolve(employRead({ generalId: 9, name: '조인', available: true })); });
    await slotButton('01순 — 조인 등용');
    expect(screen.queryByText(/하후돈|#9/)).not.toBeInTheDocument();
    expect(reads).toHaveLength(3);
}

test('returning to an earlier actor does not show that actor\'s previous names while its new read is pending', async () => {
    await roundTrip(scope => flow(scope === 'A' ? 1 : 2));
    expect(employCalls.map(c => c.generalId)).toEqual([1, 2, 1]);
});

test('returning to an earlier server does not show that server\'s previous names while its new read is pending', async () => {
    await roundTrip(scope => {
        document.cookie = `sam_server=${scope === 'A' ? 'pep' : 'che'}; path=/`;
        return flow(1);
    });
    expect(employCalls).toEqual([{ generalId: 1, server: 'pep' }, { generalId: 1, server: 'che' }, { generalId: 1, server: 'pep' }]);
});

test('returning to an earlier refreshKey does not show that key\'s previous names while its new read is pending', async () => {
    await roundTrip(scope => flow(1, scope === 'A' ? 0 : 1));
    expect(employCalls).toEqual([{ generalId: 1, server: 'pep' }, { generalId: 1, server: 'pep' }, { generalId: 1, server: 'pep' }]);
});

test('a new employ reservation is reported from the saved slot as reserved, never as executed', async () => {
    employ = async () => employRead(hahudun, { generalId: 12, name: '석도', available: true });
    render(<CommandFlow generalId={1} initialInputId="action.employ" onClose={vi.fn()} />);
    await slotButton('01순 — 하후돈 등용');
    fireEvent.click(await screen.findByRole('option', { name: /석도/ }));
    const submit = () => screen.getByRole('button', { name: /순에 예약$/ });
    await waitFor(() => expect(submit()).toHaveTextContent('02순에 예약'));
    fireEvent.click(submit());
    await waitFor(() => expect(writes).toEqual([{ turnIdx: 1, arg: { targetGeneralId: 12 } }]));
    await screen.findByText('「석도 등용」 — 02순에 예약했습니다.', {}, { timeout: 9000 });
    await slotButton('02순 — 석도 등용');
    expect(rings[1].find(s => s.turnIdx === 0)).toEqual(employSlot);
    expect(screen.queryByText(/처리했습니다/)).not.toBeInTheDocument();
});
