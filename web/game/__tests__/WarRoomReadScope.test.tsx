// War room → real CommandFlow: a new turn or world generation from front-info re-reads the open command's options and
// the 12-slot ring, and drops the destination map picked in the old scope. Only the shell, the map renderers, the session
// and HTTP are replaced.
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import WarRoomPage from '../components/campaign/WarRoomPage';
import type { ArgField } from '../lib/command-flow/options';
import { farm } from './fixtures/reservation-cancel';

vi.setConfig({ testTimeout: 20_000 });
// The address decides whether the flow is open; tests close and reopen it by changing it.
const FLOW = 'do=action.move&slot=4';
const address = vi.hoisted(() => ({ search: '' }));
vi.mock('next/navigation', () => ({
    usePathname: () => '/game/pep',
    useSearchParams: () => new URLSearchParams(address.search),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));
vi.mock('../components/GameShell', () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));
vi.mock('../components/campaign/WarRoomMap', () => ({ default: () => <div /> }));
// Map renderer stand-in: the candidates it was handed, one button each; the last onConfirm · onClose are kept to call them late.
const picker = vi.hoisted(() => ({ confirm: null as null | ((value: string) => void), close: null as null | (() => void) }));
vi.mock('../components/command-flow/CommandDestinationMap', () => ({
    default: ({ field, onConfirm, onClose }: { field: ArgField; onConfirm: (value: string) => void; onClose: () => void }) => {
        picker.confirm = onConfirm;
        picker.close = onClose;
        return <div role="dialog" aria-label="목적지 지도에서 고르기">
            {field.candidates.filter(c => c.available).map(c => <button key={c.value} type="button" onClick={() => onConfirm(c.value)}>{c.value}</button>)}
        </div>;
    },
}));
const session = vi.hoisted(() => ({ state: null as null | Record<string, unknown> }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => session.state }));

type Calendar = { readonly month: number; readonly turnPhase: number; readonly generation: number };
const START: Calendar = { month: 3, turnPhase: 2, generation: 7 };
const at = (calendar: Calendar) => {
    session.state = {
        generalId: 7, serverId: 'pep', loading: false, error: null, refresh: () => {},
        frontInfo: {
            global: { year: 200, ...calendar },
            general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, picture: null, imageServer: 0 },
            nation: { id: 1, name: '조조', color: '#4f7fbf' },
            city: { id: 3, name: '양성현', level: 2, nationId: 1, region: 0 }, recentRecord: {},
        },
    };
};

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const destination = (provinceId: string, available: boolean) => ({
    provinceId, name: provinceId, available, reason: available ? null : '길이 끊겼습니다',
    reachability: 'MULTI_TURN', arrivesThisTurn: false, estimatedTurns: 2,
});
const moveOptions = (p2: boolean) => ({ inputId: 'action.move', available: true, destinations: [destination('P-2', p2), destination('P-4', true)] });
const requestId = '00000000-0000-4000-8000-000000000501';

let moveReads: number;
/** Each move-options read takes the next planned answer; an unplanned read answers with everything available. */
let planned: Promise<Response>[];
/** Same for the 12-slot ring; an unplanned read answers with twelve empty slots. */
let plannedRings: Promise<Response>[];
let writes: { turnIdx: number; arg: Record<string, unknown> }[];
let viewport: ReturnType<typeof installViewport>;
const hold = () => {
    let release!: (body: unknown) => void;
    planned.push(new Promise<Response>(resolve => { release = body => resolve(json(body)); }));
    return release;
};
const holdRing = () => {
    let release!: (body: unknown, status?: number) => void;
    plannedRings.push(new Promise<Response>(resolve => { release = (body, status) => resolve(json(body, status)); }));
    return release;
};
const EMPTY_RING = { result: true, generalId: 7, slots: [] };
/** 04순(turnIdx 3) already holds another reservation. */
const TAKEN_RING = { result: true, generalId: 7, slots: [farm(3)] };

