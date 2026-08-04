import type { NextRequest } from 'next/server';
import { afterAll, afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const nextServerMocks = vi.hoisted(() => ({
  next: vi.fn(),
  rewrite: vi.fn(),
}));

vi.mock('next/server', () => ({
  NextResponse: {
    next: nextServerMocks.next,
    rewrite: nextServerMocks.rewrite,
  },
}));

import { middleware } from '../middleware';

interface MockResponse {
  cookies: {
    set: ReturnType<typeof vi.fn>;
  };
}

function makeResponse(): MockResponse {
  return { cookies: { set: vi.fn() } };
}

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

const originalServerId = process.env.SERVER_ID;
let nextResponse: MockResponse;
let rewriteResponse: MockResponse;

describe('game middleware server path selection', () => {
  beforeEach(() => {
    process.env.SERVER_ID = 'pep';
    nextResponse = makeResponse();
    rewriteResponse = makeResponse();
    nextServerMocks.next.mockReturnValue(nextResponse);
    nextServerMocks.rewrite.mockReturnValue(rewriteResponse);
  });

  afterEach(() => {
    nextServerMocks.next.mockReset();
    nextServerMocks.rewrite.mockReset();
  });

  afterAll(() => {
    if (originalServerId === undefined) delete process.env.SERVER_ID;
    else process.env.SERVER_ID = originalServerId;
  });

  it.each(['pep', 'a1', 's1', 'current'])('rewrites the configured public ID %s and sets its cookie', (serverId) => {
    process.env.SERVER_ID = serverId;

    middleware(makeRequest(`/game/${serverId}/join?tab=profile`));

    expect(nextServerMocks.rewrite).toHaveBeenCalledTimes(1);
    const target = nextServerMocks.rewrite.mock.calls[0][0] as URL;
    expect(target.pathname).toBe('/game/join');
    expect(target.searchParams.get('tab')).toBe('profile');
    expect(target.searchParams.get('server')).toBe(serverId);
    expect(rewriteResponse.cookies.set).toHaveBeenCalledWith('sam_server', serverId, {
      path: '/',
      sameSite: 'lax',
      maxAge: 7 * 24 * 60 * 60,
    });
  });

  it('preserves ordinary child routes and mismatched alphanumeric path segments', () => {
    middleware(makeRequest('/game/join'));
    expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
    expect(nextServerMocks.rewrite).not.toHaveBeenCalled();
    expect(nextResponse.cookies.set).not.toHaveBeenCalled();

    nextServerMocks.next.mockClear();
    middleware(makeRequest('/game/A1/join'));
    expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
    expect(nextServerMocks.rewrite).not.toHaveBeenCalled();
  });

  it('sets the query selection cookie only when it matches the configured canonical ID', () => {
    middleware(makeRequest('/game/join?server=pep'));
    expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
    expect(nextResponse.cookies.set).toHaveBeenCalledWith('sam_server', 'pep', {
      path: '/',
      sameSite: 'lax',
      maxAge: 7 * 24 * 60 * 60,
    });

    nextServerMocks.next.mockClear();
    nextResponse.cookies.set.mockClear();
    process.env.SERVER_ID = 'current';
    middleware(makeRequest('/game/join?server=current'));
    expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
    expect(nextResponse.cookies.set).toHaveBeenCalledWith('sam_server', 'current', {
      path: '/',
      sameSite: 'lax',
      maxAge: 7 * 24 * 60 * 60,
    });

    nextServerMocks.next.mockClear();
    nextResponse.cookies.set.mockClear();
    const maxLengthServerId = 'a'.repeat(48);
    process.env.SERVER_ID = maxLengthServerId;
    middleware(makeRequest(`/game/join?server=${maxLengthServerId}`));
    expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
    expect(nextResponse.cookies.set).toHaveBeenCalledWith('sam_server', maxLengthServerId, {
      path: '/',
      sameSite: 'lax',
      maxAge: 7 * 24 * 60 * 60,
    });

    nextServerMocks.next.mockClear();
    nextResponse.cookies.set.mockClear();
    process.env.SERVER_ID = 'pep';
    middleware(makeRequest('/game/join?server=a1'));
    expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
    expect(nextResponse.cookies.set).not.toHaveBeenCalled();

    nextServerMocks.next.mockClear();
    nextResponse.cookies.set.mockClear();
    middleware(makeRequest('/game/join?server=PEP'));
    expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
    expect(nextResponse.cookies.set).not.toHaveBeenCalled();
  });

  it('does not select a query server when the configured ID is absent, reserved, or invalid', () => {
    delete process.env.SERVER_ID;
    middleware(makeRequest('/game/join?server=pep'));
    expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
    expect(nextResponse.cookies.set).not.toHaveBeenCalled();

    for (const serverId of ['all', 'main', 'join', 'a'.repeat(49)]) {
      nextServerMocks.next.mockClear();
      nextServerMocks.rewrite.mockClear();
      nextResponse.cookies.set.mockClear();
      process.env.SERVER_ID = serverId;

      middleware(makeRequest(`/game/${serverId}/join?server=${serverId}`));

      expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
      expect(nextServerMocks.rewrite).not.toHaveBeenCalled();
      expect(nextResponse.cookies.set).not.toHaveBeenCalled();
    }
  });

  it('ignores uppercase paths and invalid configured IDs', () => {
    middleware(makeRequest('/game/A1/join'));
    expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
    expect(nextServerMocks.rewrite).not.toHaveBeenCalled();

    process.env.SERVER_ID = 'A1';
    nextServerMocks.next.mockClear();
    middleware(makeRequest('/game/A1/join'));
    expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
    expect(nextServerMocks.rewrite).not.toHaveBeenCalled();

    process.env.SERVER_ID = 'pep-id';
    nextServerMocks.next.mockClear();
    middleware(makeRequest('/game/join?server=pep-id'));
    expect(nextServerMocks.next).toHaveBeenCalledTimes(1);
    expect(nextResponse.cookies.set).not.toHaveBeenCalled();
  });
});
