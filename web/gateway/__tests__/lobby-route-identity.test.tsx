import { render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/AuthGate', () => ({ default: ({ children }: { children: React.ReactNode }) => children }));
vi.mock('@/components/Topbar', () => ({ default: () => <div>topbar</div> }));
vi.mock('@/components/ServerBoard', () => ({ default: () => <div>server board</div> }));

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
    vi.stubGlobal('fetch', vi.fn(async () => response({ servers: [] })));

    render(<LobbyPage />);

    expect(await screen.findByRole('heading', { level: 1, name: '게임 로비' })).toBeInTheDocument();
    expect(await screen.findByRole('status')).toHaveTextContent('현재 이용할 수 있는 게임 서버가 없습니다.');
    expect(screen.getByRole('heading', { level: 2, name: '계 정 관 리' })).toBeInTheDocument();
  });
});
