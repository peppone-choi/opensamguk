import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import CommandFlow from '../components/command-flow/CommandFlow';
import { announceTurnSlotsChanged } from '../lib/turn-slots';
import type { ReservedSlot } from '../lib/types';

vi.setConfig({ testTimeout: 20_000 });

vi.mock('next/navigation', () => ({
    usePathname: () => '/game/pep/war-room',
    useSearchParams: () => new URLSearchParams('do=action.selfTrain'),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));

// Only the HTTP boundary is replaced. CommandFlow, options, API client and result polling are real.
const requestId = '00000000-0000-4000-8000-000000000333';
const original: ReservedSlot = { turnIdx: 0, action: 'action.selfTrain', brief: '수련', arg: { stat: 'strength' } };
let slots: ReservedSlot[];
let readRing: () => Promise<Response>;
let writes: { turnIdx: number; arg: Record<string, unknown> }[];
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
});
const ring = () => json({ result: true, generalId: 1, slots });

beforeEach(() => {
    slots = [{ ...original, arg: { ...original.arg } }];
    writes = [];
    readRing = async () => ring();
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
        const path = new URL(url, 'http://localhost');
        if (path.pathname === '/api/game/api/reserved-commands') return readRing();
        if (path.pathname === '/api/game/api/commands/self-train-options') return json({
            inputId: 'action.selfTrain', available: true,
            stats: [{ stat: 'leadership', available: true }, { stat: 'strength', available: true }],
        });
        if (path.pathname === '/api/game/api/command/action.selfTrain' && init?.method === 'POST') {
            const turnIdx = Number(path.searchParams.get('turnIdx'));
            const arg = JSON.parse(String(init.body)) as Record<string, unknown>;
            writes.push({ turnIdx, arg });
            // Same-slot replacement is the server's reserve contract, not a claimed live DB execution.
            slots = [...slots.filter(slot => slot.turnIdx !== turnIdx), { turnIdx, action: 'action.selfTrain', brief: '수련', arg }];
            return json({ status: 'AVAILABLE', requestId, turnIdx }, 202);
        }
        if (path.pathname === `/api/game/api/command/result/${requestId}`) return json({
            status: 'PENDING', requestId, phase: 'reservationAccepted',
        });
        if (path.pathname === '/api/game/api/map-preview') return json({ cities: [], nations: [] });
        if (path.pathname === '/api/game/api/game-const') return json({ gameUnitConst: [] });
        return json({}, 404);
    }));
});
afterEach(() => vi.unstubAllGlobals());

const submit = () => screen.getByRole('button', { name: /순에 예약$/ });
const chooseTraining = async () => fireEvent.click(await screen.findByRole('option', { name: '통솔' }));
const unchanged = () => {
    expect(writes).toEqual([]);
    expect(slots).toEqual([original]);
};

test.each([false, true])('initial loading never submits to an unverified slot (explicit slot=%s)', async explicit => {
    let resolve = (_: Response) => {};
    readRing = () => new Promise<Response>(r => { resolve = r; });
    render(<CommandFlow generalId={1} initialInputId="action.selfTrain" initialSlot={explicit ? 0 : undefined} onClose={vi.fn()} />);
    await chooseTraining();
    expect(screen.getByRole('status', { name: '12순을 불러오는 중' })).toBeInTheDocument();
    fireEvent.click(submit());
    await act(async () => {});
    unchanged();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await act(async () => { readRing = async () => ring(); resolve(ring()); });
    await screen.findByRole('button', { name: '01순 — 무력 단련' });
    if (explicit) {
        fireEvent.click(submit());
        expect(await screen.findByText('01순을 바꿉니다')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '그대로 두기' }));
        unchanged();
    } else {
        await waitFor(() => expect(submit()).toHaveTextContent('02순에 예약'));
    }
});

test('failed read preserves the original reservation; retry requires confirmation and cancel writes nothing', async () => {
    readRing = async () => json({}, 503);
    render(<CommandFlow generalId={1} initialInputId="action.selfTrain" initialSlot={0} onClose={vi.fn()} />);
    await chooseTraining();
    await screen.findByText('12순을 불러오지 못했습니다');
    fireEvent.click(submit());
    await waitFor(() => expect(submit()).not.toHaveAttribute('aria-busy', 'true'), { timeout: 9000 });
    unchanged();
    readRing = async () => ring();
    fireEvent.click(screen.getByRole('button', { name: /다시/ }));
    await screen.findByTestId('slot-reserved');
    fireEvent.click(submit());
    expect(await screen.findByText('01순을 바꿉니다')).toBeInTheDocument();
    unchanged();
    fireEvent.click(screen.getByRole('button', { name: '그대로 두기' }));
    unchanged();
    fireEvent.click(submit());
    fireEvent.click(await screen.findByRole('button', { name: '바꾸기' }));
    await waitFor(() => expect(writes).toEqual([{ turnIdx: 0, arg: { stat: 'leadership' } }]));
    expect(slots[0].arg).toEqual({ stat: 'leadership' });
    await screen.findByText(/01순에 예약했습니다/, {}, { timeout: 9000 });
});

test('empty-slot reservation keeps the original and advances to the next empty slot', async () => {
    render(<CommandFlow generalId={1} initialInputId="action.selfTrain" onClose={vi.fn()} />);
    await chooseTraining();
    await waitFor(() => expect(submit()).toHaveTextContent('02순에 예약'));
    fireEvent.click(submit());
    await waitFor(() => expect(writes).toEqual([{ turnIdx: 1, arg: { stat: 'leadership' } }]));
    expect(slots.find(slot => slot.turnIdx === 0)).toEqual(original);
    await waitFor(() => expect(submit()).toHaveTextContent('03순에 예약'), { timeout: 9000 });
});

test('an open replacement confirmation cannot submit after reservation refresh fails', async () => {
    render(<CommandFlow generalId={1} initialInputId="action.selfTrain" initialSlot={0} onClose={vi.fn()} />);
    await chooseTraining();
    await screen.findByTestId('slot-reserved');
    fireEvent.click(submit());
    await screen.findByText('01순을 바꿉니다');
    readRing = async () => json({}, 503);
    await act(async () => announceTurnSlotsChanged());
    await screen.findByText('12순을 불러오지 못했습니다');
    fireEvent.click(screen.getByRole('button', { name: '바꾸기' }));
    await act(async () => {});
    unchanged();
});
