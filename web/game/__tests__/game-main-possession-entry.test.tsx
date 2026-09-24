import { fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import GameMainPage from '@/app/game/page';
import { useHwihaSession, type HwihaSession } from '@/lib/hwiha-session';

const mocks = vi.hoisted(() => ({
  useSearchParams: vi.fn(),
  replace: vi.fn(),
  refresh: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useSearchParams: mocks.useSearchParams,
  useRouter: () => ({ replace: mocks.replace }),
}));

vi.mock('@/lib/hwiha-session', () => ({
  HwihaSessionProvider: ({ children }: { children: ReactNode }) => <>{children}</>,
  useHwihaSession: vi.fn(),
}));

vi.mock('@/app/game/hwiha/war-room/page', () => ({
  default: () => <div data-testid="hwiha-war-room" />,
}));

vi.mock('@/components/game/CharacterClaim', () => ({
  default: ({ onClaimed }: { onClaimed: () => void }) => <button onClick={onClaimed}>장수 선택 완료</button>,
}));

function setSession(generalId: number | null, global: { npcMode?: number; blockGeneralCreate?: number } = {}) {
  vi.mocked(useHwihaSession).mockReturnValue({
    loading: false,
    error: null,
    frontInfo: { global: { serverId: 'pep', ...global } },
    generalId,
    serverId: 'pep',
    isHwihaWorld: true,
    gameDate: '',
    refresh: mocks.refresh,
  } as unknown as HwihaSession);
}

describe('main game route after HWIHA cutover', () => {
  beforeEach(() => {
    mocks.useSearchParams.mockReset().mockReturnValue(new URLSearchParams('entry=possession'));
    mocks.replace.mockReset();
    mocks.refresh.mockReset();
  });

  it('keeps the possession entry and goes to the HWIHA room after a claim', () => {
    setSession(null);
    render(<GameMainPage />);
    fireEvent.click(screen.getByRole('button', { name: '장수 선택 완료' }));
    expect(mocks.refresh).toHaveBeenCalledTimes(1);
    expect(mocks.replace).toHaveBeenCalledWith('/game/pep/hwiha/war-room');
  });

  it('renders the HWIHA room for a general even with the possession query', () => {
    setSession(42);
    render(<GameMainPage />);
    expect(screen.getByTestId('hwiha-war-room')).toBeInTheDocument();
  });

  it('sends a player without a general to registration by default', () => {
    mocks.useSearchParams.mockReturnValue(new URLSearchParams());
    setSession(null);
    render(<GameMainPage />);
    expect(mocks.replace).toHaveBeenCalledWith('/game/pep/join');
  });

  it.each([1, 2])('opens the selection entry when npcMode %s blocks direct creation', (npcMode) => {
    mocks.useSearchParams.mockReturnValue(new URLSearchParams());
    setSession(null, { npcMode, blockGeneralCreate: 1 });
    render(<GameMainPage />);
    expect(mocks.replace).toHaveBeenCalledWith('/game/pep?entry=possession');
  });

  it('keeps registration available when the creation block bit is clear', () => {
    mocks.useSearchParams.mockReturnValue(new URLSearchParams());
    setSession(null, { npcMode: 1, blockGeneralCreate: 2 });
    render(<GameMainPage />);
    expect(mocks.replace).toHaveBeenCalledWith('/game/pep/join');
  });
});
