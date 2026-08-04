import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import GameMainPage from '@/app/game/page';

const mocks = vi.hoisted(() => ({
  useSearchParams: vi.fn(),
}));

vi.mock('next/navigation', () => ({
  useSearchParams: mocks.useSearchParams,
}));

vi.mock('@/components/Shell', () => ({
  default: ({ children }: { children: ReactNode }) => <>{children}</>,
}));

vi.mock('@/components/game/GameChrome', () => ({
  default: ({ entryMode }: { entryMode?: string }) => <div data-testid="game-entry-mode">{entryMode ?? 'default'}</div>,
}));

vi.mock('@/components/game/MainRecordZone', () => ({
  default: () => null,
}));

describe('GameMainPage possession entry', () => {
  beforeEach(() => {
    mocks.useSearchParams.mockReset().mockReturnValue(new URLSearchParams('entry=possession'));
  });

  it('passes the explicit possession query mode to the game chrome', () => {
    render(<GameMainPage />);

    expect(screen.getByTestId('game-entry-mode')).toHaveTextContent('possession');
  });
});
