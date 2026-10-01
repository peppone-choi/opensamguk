// 계책 덱 페이지(/game/stratagem) — 셸 세션의 장수로 손패를 읽어 덱을 그리고, 실패하면 다시 시도로 다시 읽는다.
// 옛 손패 화면의 지어낸 「비용」 칩 · 네이티브 disabled 「쓰기」(title 전용 사유)는 없다.
import { configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import StratagemPage from '@/app/game/(campaign)/stratagem/page';
import { api } from '@/lib/api';
import { useGameSession, type GameSession } from '@/lib/campaign-session';

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });
vi.mock('next/navigation', () => ({
    usePathname: () => '/game/stratagem',
    useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: vi.fn() }));
vi.mock('@/lib/api', () => ({ api: { stratagemHand: vi.fn() } }));

const hand = { status: 'READY', handLimit: 5, canUse: false, cards: [{ instanceId: 1, type: 'FORTIFY', label: '견벽' }, { instanceId: 2, type: 'INSIGHT', label: '간파' }] };

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useGameSession).mockReturnValue({
        loading: false, error: null, serverId: undefined, gameDate: '', refresh: vi.fn(), generalId: 1,
        frontInfo: { global: { year: 200, month: 3, turnPhase: 1 }, general: { hasGeneral: true, generalId: 1, nationId: 1 } },
    } as unknown as GameSession);
    vi.mocked(api.stratagemHand).mockResolvedValue(hand as never);
});

test('제목 「계책 덱」 · 손패 수 / 한도 · 옛 비용 칩과 네이티브 disabled 「쓰기」는 없다', async () => {
    const { container } = render(<StratagemPage />);
    expect(screen.getByRole('heading', { name: '계책 덱' })).toBeInTheDocument();
    expect(await screen.findByText('손패 2 / 5')).toBeInTheDocument();
    expect(screen.queryByText('비용')).toBeNull();
    expect(container.querySelector('button:disabled')).toBeNull();
    expect(container.querySelector('[title]')).toBeNull();
});

test('손패를 못 읽으면 실패 모양 · 다시 시도로 다시 읽는다', async () => {
    vi.mocked(api.stratagemHand).mockRejectedValueOnce(new Error('500'));
    render(<StratagemPage />);
    fireEvent.click(await screen.findByRole('button', { name: /다시 시도/ }));
    await waitFor(() => expect(api.stratagemHand).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('손패 2 / 5')).toBeInTheDocument();
});
