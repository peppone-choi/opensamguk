import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import CommandFlow from '../components/command-flow/CommandFlow';

vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep', useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

// Only the HTTP boundary is replaced. CommandFlow, options, API client and result polling are real.
const fort = (id: string, provinceId: string, provinceName: string | null | undefined, canBesiege = true) => ({
    id, edgeId: `E-${id}`, provinceId, ...(provinceName === undefined ? {} : { provinceName }),
    row: 0, col: 0, ownerNationId: 2, wall: 120, garrison: 45, besiegerGeneralId: null, siegeProgress: 0, canBesiege,
});

function serve(forts: unknown[]) {
    const posts: { path: string; generalId: string | null; body: unknown }[] = [];
    let stored: unknown = null;
    const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status,
        headers: { 'Content-Type': 'application/json' } });
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
        const url = new URL(input, 'http://localhost');
        const path = url.pathname;
        if (path === '/api/game/api/reserved-commands') return response({ result: true, generalId: 7,
            slots: stored ? [{ turnIdx: 0, action: 'action.siegeRoadFort', brief: '', arg: stored, revision: '00000000-0000-4000-8000-0000000000f0' }] : [] });
        if (path === '/api/game/api/road-forts') return response({ status: 'READY', roadMode: true, gates: [], forts });
        if (path === '/api/game/api/command/action.siegeRoadFort' && init?.method === 'POST') {
            stored = JSON.parse(String(init.body));
            posts.push({ path, generalId: url.searchParams.get('generalId'), body: stored });
            return response({ status: 'AVAILABLE', requestId: 'road-fort-test', turnIdx: 0 }, 202);
        }
        if (path === '/api/game/api/command/result/road-fort-test') return response({
            status: 'RESOLVED', requestId: 'road-fort-test', ok: true, type: 'reservationAccepted',
            result: { commandKind: 'RESERVED_TURN' },
        });
        return response({}, 404);
    }));
    return posts;
}

const submitButton = () => screen.getByTestId('command-flow').querySelector('[data-input-status]')!;

test('같은 지명의 두 보루는 따로 보이고, 고른 보루의 fortId만 예약으로 보낸다', async () => {
    const posts = serve([fort('F-1', 'P-1', '하비'), fort('F-2', 'P-2', ' 하비 '), fort('F-3', 'P-3', '소패', false)]);
    render(<CommandFlow generalId={7} initialInputId="action.siegeRoadFort" onClose={vi.fn()} />);
    const listbox = await screen.findByRole('listbox', { name: '에울 보루' });
    const options = await waitFor(() => {
        const found = screen.getAllByRole('option', { name: /보루 · 하비/ });
        expect(found).toHaveLength(2); return found;
    });
    expect(options.map(o => o.getAttribute('data-value'))).toEqual(['F-1', 'F-2']);
    expect(listbox).toHaveTextContent('성벽 120 · 수비 45');
    // canBesiege=false is filtered out, and internal province ids never stand in for a name.
    expect(listbox).not.toHaveTextContent('소패');
    expect(listbox).not.toHaveTextContent(/P-\d/);

    fireEvent.click(options[1]);
    await waitFor(() => expect(submitButton()).toHaveTextContent('01순에 예약'));
    fireEvent.click(submitButton());
    await waitFor(() => expect(posts).toEqual([
        { path: '/api/game/api/command/action.siegeRoadFort', generalId: '7', body: { fortId: 'F-2' } },
    ]));
    // Intake acceptance is a reservation, not an executed siege.
    expect(await screen.findByText(/01순에 예약했습니다/)).toBeInTheDocument();
    expect(screen.queryByText(/포위했습니다/)).not.toBeInTheDocument();
});

test('이름이 없거나 비면 고정 문구로 보이고 값은 각 보루 id다', async () => {
    serve([fort('F-1', 'P-11', undefined), fort('F-2', 'P-12', null), fort('F-3', 'P-13', '   ')]);
    render(<CommandFlow generalId={7} initialInputId="action.siegeRoadFort" onClose={vi.fn()} />);
    const options = await waitFor(() => {
        const found = screen.getAllByRole('option', { name: /보루 · 장소 이름 확인 불가/ });
        expect(found).toHaveLength(3); return found;
    });
    expect(options.map(o => o.getAttribute('data-value'))).toEqual(['F-1', 'F-2', 'F-3']);
    expect(screen.getByRole('listbox', { name: '에울 보루' })).not.toHaveTextContent(/P-1\d/);
});

test('에울 수 있는 보루가 없으면 이름이 있어도 막히고 보내지 않는다', async () => {
    const posts = serve([fort('F-1', 'P-1', '하비', false)]);
    render(<CommandFlow generalId={7} initialInputId="action.siegeRoadFort" onClose={vi.fn()} />);
    const submit = await waitFor(() => {
        const node = submitButton();
        expect(node).toHaveAttribute('data-input-status', 'BLOCKED'); return node;
    });
    expect(screen.queryByRole('option', { name: /하비/ })).not.toBeInTheDocument();
    fireEvent.click(submit);
    expect(posts).toEqual([]);
});
