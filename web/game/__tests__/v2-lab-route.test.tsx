import { render, screen } from '@testing-library/react';
import type { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import V2LabLayout from '@/app/game/v2-lab/layout';
import V2LabPage from '@/app/game/v2-lab/page';

// `notFound()` throws a `NEXT_HTTP_ERROR_FALLBACK` digest. The digest string is a
// Next.js internal contract, so mock it and assert only whether it was called.
const notFound = vi.hoisted(() => vi.fn(() => {
  throw new Error('NEXT_NOT_FOUND');
}));

vi.mock('next/navigation', () => ({ notFound }));

// The middleware uses `new NextResponse(null, { status: 404 })`, so the mock must
// preserve the constructor shape.
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

const original = process.env.V2_ENABLED;

describe('/game/v2-lab 네임스페이스 게이트', () => {
  beforeEach(() => {
    notFound.mockClear();
    delete process.env.V2_ENABLED;
  });

  afterEach(() => {
    if (original === undefined) delete process.env.V2_ENABLED;
    else process.env.V2_ENABLED = original;
  });

  it('V2_ENABLED 미설정이면 404 (리다이렉트·빈 페이지 아님)', () => {
    expect(() => V2LabLayout({ children: <div>v2</div> })).toThrow();
    expect(notFound).toHaveBeenCalledTimes(1);
  });

  // The frontend intentionally uses strict `=== 'true'`, unlike the backend
  // `havingValue="true"` comparison (`equalsIgnoreCase`). The backend also has an
  // `@Profile("v2-sandbox")` condition that the frontend lacks, so accepting `TRUE`
  // here could expose the route in production. See the layout comment for evidence.
  it('V2_ENABLED가 true가 아닌 값이면 404 — 대소문자 변형 포함', () => {
    for (const value of ['false', '1', 'TRUE', 'True', 'tRuE', '']) {
      notFound.mockClear();
      process.env.V2_ENABLED = value;
      expect(() => V2LabLayout({ children: <div>v2</div> })).toThrow();
      expect(notFound, `V2_ENABLED=${value}`).toHaveBeenCalledTimes(1);
    }
  });

  it('V2_ENABLED=true면 자식을 렌더하고 404를 내지 않는다', () => {
    process.env.V2_ENABLED = 'true';
    render(<>{V2LabLayout({ children: <V2LabPage /> })}</>);
    expect(notFound).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: 'v2-lab' })).toBeInTheDocument();
  });
});

describe('middleware v2-lab 게이트 (진짜 404)', () => {
  const originalServerId = process.env.SERVER_ID;

  function makeRequest(path: string): NextRequest {
    const url = new URL(path, 'https://game.example.test');
    return {
      nextUrl: {
        pathname: url.pathname,
        searchParams: url.searchParams,
        clone: () => new URL(url.toString()),
      },
    } as unknown as NextRequest;
  }

  beforeEach(() => {
    nextServer.next.mockClear();
    nextServer.rewrite.mockClear();
    process.env.SERVER_ID = 'pep';
    delete process.env.V2_ENABLED;
  });

  afterEach(() => {
    if (originalServerId === undefined) delete process.env.SERVER_ID;
    else process.env.SERVER_ID = originalServerId;
    if (original === undefined) delete process.env.V2_ENABLED;
    else process.env.V2_ENABLED = original;
  });

  // Cover direct paths and paths that first pass through the serverId rewrite.
  // Checking the raw pathname would miss the latter gate bypass.
  const gated = [
    '/game/v2-lab',
    '/game/v2-lab/anything',
    '/game/pep/v2-lab',
    '/game/pep/v2-lab/anything',
  ];

  it.each(gated)('V2_ENABLED 미설정이면 %s → 404 (rewrite/next 아님)', async (path) => {
    const { middleware } = await import('../middleware');
    const res = middleware(makeRequest(path)) as unknown as { status?: number };
    expect(res.status).toBe(404);
    expect(nextServer.rewrite).not.toHaveBeenCalled();
    expect(nextServer.next).not.toHaveBeenCalled();
  });

  it.each(gated)('V2_ENABLED=true면 %s는 게이트를 통과한다', async (path) => {
    process.env.V2_ENABLED = 'true';
    const { middleware } = await import('../middleware');
    const res = middleware(makeRequest(path)) as unknown as { status?: number };
    expect(res.status).toBeUndefined();
    expect(nextServer.next.mock.calls.length + nextServer.rewrite.mock.calls.length).toBe(1);
  });

  // Middleware is the layer that guarantees an HTTP 404; if case variants escaped
  // here, the layout-only gate could return HTTP 200.
  it.each(['TRUE', 'True', 'tRuE'])('V2_ENABLED=%s는 strict 비교라 여전히 404', async (value) => {
    process.env.V2_ENABLED = value;
    const { middleware } = await import('../middleware');
    const res = middleware(makeRequest('/game/v2-lab')) as unknown as { status?: number };
    expect(res.status).toBe(404);
    expect(nextServer.rewrite).not.toHaveBeenCalled();
    expect(nextServer.next).not.toHaveBeenCalled();
  });

  it('v2-lab이 아닌 경로는 영향받지 않는다', async () => {
    const { middleware } = await import('../middleware');
    middleware(makeRequest('/game/rankings'));
    expect(nextServer.next).toHaveBeenCalled();
  });

  // A prefix match such as `v2-lab-x` must not make unrelated routes unavailable.
  it('접두사만 같은 경로는 게이트 대상이 아니다', async () => {
    const { middleware } = await import('../middleware');
    middleware(makeRequest('/game/v2-lab-x'));
    expect(nextServer.next).toHaveBeenCalled();
  });
});

/**
 * Preserves the assumption under which 0A-a isolation holds.
 *
 * The middleware matcher only includes `['/game', '/game/:path*']`, so `/_next/**`
 * static assets are structurally outside the gate. Isolation still holds because
 * the ungated paths contain no protected content: v2-lab is server-only, so the
 * production chunk is a 556 B empty stub (measured in s3b §7.4–§7.6).
 *
 * Adding `'use client'` would place real v2 code there, and the v1 image would serve
 * that bundle with HTTP 200 outside the gate. Code believed to be behind a 404 would
 * leak through a static path.
 *
 * Honest limitation: this source scan catches only a directly attached `'use client'`.
 * It passes if v2-lab imports an external client component. Covering that case requires
 * a production build and chunk-size baseline in CI.
 */
describe('v2-lab 정적 자산 격리 전제', () => {
  it("v2-lab 라우트에 'use client'가 없다 — 있으면 s3b §7.6 판정을 다시 해야 한다", async () => {
    const { readdirSync, readFileSync } = await import('node:fs');
    const { join } = await import('node:path');

    const root = join(__dirname, '..', 'app', 'game', 'v2-lab');
    const walk = (dir: string): string[] =>
      readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
        e.isDirectory() ? walk(join(dir, e.name)) : [join(dir, e.name)],
      );

    const files = walk(root);
    // Ensure the scan cannot pass vacuously over an empty directory.
    expect(files.length).toBeGreaterThan(0);

    const clientFiles = files.filter((f) => /^\s*['"]use client['"]/m.test(readFileSync(f, 'utf8')));
    expect(clientFiles).toEqual([]);
  });
});
