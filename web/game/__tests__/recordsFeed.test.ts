import { describe, expect, it } from 'vitest';
import type { GameEvent } from '@opensamguk/ui';
import { appendPage, compareEventsDesc, mergeFeeds, newerThanLoaded } from '@/lib/recordsFeed';
import { feedPath, httpStatusOf } from '@/lib/records-reads';

const ev = (id: number, month: number, phase: number, ordinal = 0, kind = 'county.ownerChanged'): GameEvent =>
  ({ id, kind, section: 'WORLD', occurredAt: { year: 200, month, phase, ordinal }, refs: {}, facts: {} });

describe('compareEventsDesc — 서버와 같은 순서(연 · 월 · 순 · ordinal · id 내림차순)', () => {
  it('새 것이 앞', () => {
    const sorted = [ev(1, 3, 1), ev(2, 3, 2), ev(3, 2, 3), ev(4, 3, 2, 5), ev(5, 3, 2, 5)].sort(compareEventsDesc);
    expect(sorted.map((e) => e.id)).toEqual([5, 4, 2, 1, 3]);
  });
});

describe('appendPage', () => {
  it('다음 쪽을 붙이고 같은 id 는 한 번만, 커서는 새 쪽 것', () => {
    const feed = appendPage({ events: [ev(9, 3, 2), ev(8, 3, 1)], cursor: 'a' }, { events: [ev(8, 3, 1), ev(7, 2, 3)], nextCursor: null });
    expect(feed.events.map((e) => e.id)).toEqual([9, 8, 7]);
    expect(feed.cursor).toBeNull();
  });
});

describe('mergeFeeds — 「전체」', () => {
  it('끝난 피드끼리는 모두 시각순으로 합친다', () => {
    const out = mergeFeeds([{ events: [ev(10, 3, 2), ev(6, 2, 1)], cursor: null }, { events: [ev(9, 3, 1)], cursor: null }]);
    expect(out.events.map((e) => e.id)).toEqual([10, 9, 6]);
    expect(out.more).toBe(false);
    expect(out.held).toBe(0);
  });

  it('더 있는 피드의 가장 오래된 줄보다 오래된 줄은 잡아 둔다(다음 쪽이 그 사이에 낄 수 있다)', () => {
    // A 는 3월 상순까지 받았고 뒤가 더 있다. B 의 2월 줄은 A 의 다음 쪽보다 뒤일 수 있으니 아직 보이지 않는다.
    const a = { events: [ev(20, 3, 3), ev(19, 3, 1)], cursor: 'next-a' };
    const b = { events: [ev(18, 3, 2), ev(5, 2, 1)], cursor: null };
    const out = mergeFeeds([a, b]);
    expect(out.events.map((e) => e.id)).toEqual([20, 18, 19]);
    expect(out.held).toBe(1);
    expect(out.more).toBe(true);
  });

  it('경계는 더 있는 피드들 가운데 가장 새로운 끝', () => {
    const a = { events: [ev(30, 4, 1), ev(29, 3, 3)], cursor: 'a' };
    const b = { events: [ev(28, 3, 2), ev(27, 2, 2)], cursor: 'b' };
    const out = mergeFeeds([a, b]);
    expect(out.events.map((e) => e.id)).toEqual([30, 29]);
    expect(out.held).toBe(2);
  });

  it('더 있는데 빈 쪽(서버 탐색 한도)은 경계를 만들지 않고 「더 보기」만 남긴다', () => {
    const out = mergeFeeds([{ events: [], cursor: 'scan-cap' }, { events: [ev(3, 3, 1)], cursor: null }]);
    expect(out.events.map((e) => e.id)).toEqual([3]);
    expect(out.more).toBe(true);
  });
});

describe('newerThanLoaded — 새 순 알림', () => {
  it('이미 있는 줄보다 새 줄만 센다', () => {
    const feed = { events: [ev(10, 3, 2), ev(9, 3, 1)], cursor: 'c' };
    const { fresh, reset } = newerThanLoaded(feed, { events: [ev(12, 3, 3), ev(11, 3, 3), ev(10, 3, 2)], nextCursor: 'x' });
    expect(fresh.map((e) => e.id)).toEqual([12, 11]);
    expect(reset).toBe(false);
  });
  it('첫 쪽이 모두 새 줄이고 뒤가 더 있으면 사이가 비었을 수 있다 — reset', () => {
    const feed = { events: [ev(10, 3, 2)], cursor: null };
    expect(newerThanLoaded(feed, { events: [ev(13, 4, 1), ev(12, 3, 3)], nextCursor: 'more' }).reset).toBe(true);
  });
});

describe('읽기 경로', () => {
  it('비공개 분류는 /api/events?section=, 천하 정세는 /api/world-events, 커서는 before', () => {
    expect(feedPath('COURT', null)).toBe('/api/events?section=COURT&limit=50');
    expect(feedPath('WORLD', 'abc_-')).toBe('/api/world-events?before=abc_-&limit=50');
  });
  it('api.get 오류 문장에서 HTTP 상태를 읽는다', () => {
    expect(httpStatusOf(new Error('404: Not Found'))).toBe(404);
    expect(httpStatusOf(new Error('network'))).toBeNull();
  });
});
