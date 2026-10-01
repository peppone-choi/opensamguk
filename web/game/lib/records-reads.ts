'use client';

// 기록 5분류(P-H01) 읽기 — 분류마다 피드 하나(커서 · 상태)를 두고, 탭을 바꿔도 이미 받은 것은 다시 받지 않는다.
// 서버: GET /api/events?section=&before=&limit=(비공개 네 분류, 내 장수가 있어야 한다) · GET /api/world-events(공개).
// 새 순이 끝나면(SSE turnCompleted) 받은 분류의 첫 쪽을 다시 읽어 「새 기록 n건」만 알리고, 누르면 붙인다 —
// 읽던 목록이 발밑에서 밀리지 않게 한다.

import { useCallback, useEffect, useRef, useState } from 'react';
import type { GameEvent, GameEventPage, RecordSection } from '@opensamguk/ui';
import { api } from './api';
import { appendPage, compareEventsDesc, newerThanLoaded, RECORDS_PAGE_SIZE, type FeedState } from './recordsFeed';
import { useTurnRefresh } from '@/hooks/useTurnRefresh';

export type FeedStatus = 'loading' | 'ready' | 'error' | 'denied';

export interface FeedSlot extends FeedState {
  readonly status: FeedStatus;
  /** 오류 · 막힘의 HTTP 상태(있으면). 401 = 로그인 필요, 404 = 이 서버에 내 장수 없음. */
  readonly httpStatus: number | null;
  readonly loadingMore: boolean;
  readonly moreError: boolean;
  /** 새 순 뒤 첫 쪽에서 찾은 새 줄(아직 목록에 붙이지 않음). */
  readonly fresh: readonly GameEvent[];
  /** 새 줄이 한 쪽을 다 채워 사이가 비었을 수 있다 — 붙일 때 목록을 새 첫 쪽으로 바꾼다. */
  readonly latest: GameEventPage | null;
}

const LOADING: FeedSlot = { events: [], cursor: null, status: 'loading', httpStatus: null, loadingMore: false, moreError: false, fresh: [], latest: null };

export function feedPath(section: RecordSection, before: string | null, limit = RECORDS_PAGE_SIZE): string {
  const query = new URLSearchParams();
  if (section !== 'WORLD') query.set('section', section);
  if (before) query.set('before', before);
  query.set('limit', String(limit));
  return section === 'WORLD' ? `/api/world-events?${query}` : `/api/events?${query}`;
}

/** api.get 은 「404: Not Found」 모양으로 던진다. */
export function httpStatusOf(error: unknown): number | null {
  const match = error instanceof Error ? /^(\d{3}):/.exec(error.message) : null;
  return match ? Number(match[1]) : null;
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

export interface RecordFeeds {
  readonly slots: Readonly<Partial<Record<RecordSection, FeedSlot>>>;
  readonly loadMore: (sections: readonly RecordSection[]) => void;
  readonly retry: (sections: readonly RecordSection[]) => void;
  /** 「새 기록 n건」을 목록에 붙인다. */
  readonly applyFresh: (sections: readonly RecordSection[]) => void;
}

/**
 * `sections` 가운데 아직 없는 분류만 첫 쪽을 받는다. `hasGeneral` 이 거짓이면 비공개 분류는 요청하지 않고 막힘으로 둔다.
 */
export function useRecordFeeds(sections: readonly RecordSection[], hasGeneral: boolean | null): RecordFeeds {
  const [slots, setSlots] = useState<Partial<Record<RecordSection, FeedSlot>>>({});
  const slotsRef = useRef(slots);
  slotsRef.current = slots;
  const controllers = useRef(new Set<AbortController>());

  useEffect(() => () => {
    for (const controller of controllers.current) controller.abort();
    controllers.current.clear();
  }, []);

  const request = useCallback(async (section: RecordSection, before: string | null): Promise<GameEventPage> => {
    const controller = new AbortController();
    controllers.current.add(controller);
    try {
      return await api.get<GameEventPage>(feedPath(section, before), controller.signal);
    } finally {
      controllers.current.delete(controller);
    }
  }, []);

  const patch = useCallback((section: RecordSection, update: (slot: FeedSlot) => FeedSlot) => {
    setSlots((prev) => ({ ...prev, [section]: update(prev[section] ?? LOADING) }));
  }, []);

  const loadFirst = useCallback((section: RecordSection) => {
    patch(section, () => LOADING);
    request(section, null).then(
      (page) => patch(section, () => ({ ...LOADING, ...appendPage({ events: [], cursor: null }, page), status: 'ready' })),
      (error) => {
        if (isAbort(error)) return;
        const httpStatus = httpStatusOf(error);
        const denied = section !== 'WORLD' && (httpStatus === 401 || httpStatus === 404);
        patch(section, () => ({ ...LOADING, status: denied ? 'denied' : 'error', httpStatus }));
      },
    );
  }, [patch, request]);

  const key = sections.join(',');
  useEffect(() => {
    if (hasGeneral === null) return; // 세션을 아직 모른다
    for (const section of key.split(',') as RecordSection[]) {
      if (slotsRef.current[section]) continue;
      if (section !== 'WORLD' && !hasGeneral) {
        patch(section, () => ({ ...LOADING, status: 'denied', httpStatus: 404 }));
        continue;
      }
      loadFirst(section);
    }
  }, [hasGeneral, key, loadFirst, patch]);

  const loadMore = useCallback((targets: readonly RecordSection[]) => {
    for (const section of targets) {
      const slot = slotsRef.current[section];
      if (!slot || slot.status !== 'ready' || slot.cursor === null || slot.loadingMore) continue;
      const cursor = slot.cursor;
      patch(section, (s) => ({ ...s, loadingMore: true, moreError: false }));
      request(section, cursor).then(
        (page) => patch(section, (s) => ({ ...s, ...appendPage(s, page), loadingMore: false })),
        (error) => { if (!isAbort(error)) patch(section, (s) => ({ ...s, loadingMore: false, moreError: true })); },
      );
    }
  }, [patch, request]);

  const retry = useCallback((targets: readonly RecordSection[]) => {
    for (const section of targets) {
      const slot = slotsRef.current[section];
      if (slot?.status === 'error') loadFirst(section);
    }
  }, [loadFirst]);

  const applyFresh = useCallback((targets: readonly RecordSection[]) => {
    setSlots((prev) => {
      const next = { ...prev };
      for (const section of targets) {
        const slot = prev[section];
        if (!slot || !slot.latest) continue;
        const replace = slot.events.length === 0 || (slot.fresh.length === slot.latest.events.length && slot.latest.nextCursor !== null);
        next[section] = replace
          ? { ...slot, events: slot.latest.events, cursor: slot.latest.nextCursor, fresh: [], latest: null }
          : { ...slot, events: [...slot.fresh, ...slot.events].sort(compareEventsDesc), fresh: [], latest: null };
      }
      return next;
    });
  }, []);

  useTurnRefresh(() => {
    for (const [section, slot] of Object.entries(slotsRef.current) as [RecordSection, FeedSlot][]) {
      if (slot.status !== 'ready') continue;
      request(section, null).then(
        (page) => patch(section, (s) => {
          const { fresh } = newerThanLoaded(s, page);
          return fresh.length > 0 ? { ...s, fresh, latest: page } : s;
        }),
        () => undefined, // 새 순 알림은 덤이다 — 실패해도 읽던 목록은 그대로 둔다.
      );
    }
  });

  return { slots, loadMore, retry, applyFresh };
}
