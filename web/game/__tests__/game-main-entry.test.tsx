import * as matchers from '@testing-library/jest-dom/matchers';
import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import GameMainPage from '@/app/game/page';
import { useGameSession, type GameSession } from '@/lib/campaign-session';
import { useCreationOptions, type OptionsState } from '@/hooks/useCreationOptions';
import { OPTIONS } from './fixtures/creation';

expect.extend(matchers);

const mocks = vi.hoisted(() => ({
  replace: vi.fn(),
  refresh: vi.fn(),
  reloadOptions: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(),
  useRouter: () => ({ replace: mocks.replace }),
}));

vi.mock('@/lib/campaign-session', () => ({
  GameSessionProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
  useGameSession: vi.fn(),
}));

vi.mock('@/hooks/useCreationOptions', () => ({ useCreationOptions: vi.fn() }));

vi.mock('@/components/campaign/WarRoomPage', () => ({
  default: () => <div data-testid="campaign-war-room" />,
}));

function setSession(generalId: number | null, global: { npcMode?: number; blockGeneralCreate?: number } = {}, serverId = 'pep') {
  const session = {
    loading: false,
    error: null,
    frontInfo: { global: { serverId, ...global } },
    generalId,
    serverId,
   
    gameDate: '',
    refresh: mocks.refresh,
  } as unknown as GameSession;
  vi.mocked(useGameSession).mockReturnValue(session);
  return session;
}

