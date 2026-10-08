'use client';

import { useCallback, useEffect, useState } from 'react';
import { HierarchyReadError, readRetinueHierarchy, type RetinueHierarchy } from './api/retinue-hierarchy';

type Load =
  | { readonly kind: 'loading' }
  | { readonly kind: 'ready'; readonly data: RetinueHierarchy }
  | { readonly kind: 'unavailable' }
  | { readonly kind: 'error'; readonly message: string; readonly denied: boolean };

export function useRetinueHierarchy(actor: number | null, serverId: string | undefined, turnKey: string) {
  const key = `${serverId}:${actor}:${turnKey}`;
  const [result, setResult] = useState<{ key: string; state: Load } | null>(null);
  const [nonce, setNonce] = useState(0);
  const reload = useCallback(() => setNonce((n) => n + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    setResult({ key, state: { kind: 'loading' } });
    if (actor === null) return () => controller.abort();
    readRetinueHierarchy(actor, controller.signal).then((data) => {
      if (!controller.signal.aborted) setResult({ key, state: data.status === 'READY' ? { kind: 'ready', data } : { kind: 'unavailable' } });
    }).catch((error: unknown) => {
      if (controller.signal.aborted) return;
      const denied = error instanceof HierarchyReadError && (error.status === 401 || error.status === 403);
      setResult({ key, state: { kind: 'error', denied, message: error instanceof HierarchyReadError ? error.message : '조직도를 불러오지 못했습니다.' } });
    });
    return () => controller.abort();
  }, [actor, key, nonce]);
  const state: Load = result?.key === key ? result.state : { kind: 'loading' };
  return { state, reload };
}
