// 시야 · 첩보 페이지(/game/corps/intel) — 셸 세션의 장수로 시야 · 첩보 옵션을 읽어 군 목록을 그리고,
// 「첩보」 → 작전실 명령 흐름(?do=action.scout&target=commandery:<id>), 「정찰 보내기」 · 「망루 짓기」 → 영지(P-T01).
import { configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import IntelPage from '@/app/game/(campaign)/corps/intel/page';
import { api } from '@/lib/api';
import { useGameSession, type GameSession } from '@/lib/campaign-session';

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });
const push = vi.fn();
vi.mock('next/navigation', () => ({
    usePathname: () => '/game/corps/intel',
    useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ push, replace: vi.fn() }),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: vi.fn() }));
vi.mock('@/lib/api', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/lib/api')>()),
    api: { campaignVisibility: vi.fn(), campaignScoutOptions: vi.fn() },
}));

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useGameSession).mockReturnValue({
        loading: false, error: null, serverId: undefined, gameDate: '', refresh: vi.fn(), generalId: 7,
        frontInfo: { global: { year: 200, month: 3, turnPhase: 1 }, general: { hasGeneral: true, generalId: 7, nationId: 1 } },
    } as unknown as GameSession);
    vi.mocked(api.campaignVisibility).mockResolvedValue({ status: 'READY', commanderies: [
        { no: 1, id: 'c1', name: '영천군', tier: 'FULL' }, { no: 2, id: 'c2', name: '진류군', tier: 'INTEL', ageTurns: 3 },
    ] } as never);
    vi.mocked(api.campaignScoutOptions).mockResolvedValue({ status: 'READY', available: true, options: [{ no: 2, id: 'c2', name: '진류군', tier: 'INTEL', available: true }] } as never);
});

test('군 목록 · 지도 자리 — 「첩보」는 그 군을 미리 고른 작전실 흐름, 정찰 · 망루는 영지', async () => {
    render(<IntelPage />);
    expect(screen.getByRole('heading', { name: '시야 · 첩보' })).toBeInTheDocument();
    expect(screen.getByText('시야 지도 준비 중')).toBeInTheDocument();
    const intel = await screen.findByRole('region', { name: '첩보' });
    expect(intel).toHaveTextContent('3순 전 첩보');
    fireEvent.click(within(intel).getByRole('button', { name: '첩보' }));
    expect(push).toHaveBeenLastCalledWith('/game?do=action.scout&target=commandery%3Ac2');
    fireEvent.click(screen.getByRole('button', { name: '정찰 보내기' }));
    expect(push).toHaveBeenLastCalledWith('/game/territory');
    fireEvent.click(screen.getByRole('button', { name: '망루 짓기' }));
    expect(push).toHaveBeenLastCalledWith('/game/territory');
});

test('첩보 옵션을 못 읽으면 단추 없이 군만 · 시야 읽기 실패는 다시 시도, 원문(영어)은 보이지 않는다', async () => {
    vi.mocked(api.campaignScoutOptions).mockRejectedValue(new Error('500: Internal Server Error'));
    vi.mocked(api.campaignVisibility).mockRejectedValueOnce(new Error('500: Internal Server Error'));
    render(<IntelPage />);
    fireEvent.click(await screen.findByRole('button', { name: /다시 시도/ }));
    const intel = await screen.findByRole('region', { name: '첩보' });
    expect(within(intel).queryByRole('button', { name: '첩보' })).toBeNull();
    await waitFor(() => expect(api.campaignVisibility).toHaveBeenCalledTimes(2));
    expect(screen.queryByText(/Internal Server Error/)).toBeNull();
});