// The result poll (20 × 300 ms) runs on a fake setTimeout that also follows real time, so RTL's waits keep their real budget
// while accepted() and afterEach can finish the poll at once. On real timers alone every submit waited the whole poll; under
// load that outran accepted(), and the abandoned submit kept the module-wide slot lock and took the next test's ring answer.
const RESULT_POLL_MS = 20 * 300;
beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    vi.clearAllMocks();
    picker.confirm = null; picker.close = null;
    address.search = FLOW;
    at(START);
    viewport = installViewport(1440);
    document.cookie = 'sam_server=pep; path=/';
    moveReads = 0; planned = []; plannedRings = []; writes = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
        const path = new URL(url, 'http://localhost');
        if (path.pathname === '/api/game/api/reserved-commands') return plannedRings.shift() ?? json(EMPTY_RING);
        if (path.pathname === '/api/game/api/commands/move-options') {
            moveReads += 1;
            return planned.shift() ?? json(moveOptions(true));
        }
        if (path.pathname === '/api/game/api/command/action.move' && init?.method === 'POST') {
            writes.push({ turnIdx: Number(path.searchParams.get('turnIdx')), arg: JSON.parse(String(init.body)) as Record<string, unknown> });
            return json({ status: 'AVAILABLE', requestId, turnIdx: Number(path.searchParams.get('turnIdx')) }, 202);
        }
        // The reservation answer the server gives (CommandController): PENDING with phase reservationAccepted, never RESOLVED.
        if (path.pathname === `/api/game/api/command/result/${requestId}`) return json({ status: 'PENDING', requestId, phase: 'reservationAccepted' });
        if (path.pathname === '/api/game/api/map/preview') return json({ cities: [], nations: [] });
        if (path.pathname === '/api/game/api/const') return json({ gameUnitConst: [] });
        return json({}, 503);
    }));
});
afterEach(async () => {
    // A submit still polling here (a failed test) finishes now and releases its slot lock before the next test starts.
    await act(() => vi.advanceTimersByTimeAsync(RESULT_POLL_MS));
    vi.useRealTimers();
    viewport.restore();
    vi.unstubAllGlobals();
    document.cookie = 'sam_server=; max-age=0; path=/';
});

const MISSING = '「어디로」을 고르세요.';
const flow = () => within(screen.getByTestId('command-flow'));
const submit = () => flow().getByRole('button', { name: /순에 예약$/ });
const mapDialog = () => screen.queryByRole('dialog', { name: '목적지 지도에서 고르기' });
/** The destination field (and its map button) is drawn only once the current scope's options are READY. */
const optionsReady = () => screen.findByRole('button', { name: '지도에서 고르기' });
const accepted = async () => {
    await act(() => vi.advanceTimersByTimeAsync(RESULT_POLL_MS));
    return screen.findByText(/04순(에 예약했습니다| 예약은 접수했습니다)/, {}, { timeout: 9000 });
};
const READING = '12순을 불러오는 중';
/** The flow's 12-slot strip shows a ring read in the current scope (while a new read is pending it shows only a skeleton). */
const slotsReady = () => flow().findByRole('group', { name: '명령 목록 12순' });
const OVERWRITE = '04순을 바꿉니다';
const openMap = async () => {
    fireEvent.click(await screen.findByRole('button', { name: '지도에서 고르기' }));
    expect(mapDialog()).toBeInTheDocument();
};
/** Opens the move flow, picks P-2 on the destination map, and leaves the map open again. */
const openWithP2 = async () => {
    const view = render(<WarRoomPage />);
    await openMap();
    fireEvent.click(within(mapDialog()!).getByRole('button', { name: 'P-2' }));
    expect(mapDialog()).not.toBeInTheDocument();
    await openMap();
    expect(moveReads).toBe(1);
    return view;
};
const moveTo = (view: ReturnType<typeof render>, calendar: Calendar) => act(() => {
    at(calendar);
    view.rerender(<WarRoomPage />);
});

