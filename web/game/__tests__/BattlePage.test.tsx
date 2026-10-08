// 전투 · 부재 대비 페이지(/game/corps/battle) · 전투 방(/game/corps/battle/[id]) — 셸 세션의 장수로 방침을 읽어 부재 대비를 그리고,
// 「방침 고치기」 → 영지(P-T01), 「대응 계책 칸 보기」 → 계책 덱(P-S01). 전투 목록 조회 실패와 전투 방 접속 불가를 구분한다.
import { configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import BattlePage from '@/app/game/(campaign)/corps/battle/page';
import BattleRoomPage from '@/app/game/(campaign)/corps/battle/[id]/page';
import { api } from '@/lib/api';
import { useGameSession, type GameSession } from '@/lib/campaign-session';

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });
const push = vi.fn();
vi.mock('next/navigation', () => ({
    usePathname: () => '/game/corps/battle',
    useSearchParams: () => new URLSearchParams(),
    useParams: () => ({ id: 'B-1' }),
    useRouter: () => ({ push, replace: vi.fn() }),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: vi.fn() }));
// 내 전투 목록(K6-11) 404는 조회 실패다. producer 가용성은 이 응답으로 추정하지 않는다.
vi.mock('@/lib/api', () => ({ api: { campaignPolicies: vi.fn() }, fetchGame: vi.fn(async () => new Response(null, { status: 404 })) }));

const policies = {
    status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: { code: 'DEFEND', label: '수비' },
    corps: [{ orderId: 'O-1', commanderName: '하후돈', active: { policy: 'INTERCEPT', label: '요격' }, pending: null, settable: true, blocked: null }],
    counties: [],
};

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useGameSession).mockReturnValue({
        loading: false, error: null, serverId: undefined, gameDate: '', refresh: vi.fn(), generalId: 7,
        frontInfo: { global: { year: 200, month: 3, turnPhase: 1 }, general: { hasGeneral: true, generalId: 7, nationId: 1 } },
    } as unknown as GameSession);
    vi.mocked(api.campaignPolicies).mockResolvedValue(policies as never);
});

test('전투 목록은 조회 실패 · 부재 대비는 내 군단 방침 · 고치러 가는 길은 영지와 계책 덱', async () => {
    render(<BattlePage />);
    expect(screen.getByRole('heading', { name: '전투 · 부재 대비' })).toBeInTheDocument();
    expect(await screen.findByText('전투 목록을 읽지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '오류 번호 HTTP_404 복사' })).toBeInTheDocument();
    expect(await screen.findByText('하후돈 군단')).toBeInTheDocument();
    expect(screen.getByText('요격')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '방침 고치기' }));
    expect(push).toHaveBeenLastCalledWith('/game/territory');
    fireEvent.click(screen.getByRole('button', { name: '대응 계책 칸 보기' }));
    expect(push).toHaveBeenLastCalledWith('/game/stratagem');
});

test('방침을 못 읽으면 실패 모양 · 다시 시도로 다시 읽는다', async () => {
    vi.mocked(api.campaignPolicies).mockRejectedValueOnce(new Error('500'));
    render(<BattlePage />);
    fireEvent.click(await within(screen.getByRole('complementary', { name: '부재 대비' })).findByRole('button', { name: /다시 시도/ }));
    await waitFor(() => expect(api.campaignPolicies).toHaveBeenCalledTimes(2));
    expect(await screen.findByText('하후돈 군단')).toBeInTheDocument();
});

test('전투 방에 전투 세계 번호(?world=) 없이 오면 「전투가 열리지 않습니다」 · 돌아가기는 전투 · 부재 대비(접속 시도 없음)', () => {
    render(<BattleRoomPage />);
    expect(screen.getByText('전투가 열리지 않습니다(서버 준비 중)')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '전투 · 부재 대비로' }));
    expect(push).toHaveBeenLastCalledWith('/game/corps/battle');
});
