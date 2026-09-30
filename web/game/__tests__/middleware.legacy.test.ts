import type { NextRequest } from 'next/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// 308 틀이 미들웨어에 걸려 있는지 — 켠 줄(ready)이 있을 때 서버 경로 · 쿼리를 지키며 308 을 내고,
// 켠 줄이 없으면(지금 main) 아무 것도 바꾸지 않는다.
const mocks = vi.hoisted(() => ({ next: vi.fn(), rewrite: vi.fn(), redirect: vi.fn(), ready: false }));

vi.mock('next/server', () => ({
  NextResponse: Object.assign(function NextResponse() { return { status: 404 }; }, {
    next: mocks.next, rewrite: mocks.rewrite, redirect: mocks.redirect,
  }),
}));

vi.mock('../lib/legacyRoutes', async (importActual) => {
  const actual = await importActual<typeof import('../lib/legacyRoutes')>();
  return {
    ...actual,
    legacyTarget: (rest: readonly string[], query: URLSearchParams) =>
      actual.legacyTarget(rest, query, actual.LEGACY_ROUTES.map((route) => ({ ...route, ready: mocks.ready }))),
  };
});

import { middleware } from '../middleware';

function makeRequest(path: string): NextRequest {
  const url = new URL(path, 'https://game.example.test');
  return {
    nextUrl: { pathname: url.pathname, searchParams: url.searchParams, clone: () => new URL(url.toString()) },
  } as unknown as NextRequest;
}

const response = () => ({ cookies: { set: vi.fn() } });
const originalServerId = process.env.SERVER_ID;

describe('game middleware legacy 308', () => {
  beforeEach(() => {
    process.env.SERVER_ID = 'pep';
    mocks.next.mockReturnValue(response());
    mocks.rewrite.mockReturnValue(response());
    mocks.redirect.mockReturnValue(response());
  });
  afterEach(() => {
    mocks.next.mockReset(); mocks.rewrite.mockReset(); mocks.redirect.mockReset();
    mocks.ready = false;
    process.env.SERVER_ID = originalServerId;
  });

  it('changes nothing while every line is off', () => {
    middleware(makeRequest('/game/pep/war-room'));
    expect(mocks.redirect).not.toHaveBeenCalled();
    expect(mocks.rewrite).toHaveBeenCalledTimes(1);
  });

  it('keeps the server path and the query when a line is on', () => {
    mocks.ready = true;
    middleware(makeRequest('/game/pep/battle-replay/12?t=40'));
    expect(mocks.redirect).toHaveBeenCalledTimes(1);
    const [url, status] = mocks.redirect.mock.calls[0] as [URL, number];
    expect(status).toBe(308);
    expect(url.pathname).toBe('/game/pep/records/replay/12');
    expect(url.searchParams.get('t')).toBe('40');
  });

  it('works for the query-selected server and moves a query id into the path', () => {
    mocks.ready = true;
    middleware(makeRequest('/game/city?id=3&server=pep'));
    const [url, status] = mocks.redirect.mock.calls[0] as [URL, number];
    expect(status).toBe(308);
    expect(url.pathname).toBe('/game/territory/county/3');
    expect(url.searchParams.get('id')).toBeNull();
    expect(url.searchParams.get('server')).toBe('pep');
  });

  it('sends the old war room to the server root', () => {
    mocks.ready = true;
    middleware(makeRequest('/game/pep/war-room'));
    expect((mocks.redirect.mock.calls[0] as [URL, number])[0].pathname).toBe('/game/pep');
  });

  it('leaves retired 404 paths and unknown paths alone', () => {
    mocks.ready = true;
    middleware(makeRequest('/game/pep/auction'));
    middleware(makeRequest('/game/pep/retinue'));
    expect(mocks.redirect).not.toHaveBeenCalled();
  });
});
