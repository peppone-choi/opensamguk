import { render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/AuthGate', () => ({ default: ({ children }: { children: React.ReactNode }) => children }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: vi.fn(), push: vi.fn() }) }));
vi.mock('@/lib/serverRegistry', () => ({ getServers: () => [], isValidEmptyServerRegistry: () => true }));

vi.mock('@/lib/serverPublication', async () => {
    // 공개 목록(C8)은 이 시험의 레지스트리 흉내에서 만든다 — 비었고 「유효한 빈 표」가 아니면 원천 불명(UNKNOWN)
    const registry = await import('@/lib/serverRegistry');
    return {
        readPublicServers: async () => {
            const servers = registry.getServers();
            const validEmpty = 'isValidEmptyServerRegistry' in registry ? registry.isValidEmptyServerRegistry() : true;
            if (servers.length === 0 && !validEmpty) return { kind: 'unknown' };
            return { kind: 'known', servers: servers.map((s) => ({ id: s.id, name: s.name, generation: s.generation ?? null, gameUrl: s.gameUrl ?? `/game/${s.id}` })) };
        },
    };
});
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

    render(await LobbyPage());

    expect(await screen.findByRole('heading', { level: 1, name: '게임 로비' })).toBeInTheDocument();
    expect(screen.getByText('현재 이용할 수 있는 게임 서버가 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: '게이트웨이 메뉴' })).toBeInTheDocument();
  });
});
