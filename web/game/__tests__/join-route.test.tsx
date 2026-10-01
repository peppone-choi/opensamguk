import * as matchers from '@testing-library/jest-dom/matchers';
import { render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import EnlistPage from '@/app/game/join/page';
import { useGameSession, type GameSession } from '@/lib/campaign-session';
expect.extend(matchers);
const mocks = vi.hoisted(() => ({ replace: vi.fn(), read: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: mocks.replace }) }));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: vi.fn() }));
vi.mock('@/hooks/useHelp', () => ({ useOpenHelp: () => vi.fn() }));
vi.mock('@/components/enlist/EnlistScreen', () => ({ default: ({ generalId }: { generalId: number }) => {
  mocks.read(generalId); return <div data-testid="enlist-screen">출사 후보</div>;
} }));
function session(generalId: number | null, nationId = 0, serverId: string | undefined = 'pep') {
  vi.mocked(useGameSession).mockReturnValue({ loading: false, error: null,
    frontInfo: { general: { nationId } }, generalId, serverId, refresh: vi.fn(), gameDate: '' } as unknown as GameSession);
}
beforeEach(() => vi.clearAllMocks());
describe('E04 real join route', () => {
  it('returns a player without a general to the entry without reading enlist options', async () => {
    session(null); render(<EnlistPage />);
    await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith('/game/pep'));
    expect(mocks.read).not.toHaveBeenCalled();
    expect(screen.queryByTestId('enlist-screen')).toBeNull();
  });
  it('returns an affiliated player to the war room without reading enlist options', async () => {
    session(7, 1); render(<EnlistPage />);
    await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith('/game/pep'));
    expect(mocks.read).not.toHaveBeenCalled();
  });
  it('opens the actual enlist screen only for a free general', () => {
    session(7); render(<EnlistPage />);
    expect(screen.getByTestId('enlist-screen')).toBeVisible();
    expect(mocks.read).toHaveBeenCalledWith(7);
    expect(mocks.replace).not.toHaveBeenCalled();
  });
  it('keeps a serverless route serverless', async () => {
    session(null);
    vi.mocked(useGameSession).mockReturnValue({ ...useGameSession(), serverId: undefined });
    render(<EnlistPage />);
    await waitFor(() => expect(mocks.replace).toHaveBeenCalledWith('/game'));
  });
  it('does not expose a raw server error or redirect on an uncertain session', () => {
    session(7);
    vi.mocked(useGameSession).mockReturnValue({ ...useGameSession(), error: '500 Internal Server Error' });
    render(<EnlistPage />);
    expect(screen.getByText('장수 정보를 불러오지 못했습니다.')).toBeVisible();
    expect(screen.queryByText('500 Internal Server Error')).toBeNull();
    expect(mocks.replace).not.toHaveBeenCalled();
    expect(mocks.read).not.toHaveBeenCalled();
  });
});