// Server selection leads to the war room or one creation-type choice.
describe('main game entry', () => {
  beforeEach(() => {
    mocks.replace.mockReset();
    mocks.refresh.mockReset();
    mocks.reloadOptions.mockReset();
    vi.mocked(useCreationOptions).mockClear();
    vi.mocked(useCreationOptions).mockReturnValue({ state: { kind: 'ready', data: { ...OPTIONS, playerCap: { used: 0, max: 50 } } }, reload: mocks.reloadOptions });
  });

  it('renders the war room for a player with a general', () => {
    setSession(42);
    render(<GameMainPage />);
    expect(screen.queryByTestId('campaign-war-room')).not.toBeNull();
    expect(mocks.replace).not.toHaveBeenCalled();
    expect(useCreationOptions).not.toHaveBeenCalled();
  });

  it('offers one start-type choice without repeating server selection or status', () => {
    setSession(null);
    render(<GameMainPage />);
    expect(screen.getByTestId('game-entry-screen')).toBeVisible();
    expect(screen.getByRole('region', { name: '시작 방식 선택' })).toBeVisible();
    expect(screen.getByRole('heading', { name: '시작 방식 선택' })).toBeVisible();
    expect(screen.queryByLabelText('서버 요약')).not.toBeInTheDocument();
    expect(screen.queryByText(/입구 지도|입구 세력 목록|이 서버에서 시작한다/)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '로비로' })).not.toBeInTheDocument();
    expect(screen.getByText(/사람 장수 자리 50\/50 남음/)).toBeVisible();
    expect(screen.queryByText('장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '생성 화면 보기' })).toHaveAttribute('href', '/game/pep/create');
    expect(screen.getByRole('link', { name: '역사 인물 화면 보기' })).toHaveAttribute('href', '/game/pep/create/historical');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('preserves the selected server in both creation destinations', () => {
    setSession(null, {}, 'uni');
    render(<GameMainPage />);
    expect(screen.getByRole('link', { name: '생성 화면 보기' })).toHaveAttribute('href', '/game/uni/create');
    expect(screen.getByRole('link', { name: '역사 인물 화면 보기' })).toHaveAttribute('href', '/game/uni/create/historical');
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('waits for the general read before offering creation', () => {
    const session = setSession(null);
    vi.mocked(useGameSession).mockReturnValue({ ...session, loading: true, frontInfo: null });
    render(<GameMainPage />);
    expect(screen.getByRole('status')).toHaveTextContent('장수 정보를 불러오는 중입니다.');
    expect(screen.queryByTestId('game-entry-screen')).not.toBeInTheDocument();
    expect(useCreationOptions).not.toHaveBeenCalled();
  });

  it.each(['401', '403', '503', null])('does not treat a failed or missing general read as no general (%s)', (status) => {
    const session = setSession(null);
    vi.mocked(useGameSession).mockReturnValue({
      ...session, frontInfo: null, error: status ? `${status}: read failed` : null,
    });
    render(<GameMainPage />);
    expect(screen.getByRole('alert')).toHaveTextContent('장수 정보를 불러오지 못했습니다.');
    expect(screen.queryByTestId('game-entry-screen')).not.toBeInTheDocument();
    expect(useCreationOptions).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(mocks.refresh).toHaveBeenCalledTimes(1);
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it.each([1, 2])('never opens a retired selection entry, even when npcMode %s and the creation block bit are set', (npcMode) => {
    setSession(null, { npcMode, blockGeneralCreate: 1 });
    render(<GameMainPage />);
    expect(screen.getByTestId('game-entry-screen')).toBeVisible();
    expect(screen.getByRole('link', { name: '생성 화면 보기' })).toHaveAttribute('href', '/game/pep/create');
    expect(screen.getByRole('link', { name: '역사 인물 화면 보기' })).toHaveAttribute('href', '/game/pep/create/historical');
    expect(mocks.replace).not.toHaveBeenCalled();
    expect(mocks.replace).not.toHaveBeenCalledWith(expect.stringContaining('entry=possession'));
  });

  function options(state: OptionsState) {
    vi.mocked(useCreationOptions).mockReturnValue({ state, reload: mocks.reloadOptions });
    setSession(null);
    render(<GameMainPage />);
  }

  it.each([
    { customAllowed: false, historicalAllowed: true, open: '역사 인물로 시작할 수 있습니다.' },
    { customAllowed: true, historicalAllowed: false, open: '내 장수를 만들 수 있습니다.' },
  ])('uses each path policy and preserves both destinations ($customAllowed/$historicalAllowed)', ({ customAllowed, historicalAllowed, open }) => {
    options({ kind: 'ready', data: { ...OPTIONS, policy: { customAllowed, historicalAllowed, reason: 'ROLE_UNAVAILABLE' } } });
    expect(screen.getByText('이 시작 역할은 현재 세계에서 선택할 수 없습니다.')).toBeVisible();
    expect(screen.getByText(open)).toBeVisible();
    expect(screen.getByRole('link', { name: '생성 화면 보기' })).toHaveAttribute('href', '/game/pep/create');
    expect(screen.getByRole('link', { name: '역사 인물 화면 보기' })).toHaveAttribute('href', '/game/pep/create/historical');
    expect(screen.getByText(/사람 장수 정원을 확인하지 못했습니다/)).toBeVisible();
    expect(screen.queryByText(/50\/50/)).not.toBeInTheDocument();
  });

  it('shows full capacity even when path flags still allow creation', () => {
    options({ kind: 'ready', data: { ...OPTIONS, playerCap: { used: 50, max: 50 } } });
    expect(screen.getByText(/사람 장수 자리 0\/50 남음/)).toBeVisible();
    expect(screen.getByText('사람 장수 자리가 다 찼습니다.')).toBeVisible();
    expect(screen.queryByText('내 장수를 만들 수 있습니다.')).not.toBeInTheDocument();
  });

  it('shows policy rejection instead of announcing either path as available', () => {
    options({ kind: 'ready', data: { ...OPTIONS, policy: { customAllowed: false, historicalAllowed: false, reason: 'CREATION_POLICY_UNAVAILABLE' } } });
    expect(screen.getAllByText('장수 만들기가 아직 열리지 않았습니다. 잠시 후 다시 확인해 주세요.').length).toBeGreaterThan(0);
    expect(screen.queryByText('내 장수를 만들 수 있습니다.')).not.toBeInTheDocument();
    expect(screen.queryByText('역사 인물로 시작할 수 있습니다.')).not.toBeInTheDocument();
  });

  it.each([null, 'CREATION_POLICY_UNAVAILABLE'])('keeps missing/closed options distinct from ready (%s)', (code) => {
    options({ kind: 'waiting', code, message: code ? '지금은 생성할 수 없습니다.' : null });
    expect(screen.getByText(code ? '지금은 생성할 수 없습니다.' : '생성 조건을 아직 확인할 수 없습니다.')).toBeVisible();
    expect(screen.queryByText('내 장수를 만들 수 있습니다.')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '생성 화면 보기' })).toHaveAttribute('href', '/game/pep/create');
  });

  it('preserves read failure and retries only the options read', () => {
    options({ kind: 'error', error: new Error('생성 조건 조회 실패') });
    expect(screen.getByRole('alert')).toHaveTextContent('생성 조건 조회 실패');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(mocks.reloadOptions).toHaveBeenCalledTimes(1);
    expect(mocks.replace).not.toHaveBeenCalled();
    expect(screen.queryByText(/50\/50/)).not.toBeInTheDocument();
  });

  it('does not claim admission while options are loading', () => {
    options({ kind: 'loading' });
    expect(screen.getByText('생성 조건을 확인하고 있습니다.')).toBeVisible();
    expect(screen.queryByText('내 장수를 만들 수 있습니다.')).not.toBeInTheDocument();
    expect(screen.queryByText('역사 인물로 시작할 수 있습니다.')).not.toBeInTheDocument();
  });

});
