import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, expect, test, vi } from 'vitest';
import CommandFlow from '../components/command-flow/CommandFlow';

vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep', useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());

function serve(blocked = false) {
    const posts: { path: string; body: unknown }[] = [];
    let stored: unknown = null;
    const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status,
        headers: { 'Content-Type': 'application/json' } });
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
        const path = new URL(input, 'http://localhost').pathname;
        if (path === '/api/game/api/reserved-commands') return response({ result: true, generalId: 7,
            slots: stored ? [{ turnIdx: 0, action: 'action.donate', brief: '', arg: stored, revision: '00000000-0000-4000-8000-0000000000e0' }] : [] });
        if (path === '/api/game/api/const') return response({ result: true, gameUnitConst: [] });
        if (path === '/api/game/api/map/preview') return response({ cities: [], nations: [] });
        if (path === '/api/game/api/commands/donate-options') return response({
            inputId: 'action.donate', available: !blocked, code: blocked ? 'STATE_UNAVAILABLE' : null,
            reason: blocked ? '현재 현 창고를 확인할 수 없습니다.' : null, targets: [],
            resources: [{ resource: 'MONEY', available: !blocked, maxAmount: 1000 },
                { resource: 'GRAIN', available: !blocked, maxAmount: 2000 }],
            donationRecipient: blocked ? null : { countyId: 11, countyName: '수령현', nationId: 1, nationName: '현 소유국' },
        });
        if (path === '/api/game/api/command/action.donate' && init?.method === 'POST') {
            stored = JSON.parse(String(init.body)); posts.push({ path, body: stored });
            return response({ status: 'AVAILABLE', requestId: 'donation-test', turnIdx: 0 }, 202);
        }
        if (path === '/api/game/api/command/result/donation-test') return response({
            status: 'RESOLVED', requestId: 'donation-test', ok: true, type: 'reservationAccepted',
            result: { commandKind: 'RESERVED_TURN' },
        });
        return response({}, 404);
    }));
    return posts;
}

test.each([['MONEY', '금', 100], ['GRAIN', '쌀', 600]] as const)(
    '헌납 %s의 실제 client body와 저장 readback은 수령 현을 표시한다', async (resource, label, amount) => {
        const posts = serve();
        render(<CommandFlow generalId={7} initialInputId="action.donate" onClose={vi.fn()} />);
        expect(await screen.findByText('일어나는 곳: 현 소유국 · 수령현 창고')).toBeInTheDocument();
        fireEvent.click(await screen.findByRole('option', { name: new RegExp(label) }));
        fireEvent.change(screen.getByRole('spinbutton', { name: '얼마나' }), { target: { value: String(amount) } });
        const submit = screen.getByTestId('command-flow').querySelector('[data-input-status]')!;
        await waitFor(() => expect(submit).toHaveTextContent('01순에 예약'));
        fireEvent.click(submit);
        await waitFor(() => expect(posts).toEqual([{ path: '/api/game/api/command/action.donate', body: { resource, amount } }]));
        expect(await screen.findByText(`「${label} ${amount} 헌납」 — 01순에 예약했습니다.`)).toBeInTheDocument();
    });

test('실제 창고 unavailable은 POST하지 않는다', async () => {
    const posts = serve(true);
    render(<CommandFlow generalId={7} initialInputId="action.donate" onClose={vi.fn()} />);
    const submit = await waitFor(() => {
        const node = screen.getByTestId('command-flow').querySelector('[data-input-status]')!;
        expect(node).toHaveAttribute('data-input-status', 'BLOCKED'); return node;
    });
    fireEvent.click(submit);
    expect(posts).toEqual([]);
});
