'use client';

// 기록 문장의 이름 풀이 — 화면이 이미 가진 자료만 쓴다(K5-07 이름 사전 전까지).
// - 현 · 세력: 지도 미리보기(/api/map/preview)의 지금 이름. 게이트웨이 로그인 화면(previewNames)과 같은 규칙.
// - 인물: 내 장수 이름만(세션). 다른 인물은 「어느 인물」 — 옛 삼모 장수 목록(/api/generals)으로 풀지 않는다.

import { useEffect, useMemo, useState } from 'react';
import type { EventNames } from '@opensamguk/ui';
import { api } from './api';
import type { MapPreviewResponse } from './types';

export interface RecordNames extends EventNames {
  /** 지도 미리보기를 받았거나(성공 · 실패) 끝났는지. */
  readonly ready: boolean;
}

export function namesFrom(preview: MapPreviewResponse | null, me: { readonly generalId: number | null; readonly name: string | null }): EventNames {
  const cities = new Map((preview?.cities ?? []).map((city) => [city.id, city.displayName ?? city.name] as const));
  const nations = new Map((preview?.nations ?? []).filter((nation) => nation.id > 0).map((nation) => [nation.id, nation.name] as const));
  return {
    city: (id) => cities.get(id),
    nation: (id) => nations.get(id),
    general: (id) => (me.generalId != null && id === me.generalId && me.name ? me.name : undefined),
  };
}

export function useRecordNames(generalId: number | null, name: string | null): RecordNames {
  const [preview, setPreview] = useState<MapPreviewResponse | null>(null);
  const [done, setDone] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    api.mapPreview(controller.signal).then(
      (value) => { setPreview(value); setDone(true); },
      () => setDone(true), // 이름을 못 받으면 「어느 현」 · 「어느 세력」으로 적는다 — 기록은 그대로 보인다.
    );
    return () => controller.abort();
  }, []);
  return useMemo(() => ({ ...namesFrom(preview, { generalId, name }), ready: done }), [done, generalId, name, preview]);
}
