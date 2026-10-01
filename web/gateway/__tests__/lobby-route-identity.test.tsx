import { render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/AuthGate', () => ({ default: ({ children }: { children: React.ReactNode }) => children }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: vi.fn(), push: vi.fn() }) }));
vi.mock('@/lib/serverRegistry', () => ({ getServers: () => [], isValidEmptyServerRegistry: () => true }));

import LobbyPage from '@/app/lobby/page';

function response(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
}

describe('lobby route identity', () => {
  beforeEach(() => {
    vi.stubGlobal('React', React);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('identifies itself as the game lobby when the server registry is empty', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ notices: [] })));

    render(<LobbyPage />);

    expect(await screen.findByRole('heading', { level: 1, name: '게임 로비' })).toBeInTheDocument();
    expect(screen.getByText('현재 이용할 수 있는 게임 서버가 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: '게이트웨이 메뉴' })).toBeInTheDocument();
  });
});