test.each([
    ['turn phase', { ...START, turnPhase: 3 }],
    ['month', { ...START, month: 4, turnPhase: 1 }],
    ['world generation', { ...START, generation: 8 }],
])('a new %s closes the open destination map in that render and re-reads the move options', async (_, next) => {
    const view = await openWithP2();
    const release = hold();
    moveTo(view, next);
    expect(mapDialog()).not.toBeInTheDocument();
    await waitFor(() => expect(moveReads).toBe(2));
    await act(async () => release(moveOptions(true)));
    expect(await optionsReady()).toBeInTheDocument();
    expect(mapDialog()).not.toBeInTheDocument();
});

test('a destination the new turn no longer offers is cleared and never posted', async () => {
    const view = await openWithP2();
    const release = hold();
    moveTo(view, { ...START, turnPhase: 3 });
    await waitFor(() => expect(moveReads).toBe(2));
    await act(async () => release(moveOptions(false)));
    await optionsReady();
    expect(await flow().findByText(MISSING)).toBeInTheDocument();
    fireEvent.click(submit());
    await act(async () => {});
    expect(writes).toEqual([]);
});

test('a still-offered destination stays; a confirm captured in the old turn cannot overwrite it; one POST', async () => {
    const view = await openWithP2();
    const stale = picker.confirm!;
    moveTo(view, { ...START, turnPhase: 3 });
    act(() => stale('P-4'));
    await waitFor(() => expect(moveReads).toBe(2));
    await optionsReady();
    expect(flow().queryByText(MISSING)).not.toBeInTheDocument();
    await slotsReady();
    fireEvent.click(submit());
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toEqual({ turnIdx: 3, arg: expect.objectContaining({ destinationProvinceId: 'P-2' }) });
    await accepted();
    expect(writes).toHaveLength(1);
});

test('a re-render in the same scope (a new front-info object with the same values) neither re-reads nor closes the map', async () => {
    const view = await openWithP2();
    for (let i = 0; i < 3; i++) moveTo(view, { ...START });
    await act(async () => {});
    expect(moveReads).toBe(1);
    expect(mapDialog()).toBeInTheDocument();
});

test('A→B→A: the late answer of the abandoned turn read does not clear the current draft', async () => {
    const view = await openWithP2();
    const releaseB = hold();
    const releaseA = hold();
    moveTo(view, { ...START, turnPhase: 3 });
    await waitFor(() => expect(moveReads).toBe(2));
    moveTo(view, START);
    await waitFor(() => expect(moveReads).toBe(3));
    await act(async () => releaseA(moveOptions(true)));
    await act(async () => releaseB(moveOptions(false)));
    await optionsReady();
    expect(flow().queryByText(MISSING)).not.toBeInTheDocument();
    await slotsReady();
    fireEvent.click(submit());
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0].arg).toEqual(expect.objectContaining({ destinationProvinceId: 'P-2' }));
    await accepted();
});

// ── 12순 읽기: 새 순 · 세대의 예약 링이 오기 전에는 옛 링으로 순을 검증하지 않는다(덮어쓰기 확인 보존) ──

test('after a new generation the old empty ring never verifies the slot: no POST while the new ring is read, and the reservation it brings needs confirmation', async () => {
    const view = await openWithP2();
    const release = holdRing();
    moveTo(view, { ...START, generation: 8 });
    await optionsReady();
    expect(flow().getByRole('status', { name: READING })).toBeInTheDocument();
    fireEvent.click(submit());
    await act(async () => {});
    expect(writes).toEqual([]);
    expect(flow().getByText(/12순을 불러오는 중입니다/)).toBeInTheDocument();
    await act(async () => release(TAKEN_RING));
    await slotsReady();
    fireEvent.click(submit());
    expect(await screen.findByText(OVERWRITE)).toBeInTheDocument();
    expect(writes).toEqual([]);
    fireEvent.click(screen.getByRole('button', { name: '바꾸기' }));
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0]).toEqual({ turnIdx: 3, arg: expect.objectContaining({ destinationProvinceId: 'P-2' }) });
    await accepted();
});

