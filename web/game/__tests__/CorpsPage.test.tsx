// 군단 · 세력 작전 페이지(/game/corps) — 셸 세션의 장수로 군단 · 시야 · 출병 · 방침 · 편성 해제 선택지를 읽어 군단 칸을 그리고,
// 「출병」 · 「부대 모으기」 → 작전실 명령 흐름(?do=), 「방침 바꾸기」 → 영지(P-T01), 편성 해제는 나(행위자)로 접수한다.
import { act, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import CorpsPage from '@/app/game/(campaign)/corps/page';
import { api } from '@/lib/api';
import { useGameSession, type GameSession } from '@/lib/campaign-session';

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });
const push = vi.fn();
const nav = vi.hoisted(() => ({ search: '' }));
vi.mock('next/navigation', () => ({
    usePathname: () => '/game/corps',
    useSearchParams: () => new URLSearchParams(nav.search),
    useRouter: () => ({ push, replace: vi.fn() }),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: vi.fn() }));
vi.mock('@/lib/api', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/lib/api')>()),
    api: {
        campaignCorps: vi.fn(), campaignVisibility: vi.fn(), deployOptions: vi.fn(), campaignPolicies: vi.fn(),
        legacyCourtOptions: vi.fn(), courtLegacy: vi.fn(),
    },
}));

const corps = {
    status: 'READY',
    corps: [{ corpsId: 'O-1', ownerGeneralId: 7, commanderGeneralId: 7, commanderName: '하후돈', nationId: 1, nationColor: '#4f7fbf', provinceId: 'P-1', commanderyNo: 12, visibility: 'FULL', own: true, troops: 1200 }],
};

beforeEach(() => {
    vi.clearAllMocks();
    nav.search = '';
    vi.mocked(useGameSession).mockReturnValue({
        loading: false, error: null, serverId: undefined, gameDate: '', refresh: vi.fn(), generalId: 7,
        frontInfo: { global: { year: 200, month: 3, turnPhase: 1 }, general: { hasGeneral: true, generalId: 7, nationId: 1 } },
    } as unknown as GameSession);
    vi.mocked(api.campaignCorps).mockResolvedValue(corps as never);
    vi.mocked(api.campaignVisibility).mockResolvedValue({ status: 'READY', commanderies: [{ no: 12, id: 'c12', name: '영천군', tier: 'FULL' }] } as never);
    vi.mocked(api.deployOptions).mockResolvedValue({ available: true, maxReservedTurns: 12, bugoks: [], destinations: [], order: null } as never);
    vi.mocked(api.campaignPolicies).mockResolvedValue({
        status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: { code: 'DEFEND', label: '수비' }, counties: [],
        corps: [{ orderId: 'O-1', commanderName: '하후돈', active: { policy: 'INTERCEPT', label: '요격' }, pending: null, settable: true, blocked: null }],
    } as never);
    vi.mocked(api.legacyCourtOptions).mockResolvedValue({ inputId: 'court.releaseCorps', available: true, choices: [{ label: '하후돈 군단', arguments: { targetGeneralId: 7 }, available: true }] } as never);
});

test('군단 칸 · 지도 자리 — 출병 · 부대 모으기는 작전실 흐름(?do=), 방침 바꾸기는 영지', async () => {
    render(<CorpsPage />);
    expect(screen.getByRole('heading', { name: '군단 · 세력 작전' })).toBeInTheDocument();
    expect(screen.getByText('군단 지도 준비 중')).toBeInTheDocument();
    fireEvent.click(within(await screen.findByRole('region', { name: '내 군단' })).getByRole('button'));
    expect(screen.getByRole('article', { name: '군단 — 하후돈' })).toHaveTextContent('방침요격');
    fireEvent.click(screen.getByRole('button', { name: '출병' }));
    expect(push).toHaveBeenLastCalledWith('/game?do=action.deploy');
    fireEvent.click(screen.getByRole('button', { name: '부대 모으기' }));
    expect(push).toHaveBeenLastCalledWith('/game?do=action.muster');
    fireEvent.click(screen.getByRole('button', { name: '방침 바꾸기' }));
    expect(push).toHaveBeenLastCalledWith('/game/territory');
});

test('편성 해제는 나(행위자 7)로 서버가 준 인자를 보내고, 받으면 다시 읽는다', async () => {
    vi.mocked(api.courtLegacy).mockResolvedValue({ status: 'AVAILABLE', requestId: 'r-1' } as never);
    render(<CorpsPage />);
    fireEvent.click(within(await screen.findByRole('region', { name: '내 군단' })).getByRole('button'));
    const card = screen.getByRole('article', { name: '군단 — 하후돈' });
    await waitFor(() => expect(within(card).getByRole('button', { name: '편성 해제' })).toHaveAttribute('data-input-status', 'AVAILABLE'));
    fireEvent.click(within(card).getByRole('button', { name: '편성 해제' }));
    await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: '편성 해제' }).at(-1)!); });
    expect(api.courtLegacy).toHaveBeenCalledWith('court.releaseCorps', 7, { targetGeneralId: 7 });
    await waitFor(() => expect(api.campaignCorps).toHaveBeenCalledTimes(2));
});

test('편성 해제 선택지를 못 읽으면 단추를 「가능」으로 두지 않고 사유로 막는다 · 군단 읽기 실패는 다시 시도', async () => {
    vi.mocked(api.legacyCourtOptions).mockRejectedValue(new Error('500: Internal Server Error'));
    vi.mocked(api.campaignCorps).mockRejectedValueOnce(new Error('500'));
    render(<CorpsPage />);
    fireEvent.click(await screen.findByRole('button', { name: /다시 시도/ }));
    fireEvent.click(within(await screen.findByRole('region', { name: '내 군단' })).getByRole('button'));
    const release = within(screen.getByRole('article', { name: '군단 — 하후돈' })).getByRole('button', { name: '편성 해제' });
    await waitFor(() => expect(release).toHaveAttribute('data-input-status', 'BLOCKED'));
    expect(screen.queryByText(/Internal Server Error/)).toBeNull();
});

test('주소 ?tab=operations 는 「세력 작전」 탭을 바로 연다(서버 대기)', async () => {
    nav.search = 'tab=operations';
    render(<CorpsPage />);
    expect(await screen.findByText('세력 작전 준비 중')).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: '세력 작전' })).toHaveAttribute('aria-selected', 'true');
});
