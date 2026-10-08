import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import { useSyncExternalStore, type ReactNode } from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import WarRoomPage from '../components/campaign/WarRoomPage';
import type { ReservedSlot } from '../lib/types';

vi.setConfig({ testTimeout: 20_000 });
const nav = vi.hoisted(() => ({
    search: 'do=action.selfTrain&slot=2',
    listeners: new Set<() => void>(),
    push: vi.fn(), replace: vi.fn(),
}));
vi.mock('next/navigation', () => ({
    usePathname: () => '/game/pep/war-room',
    useSearchParams: () => new URLSearchParams(nav.search),
    useRouter: () => ({ push: nav.push, replace: nav.replace, back: vi.fn() }),
}));
// Only unrelated shell/map rendering and the session/platform/HTTP boundary are replaced.
// WarRoomPage, its mobile slot producer, URL hook, CommandFlow and command client remain real.
vi.mock('../components/GameShell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));
vi.mock('../components/campaign/WarRoomMap', () => ({ default: () => <div /> }));
vi.mock('../lib/campaign-session', () => ({
    useGameSession: () => ({
        generalId: 7, serverId: 'pep', loading: false, error: null, refresh: () => {},
        frontInfo: {
            global: { year: 200, month: 3, turnPhase: 2 },
            general: { hasGeneral: true, generalId: 7, name: '장수', nationId: 1, picture: null, imageServer: 0 },
            nation: { id: 1, name: '세력', color: '#4f7fbf' },
            city: { id: 3, name: '현', level: 2, nationId: 1, region: 0 }, recentRecord: {},
        },
    }),
}));

const navigate = (href: string) => {
    nav.search = new URL(href, 'http://localhost').search.slice(1);
    for (const notify of nav.listeners) notify();
};
function RoutedRoom() {
    useSyncExternalStore(
        notify => { nav.listeners.add(notify); return () => { nav.listeners.delete(notify); }; },
        () => nav.search,
    );
    return <WarRoomPage />;
}
const original: ReservedSlot = { turnIdx: 0, action: 'action.selfTrain', brief: '수련', arg: { stat: 'strength' } };
const requestId = '00000000-0000-4000-8000-000000000338';
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
});
let slots: ReservedSlot[];
let writes: { turnIdx: number; arg: Record<string, unknown> }[];
let deny = false;
let viewport: ReturnType<typeof installViewport>;
beforeEach(() => {
    vi.clearAllMocks();
    nav.search = 'do=action.selfTrain&slot=2';
    nav.push.mockImplementation(navigate);
    nav.replace.mockImplementation(navigate);
    viewport = installViewport(390);
    document.cookie = 'sam_server=pep; path=/';
    slots = [{ ...original, arg: { ...original.arg } }];
    writes = [];
    deny = false;
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
        const path = new URL(url, 'http://localhost');
        if (path.pathname === '/api/game/api/reserved-commands') return json({ result: true, generalId: 7, slots });
        if (path.pathname === '/api/game/api/commands/self-train-options') return json({
            inputId: 'action.selfTrain', available: true,
            stats: [{ stat: 'leadership', available: true }, { stat: 'strength', available: true }],
        });
        if (path.pathname === '/api/game/api/command/action.selfTrain' && init?.method === 'POST') {
            const turnIdx = Number(path.searchParams.get('turnIdx'));
            const arg = JSON.parse(String(init.body)) as Record<string, unknown>;
            writes.push({ turnIdx, arg });
            if (deny) return json({ status: 'BLOCKED', code: 'STATE_UNAVAILABLE', reason: '현재 상태에서 예약할 수 없습니다.' });
            slots = [...slots.filter(s => s.turnIdx !== turnIdx), { turnIdx, action: 'action.selfTrain', brief: '수련', arg }];
            return json({ status: 'AVAILABLE', requestId, turnIdx }, 202);
        }
        if (path.pathname === `/api/game/api/command/result/${requestId}`) return json({
            status: 'PENDING', requestId, phase: 'reservationAccepted',
        });
        if (path.pathname === '/api/game/api/map/preview') return json({ cities: [], nations: [] });
        if (path.pathname === '/api/game/api/const') return json({ gameUnitConst: [] });
        return json({}, 503);
    }));
});
afterEach(() => {
    viewport.restore();
    vi.unstubAllGlobals();
    document.cookie = 'sam_server=; max-age=0; path=/';
});
const submit = () => within(screen.getByTestId('command-flow')).getByRole('button', { name: /순에 예약$/ });
const openRoom = async () => {
    render(<RoutedRoom />);
    fireEvent.click(await screen.findByRole('option', { name: '통솔' }));
    await waitFor(() => expect(submit()).toHaveTextContent('02순에 예약'));
};
const selectOutside = async (label: string) => {
    const peek = screen.getByRole('region', { name: '명령 목록 12순 — 다음 순' });
    fireEvent.click(within(peek).getByRole('button', { name: '12순 · 맡겨 둔 일' }));
    const sheet = await screen.findByRole('dialog', { name: '명령 목록 12순 · 맡겨 둔 일' });
    fireEvent.click(within(sheet).getByRole('button', { name: label }));
    await act(async () => {});
};