test.each([401, 403, 503])('a new-turn ring read that is pending and then fails (%s) never falls back to the old ring', async status => {
    const view = await openWithP2();
    const release = holdRing();
    moveTo(view, { ...START, turnPhase: 3 });
    await optionsReady();
    fireEvent.click(submit());
    await act(async () => {});
    expect(writes).toEqual([]);
    await act(async () => release({}, status));
    expect(await flow().findByText('12순을 불러오지 못했습니다')).toBeInTheDocument();
    fireEvent.click(submit());
    await act(async () => {});
    expect(writes).toEqual([]);
    expect(flow().getByText(/12순을 확인하지 못했습니다/)).toBeInTheDocument();
});

test('A→B→A ring reads: the abandoned turn\'s empty ring never verifies the slot; the current turn\'s ring asks to overwrite', async () => {
    const view = await openWithP2();
    const releaseB = holdRing();
    const releaseA = holdRing();
    moveTo(view, { ...START, turnPhase: 3 });
    await waitFor(() => expect(moveReads).toBe(2));
    moveTo(view, START);
    await waitFor(() => expect(moveReads).toBe(3));
    await optionsReady();
    await act(async () => releaseB(EMPTY_RING));
    expect(flow().getByRole('status', { name: READING })).toBeInTheDocument();
    fireEvent.click(submit());
    await act(async () => {});
    expect(writes).toEqual([]);
    await act(async () => releaseA(TAKEN_RING));
    await slotsReady();
    fireEvent.click(submit());
    expect(await screen.findByText(OVERWRITE)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '그대로 두기' }));
    await act(async () => {});
    expect(writes).toEqual([]);
});

// ── 목적지 지도: 늦게 불린 옛 지도의 확인 · 닫기는 그 지도에만 — 지금 열린 지도와 초안은 그대로 ──

test('a confirm or close kept from the old turn\'s map neither writes into nor closes the map opened in the new turn', async () => {
    const view = await openWithP2();
    const staleConfirm = picker.confirm!;
    const staleClose = picker.close!;
    moveTo(view, { ...START, turnPhase: 3 });
    await openMap();
    act(() => staleConfirm('P-4'));
    act(() => staleClose());
    expect(mapDialog()).toBeInTheDocument();
    act(() => picker.close!());
    expect(mapDialog()).not.toBeInTheDocument();
    await slotsReady();
    fireEvent.click(submit());
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0].arg).toEqual(expect.objectContaining({ destinationProvinceId: 'P-2' }));
    await accepted();
});

test('closing the map and reopening it in the same turn retires the first map\'s confirm and close', async () => {
    await openWithP2();
    const staleConfirm = picker.confirm!;
    const staleClose = picker.close!;
    act(() => staleClose());
    expect(mapDialog()).not.toBeInTheDocument();
    await openMap();
    act(() => staleConfirm('P-4'));
    act(() => staleClose());
    expect(mapDialog()).toBeInTheDocument();
    act(() => picker.close!());
    expect(mapDialog()).not.toBeInTheDocument();
    fireEvent.click(submit());
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0].arg).toEqual(expect.objectContaining({ destinationProvinceId: 'P-2' }));
    await accepted();
});

test('closing and reopening the whole flow keeps the new map from the old map\'s late confirm and close', async () => {
    const view = await openWithP2();
    const staleConfirm = picker.confirm!;
    const staleClose = picker.close!;
    act(() => { address.search = ''; view.rerender(<WarRoomPage />); });
    expect(screen.queryByTestId('command-flow')).not.toBeInTheDocument();
    expect(mapDialog()).not.toBeInTheDocument();
    act(() => { address.search = FLOW; view.rerender(<WarRoomPage />); });
    await openMap();
    act(() => staleConfirm('P-4'));
    act(() => staleClose());
    expect(mapDialog()).toBeInTheDocument();
    fireEvent.click(within(mapDialog()!).getByRole('button', { name: 'P-2' }));
    expect(mapDialog()).not.toBeInTheDocument();
    await slotsReady();
    fireEvent.click(submit());
    await waitFor(() => expect(writes).toHaveLength(1));
    expect(writes[0].arg).toEqual(expect.objectContaining({ destinationProvinceId: 'P-2' }));
    await accepted();
});
