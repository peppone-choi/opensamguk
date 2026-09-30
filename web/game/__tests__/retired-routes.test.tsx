import { existsSync } from 'node:fs';
import { join } from 'node:path';
import type { NextRequest } from 'next/server';
import { describe, expect, it, vi } from 'vitest';
import { middleware } from '../middleware';

const nextServer = vi.hoisted(() => ({
  next: vi.fn(() => ({ cookies: { set: vi.fn() } })),
  rewrite: vi.fn(() => ({ cookies: { set: vi.fn() } })),
}));

vi.mock('next/server', () => {
  class NextResponse {
    status: number;
    cookies = { set: vi.fn() };
    static next = nextServer.next;
    static rewrite = nextServer.rewrite;
    constructor(_body: unknown, init?: { status?: number }) {
      this.status = init?.status ?? 200;
    }
  }
  return { NextResponse };
});

const retired = [
  'admin1', 'admin2', 'admin5', 'admin7', 'admin8',
  'auction', 'battle-plan', 'betting', 'chief-center', 'coming-soon',
  'diplomacy', 'inherit', 'my-boss', 'nation', 'nation-betting',
  'nation-finance', 'npc-control', 'select-pool', 'simulator', 'tournament',
  'tournament-admin', 'troop', 'v2-lab', 'vote',
];
const retiredRankings = ['emperor', 'hall-of-fame', 'npcs', 'traffic'];

function request(path: string): NextRequest {
  const url = new URL(path, 'https://game.example.test');
  return { nextUrl: { pathname: url.pathname, searchParams: url.searchParams } } as NextRequest;
}

describe('retired SAMMO product routes', () => {
  it.each(retired)('/game/%s has no page and returns HTTP 404', (slug) => {
    const root = join(__dirname, '..', 'app', 'game', slug);
    expect(existsSync(join(root, 'page.tsx'))).toBe(false);
    expect((middleware(request(`/game/${slug}`)) as { status: number }).status).toBe(404);
  });

  it.each(retired)('/game/pep/%s is also blocked before the server rewrite', (slug) => {
    const previous = process.env.SERVER_ID;
    process.env.SERVER_ID = 'pep';
    try {
      expect((middleware(request(`/game/pep/${slug}`)) as { status: number }).status).toBe(404);
      expect(nextServer.rewrite).not.toHaveBeenCalled();
    } finally {
      if (previous === undefined) delete process.env.SERVER_ID;
      else process.env.SERVER_ID = previous;
    }
  });

  it.each(retiredRankings)('/game/rankings/%s has no page and returns HTTP 404', (slug) => {
    expect(existsSync(join(__dirname, '..', 'app', 'game', 'rankings', slug, 'page.tsx'))).toBe(false);
    expect((middleware(request(`/game/rankings/${slug}`)) as { status: number }).status).toBe(404);
  });

  it('keeps the remaining rankings available to normal routing', () => {
    for (const slug of ['best-generals', 'generals', 'kingdoms']) {
      expect((middleware(request(`/game/rankings/${slug}`)) as { status?: number }).status).toBeUndefined();
    }
  });

  it('keeps a similar but unrelated path available to normal routing', () => {
    expect((middleware(request('/game/v2-lab-x')) as { status?: number }).status).toBeUndefined();
  });
});