test('mounted mobile outside slot selection submits to that URL slot and preserves the original reservation', async () => {
    await openRoom();
    await selectOutside('06순 — 빈 순');
    expect(new URLSearchParams(nav.search).get('slot')).toBe('6');
    fireEvent.click(submit());
    await waitFor(() => expect(writes).toEqual([{ turnIdx: 5, arg: { stat: 'leadership' } }]));
    expect(slots.find(s => s.turnIdx === 0)).toEqual(original);
    expect(slots.find(s => s.turnIdx === 1)).toBeUndefined();
    await screen.findByText(/06순에 예약했습니다/, {}, { timeout: 9000 });
});

test('history slot changes keep the command and draft, and the actual POST uses the new slot', async () => {
    await openRoom();
    await act(async () => navigate('/game/pep/war-room?do=action.selfTrain&slot=4'));
    await waitFor(() => expect(submit()).toHaveTextContent('04순에 예약'));
    await act(async () => navigate('/game/pep/war-room?do=action.selfTrain&slot=8'));
    await waitFor(() => expect(submit()).toHaveTextContent('08순에 예약'));
    expect(screen.getByRole('option', { name: '통솔' })).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(submit());
    await waitFor(() => expect(writes).toEqual([{ turnIdx: 7, arg: { stat: 'leadership' } }]));
    expect(slots.find(s => s.turnIdx === 0)).toEqual(original);
    await screen.findByText(/08순에 예약했습니다/, {}, { timeout: 9000 });
});

test('a denied submission still targets the outside selection and leaves the ring unchanged', async () => {
    await openRoom();
    await selectOutside('06순 — 빈 순');
    deny = true;
    fireEvent.click(submit());
    await waitFor(() => expect(writes).toEqual([{ turnIdx: 5, arg: { stat: 'leadership' } }]));
    expect(slots).toEqual([original]);
    await waitFor(() => expect(submit()).not.toHaveAttribute('aria-busy', 'true'));
    expect(screen.queryByText(/06순에 예약했습니다/)).not.toBeInTheDocument();
});

test('outside selection of a filled slot requires confirmation; cancel preserves both slots', async () => {
    await openRoom();
    await selectOutside('01순 — 무력 단련');
    await waitFor(() => expect(submit()).toHaveTextContent('01순에 예약'));
    fireEvent.click(submit());
    await screen.findByText('01순을 바꿉니다');
    expect(writes).toEqual([]);
    fireEvent.click(screen.getByRole('button', { name: '그대로 두기' }));
    expect(writes).toEqual([]);
    expect(slots).toEqual([original]);
    fireEvent.click(submit());
    fireEvent.click(await screen.findByRole('button', { name: '바꾸기' }));
    await waitFor(() => expect(writes).toEqual([{ turnIdx: 0, arg: { stat: 'leadership' } }]));
    expect(slots.find(s => s.turnIdx === 1)).toBeUndefined();
    await screen.findByText(/01순에 예약했습니다/, {}, { timeout: 9000 });
});

test('late echoes of internal slot changes do not revert the selection; a later outside change still works', async () => {
    await openRoom();
    nav.replace.mockImplementation(() => {});
    const strip = within(screen.getByTestId('turn-slots-strip'));
    fireEvent.click(strip.getByRole('button', { name: '04순 — 빈 순' }));
    const first = String(nav.replace.mock.calls.at(-1)?.[0]);
    fireEvent.click(strip.getByRole('button', { name: '05순 — 빈 순' }));
    const second = String(nav.replace.mock.calls.at(-1)?.[0]);
    expect(first).toContain('slot=4');
    expect(second).toContain('slot=5');
    await act(async () => navigate(first));
    expect(submit()).toHaveTextContent('05순에 예약');
    await act(async () => navigate(second));
    expect(submit()).toHaveTextContent('05순에 예약');
    await act(async () => navigate('/game/pep/war-room?do=action.selfTrain&slot=9'));
    await waitFor(() => expect(submit()).toHaveTextContent('09순에 예약'));
    expect(screen.getByRole('option', { name: '통솔' })).toHaveAttribute('aria-selected', 'true');
    expect(writes).toEqual([]);
    expect(slots).toEqual([original]);
});

test('an outside slot change dismisses a stale overwrite confirmation without writing', async () => {
    await openRoom();
    fireEvent.click(within(screen.getByTestId('turn-slots-strip')).getByRole('button', { name: '01순 — 무력 단련' }));
    fireEvent.click(submit());
    await screen.findByText('01순을 바꿉니다');
    await act(async () => navigate('/game/pep/war-room?do=action.selfTrain&slot=10'));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(submit()).toHaveTextContent('10순에 예약');
    expect(writes).toEqual([]);
    expect(slots).toEqual([original]);
});
