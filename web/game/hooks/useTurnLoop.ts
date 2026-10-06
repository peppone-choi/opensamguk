'use client';

import { useCallback, useEffect, useState } from 'react';
import { isMaintenance, turnLoopView, type TurnLoopView } from '@/lib/turnLoop';
import { useTurnRefresh } from './useTurnRefresh';

/**
 * 다시 부르는 주기 — v31system REFRESH_NOTE 「기존 셸 폴링 [미정]초」. 기존 셸이 따라잡는 동안 쓰던 60초를 그대로 쓴다.
 * SSE 가 없어도 정지를 놓치지 않게 늘 부른다(K10-01e).
 */
export const TURN_LOOP_REFRESH_MS = 60_000;

/**
 * 턴 루프 공개 상태(게이트웨이 `/api/server-basic-info/<서버>`). 서버를 모르는 동안은 null — 띠를 그리지 않는다.
 * 읽기가 실패하면 UNKNOWN(「운영 상태 확인 중」)이다. 턴이 끝났다는 신호가 오면 바로 다시 읽는다.
 */
export function useTurnLoop(serverId: string | undefined): { readonly view: TurnLoopView | null; readonly maintenance: boolean; readonly recheck: () => void } {
  // 읽은 값은 그 서버에 묶는다 — 서버가 바뀌거나 사라지면 앞 서버의 띠 · 점검을 그대로 보이지 않는다.
  const [read, setRead] = useState<{ readonly serverId: string; readonly view: TurnLoopView; readonly maintenance: boolean } | null>(null);
  const [tick, setTick] = useState(0);
  const recheck = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    if (!serverId) return undefined;
    const id = serverId;
    const controller = new AbortController();
    fetch(`/api/server-basic-info/${encodeURIComponent(id)}`, { cache: 'no-store', signal: controller.signal })
      .then((res) => (res.ok ? res.json() : null))
      .then((body: unknown) => {
        const response = body as Parameters<typeof turnLoopView>[0];
        setRead({ serverId: id, view: turnLoopView(response), maintenance: isMaintenance(response) });
      })
      .catch(() => { if (!controller.signal.aborted) setRead({ serverId: id, view: turnLoopView(null), maintenance: false }); });
    return () => controller.abort();
  }, [serverId, tick]);

  useEffect(() => {
    if (!serverId) return undefined;
    const timer = window.setInterval(recheck, TURN_LOOP_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [serverId, recheck]);

  useTurnRefresh(recheck);
  const current = read !== null && read.serverId === serverId ? read : null;
  return { view: current?.view ?? null, maintenance: current?.maintenance ?? false, recheck };
}
