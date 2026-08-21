import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NextRequest } from 'next/server';

let cookieValue: string | undefined;
let cookieReadCount = 0;

vi.mock('next/headers', () => ({
  cookies: async () => {
    cookieReadCount += 1;
    return {
      get: (name: string) => (name === 'sam_access' && cookieValue ? { value: cookieValue } : undefined),
    };
  },
}));

vi.mock('@/lib/server-api', () => ({
  BOARD_API_URL: 'http://board-api.test',
  GATEWAY_UPSTREAM_TIMEOUT_MS: 10_000,
  isGatewayTimeout: (error: unknown) => error instanceof Error && error.name === 'TimeoutError',
}));

import { DELETE, GET, PATCH, POST } from '@/app/api/board/[...path]/route';

function request(path: string, init?: ConstructorParameters<typeof NextRequest>[1]): NextRequest {
  return new NextRequest(`http://gateway.example.test${path}`, init);
}

function context(path: string[]) {
  return { params: Promise.resolve({ path }) };
}

describe('gateway board proxy', () => {
  beforeEach(() => {
    cookieValue = undefined;
    cookieReadCount = 0;
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('forwards an anonymous public list read without adding Authorization', async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response('{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0}', {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const response = await GET(
      request('/api/board/posts?category=FREE&page=0&size=20'),
      context(['posts']),
    );

    expect(response.status).toBe(200);
    expect(cookieReadCount).toBe(1);
    expect(response.headers.get('Vary')).toContain('Authorization');
    expect(fetch).toHaveBeenCalledWith('http://board-api.test/board/posts?category=FREE&page=0&size=20', expect.objectContaining({
      method: 'GET',
      headers: {},
      cache: 'no-store',
      signal: expect.any(AbortSignal),
    }));
  });

  it('forwards an optional public-read Bearer so the gateway can calculate canDelete', async () => {
    cookieValue = 'possibly-stale-access-token';
    vi.mocked(fetch).mockResolvedValue(
      new Response('{"post":{"id":7},"comments":[]}', {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const response = await GET(request('/api/board/posts/7'), context(['posts', '7']));

    expect(response.status).toBe(200);
    expect(cookieReadCount).toBe(1);
    expect(response.headers.get('Vary')).toContain('Authorization');
    expect(fetch).toHaveBeenCalledWith('http://board-api.test/board/posts/7', expect.objectContaining({
      method: 'GET',
      headers: { Authorization: 'Bearer possibly-stale-access-token' },
      cache: 'no-store',
      signal: expect.any(AbortSignal),
    }));
  });

  it('rejects an unauthenticated post write before contacting the gateway API', async () => {
    const response = await POST(
      request('/api/board/posts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ category: 'FREE', title: '새 글', content: '본문' }),
      }),
      context(['posts']),
    );

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toEqual({ message: '로그인이 필요합니다.', status: 401 });
    expect(fetch).not.toHaveBeenCalled();
  });

  it('bridges the httpOnly access cookie only for an authenticated board write', async () => {
    cookieValue = 'access-token';
    vi.mocked(fetch).mockResolvedValue(
      new Response('{"id":19}', { status: 201, headers: { 'Content-Type': 'application/json' } }),
    );

    const body = JSON.stringify({ category: 'FREE', title: '새 글', content: '본문' });
    const response = await POST(
      request('/api/board/posts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body,
      }),
      context(['posts']),
    );

    expect(response.status).toBe(201);
    expect(fetch).toHaveBeenCalledWith('http://board-api.test/board/posts', expect.objectContaining({
      method: 'POST',
      headers: {
        Authorization: 'Bearer access-token',
        'Content-Type': 'application/json',
      },
      body,
      cache: 'no-store',
      signal: expect.any(AbortSignal),
    }));
  });

  it('forwards delete and admin pin mutations through the authenticated bridge', async () => {
    cookieValue = 'admin-token';
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(
        new Response('{"id":19,"pinned":true}', {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      );

    const deleted = await DELETE(
      request('/api/board/posts/19/comments/8', { method: 'DELETE' }),
      context(['posts', '19', 'comments', '8']),
    );
    const pinned = await PATCH(
      request('/api/board/posts/19/pin', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ pinned: true }),
      }),
      context(['posts', '19', 'pin']),
    );

    expect(deleted.status).toBe(204);
    expect(pinned.status).toBe(200);
    expect(fetch).toHaveBeenNthCalledWith(1, 'http://board-api.test/board/posts/19/comments/8', expect.objectContaining({
      method: 'DELETE',
      headers: { Authorization: 'Bearer admin-token' },
      cache: 'no-store',
      signal: expect.any(AbortSignal),
    }));
    expect(fetch).toHaveBeenNthCalledWith(2, 'http://board-api.test/board/posts/19/pin', expect.objectContaining({
      method: 'PATCH',
      headers: {
        Authorization: 'Bearer admin-token',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ pinned: true }),
      cache: 'no-store',
      signal: expect.any(AbortSignal),
    }));
  });

  it('forwards an authenticated post update for a valid post id', async () => {
    cookieValue = 'access-token';
    vi.mocked(fetch).mockResolvedValue(
      new Response('{"id":19,"title":"수정된 글"}', {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    );

    const body = JSON.stringify({ title: '수정된 글', content: '수정된 본문' });
    const response = await PATCH(
      request('/api/board/posts/19', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body,
      }),
      context(['posts', '19']),
    );

    expect(response.status).toBe(200);
    expect(fetch).toHaveBeenCalledWith('http://board-api.test/board/posts/19', expect.objectContaining({
      method: 'PATCH',
      headers: {
        Authorization: 'Bearer access-token',
        'Content-Type': 'application/json',
      },
      body,
      cache: 'no-store',
      signal: expect.any(AbortSignal),
    }));
  });

  it('rejects invalid public and mutation paths without contacting the gateway', async () => {
    const invalidDetail = await GET(request('/api/board/posts/abc'), context(['posts', 'abc']));
    const incompleteCommentDelete = await DELETE(
      request('/api/board/posts/19/comments', { method: 'DELETE' }),
      context(['posts', '19', 'comments']),
    );
    const invalidPin = await PATCH(
      request('/api/board/posts/19/unpin', { method: 'PATCH' }),
      context(['posts', '19', 'unpin']),
    );
    const invalidPostUpdate = await PATCH(
      request('/api/board/posts/0', { method: 'PATCH' }),
      context(['posts', '0']),
    );

    expect(invalidDetail.status).toBe(404);
    expect(incompleteCommentDelete.status).toBe(404);
    expect(invalidPin.status).toBe(404);
    expect(invalidPostUpdate.status).toBe(404);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('maps an upstream connection failure to 502', async () => {
    vi.mocked(fetch).mockRejectedValue(new Error('connection refused'));

    const response = await GET(request('/api/board/posts'), context(['posts']));

    expect(response.status).toBe(502);
    await expect(response.json()).resolves.toEqual({ message: '게시판 서버에 연결할 수 없습니다.', status: 502 });
  });

  it('bounds an upstream timeout and maps it to 504', async () => {
    const timeout = new Error('timed out');
    timeout.name = 'TimeoutError';
    vi.mocked(fetch).mockRejectedValue(timeout);

    const response = await GET(request('/api/board/posts'), context(['posts']));

    expect(response.status).toBe(504);
    await expect(response.json()).resolves.toEqual({ message: '게시판 서버 응답 시간이 초과되었습니다.', status: 504 });
  });
});
