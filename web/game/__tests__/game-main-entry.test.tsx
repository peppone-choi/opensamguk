import * as matchers from '@testing-library/jest-dom/matchers';
import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import GameMainPage from '@/app/game/page';
import { useGameSession, type GameSession } from '@/lib/campaign-session';

expect.extend(matchers);

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

vi.mock('@/components/campaign/WarRoomPage', () => ({
  default: () => <div data-testid="campaign-war-room" />,
}));

function setSession(generalId: number | null, global: { npcMode?: number; blockGeneralCreate?: number } = {}) {
  vi.mocked(useGameSession).mockReturnValue({
    loading: false,
    error: null,
    frontInfo: { global: { serverId: 'pep', ...global } },
    generalId,
    serverId: 'pep',
   
    gameDate: '',
    refresh: mocks.refresh,
  } as unknown as GameSession);
}

// The approved E01 replaces the former redirect to the generation page.
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

  it('keeps a player without a general at the entry with two real waiting destinations', () => {
    setSession(null);
    render(<GameMainPage />);
    expect(screen.getByTestId('game-entry-screen')).toBeVisible();
    expect(screen.getByRole('link', { name: '생성 화면 보기' })).toHaveAttribute('href', '/game/pep/create');
    expect(screen.getByRole('link', { name: '역사 인물 화면 보기' })).toHaveAttribute('href', '/game/pep/create/historical');
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
});
