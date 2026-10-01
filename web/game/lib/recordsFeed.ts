// 기록 5분류(P-H01) 피드 합치기 — 순수 함수만. 화면 상태 · 요청은 components/records 쪽.
//
// 서버 정렬(EventFeedReadRepository): occurred_year · month · phase · ordinal · id 모두 내림차순, 커서는 위치일 뿐이다.
// 「전체」는 네 개 비공개 분류(/api/events?section=)와 천하 정세(/api/world-events)를 같은 순서로 합친다.
// 아직 더 있는 피드가 있으면, 그 피드의 가장 오래된 기록보다 오래된 줄은 보이지 않게 잡아 둔다 — 다음 쪽이 그 사이에
// 끼어들 수 있기 때문이다(보이던 줄 사이에 나중에 줄이 끼는 일을 막는다).

import type { GameEvent, GameEventPage, RecordSection } from '@opensamguk/ui';

export interface FeedState {
  readonly events: readonly GameEvent[];
  /** 다음 쪽 커서. null 이면 끝. */
  readonly cursor: string | null;
}

export const EMPTY_FEED: FeedState = { events: [], cursor: null };

/** 새 것이 앞(음수)이 되게 — 서버와 같은 순서. */
export function compareEventsDesc(a: GameEvent, b: GameEvent): number {
  const x = a.occurredAt;
  const y = b.occurredAt;
  return (y.year - x.year) || (y.month - x.month) || (y.phase - x.phase) || (y.ordinal - x.ordinal) || (b.id - a.id);
}

/** 한 피드에 다음 쪽을 붙인다. 같은 id 는 한 번만. */
export function appendPage(feed: FeedState, page: GameEventPage): FeedState {
  const seen = new Set(feed.events.map((event) => event.id));
  const fresh = page.events.filter((event) => !seen.has(event.id));
  return { events: [...feed.events, ...fresh], cursor: page.nextCursor };
}

/**
 * 여러 피드를 시각순으로 합친 「보여도 되는」 줄. 더 있는 피드(cursor != null)의 가장 오래된 줄보다 오래된 줄은 뺀다.
 * `held` 는 그래서 잡아 둔 줄 수 — 「더 보기」가 있어야 하는지 판단에 쓴다.
 */
export function mergeFeeds(feeds: readonly FeedState[]): { readonly events: readonly GameEvent[]; readonly held: number; readonly more: boolean } {
  const all = new Map<number, GameEvent>();
  for (const feed of feeds) for (const event of feed.events) if (!all.has(event.id)) all.set(event.id, event);
  const sorted = [...all.values()].sort(compareEventsDesc);
  let boundary: GameEvent | null = null;
  for (const feed of feeds) {
    if (feed.cursor === null || feed.events.length === 0) continue;
    const oldest = feed.events.reduce((a, b) => (compareEventsDesc(a, b) > 0 ? a : b));
    // 더 있는 피드 가운데 가장 「새로운」 끝이 경계다 — 그보다 오래된 줄은 그 피드의 다음 쪽보다 뒤일 수 있다.
    if (boundary === null || compareEventsDesc(oldest, boundary) < 0) boundary = oldest;
  }
  const shown = boundary === null ? sorted : sorted.filter((event) => compareEventsDesc(event, boundary as GameEvent) <= 0);
  const more = feeds.some((feed) => feed.cursor !== null);
  return { events: shown, held: sorted.length - shown.length, more };
}

/**
 * 새 순이 끝나 첫 쪽을 다시 받았을 때: 이미 있는 줄보다 새 줄만 센다.
 * 첫 쪽이 다 새 줄이고 뒤가 더 있으면(사이가 비었을 수 있음) `reset` — 화면은 그 피드를 새 첫 쪽으로 바꾼다.
 */
export function newerThanLoaded(feed: FeedState, firstPage: GameEventPage): { readonly fresh: readonly GameEvent[]; readonly reset: boolean } {
  const seen = new Set(feed.events.map((event) => event.id));
  const newest = feed.events[0];
  const fresh = firstPage.events.filter((event) => !seen.has(event.id) && (!newest || compareEventsDesc(event, newest) < 0));
  const reset = feed.events.length > 0 && fresh.length === firstPage.events.length && firstPage.nextCursor !== null;
  return { fresh, reset };
}

/** 「전체」가 합치는 분류. 천하 정세는 공개 경로, 나머지는 비공개 경로(내 장수가 있어야 한다). */
export const PRIVATE_SECTIONS: readonly Exclude<RecordSection, 'WORLD'>[] = ['PERSONAL', 'RETINUE_NATION', 'COURT', 'BATTLE'];

/** 한 쪽 크기 — 서버 한도 1–50, 보드 「50건씩」. */
export const RECORDS_PAGE_SIZE = 50;
