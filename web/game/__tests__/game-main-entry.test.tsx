import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import GameMainPage from '@/app/game/page';
import { useGameSession, type GameSession } from '@/lib/campaign-session';

const mocks = vi.hoisted(() => ({
  replace: vi.fn(),
  refresh: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useSearchParams: () => new URLSearchParams(),
  useRouter: () => ({ replace: mocks.replace }),
}));

vi.mock('@/lib/campaign-session', () => ({
  GameSessionProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
  useGameSession: vi.fn(),
}));

vi.mock('@/app/game/(campaign)/war-room/page', () => ({
  default: () => <div data-testid="campaign-war-room" />,
}));

function setSession(generalId: number | null, global: { npcMode?: number; blockGeneralCreate?: number } = {}) {
  vi.mocked(useGameSession).mockReturnValue({
    loading: false,
    error: null,
    frontInfo: { global: { serverId: 'pep', ...global } },
    generalId,
    serverId: 'pep',
    isCampaignWorld: true,
    gameDate: '',
    refresh: mocks.refresh,
  } as unknown as GameSession);
}

// 삼모 빙의·장수 선택 풀 입구는 대체 없이 지웠다(ADR-LITE-049 2026-09-26). 장수가 없으면 가입 하나로 간다.
describe('main game entry', () => {
  beforeEach(() => {
    mocks.replace.mockReset();
    mocks.refresh.mockReset();
  });

  it('renders the war room for a player with a general', () => {
    setSession(42);
    render(<GameMainPage />);
    expect(screen.queryByTestId('campaign-war-room')).not.toBeNull();
    expect(mocks.replace).not.toHaveBeenCalled();
  });

  it('sends a player without a general to registration', () => {
    setSession(null);
    render(<GameMainPage />);
    expect(mocks.replace).toHaveBeenCalledWith('/game/pep/join');
  });

  it.each([1, 2])('never opens a retired selection entry, even when npcMode %s and the creation block bit are set', (npcMode) => {
    setSession(null, { npcMode, blockGeneralCreate: 1 });
    render(<GameMainPage />);
    expect(mocks.replace).toHaveBeenCalledWith('/game/pep/join');
    expect(mocks.replace).not.toHaveBeenCalledWith(expect.stringContaining('entry=possession'));
  });
});
