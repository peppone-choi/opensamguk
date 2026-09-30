'use client';

import { useCallback, useSyncExternalStore } from 'react';
import type { WorldTiles } from './WorldMapCanvas';

/**
 * 구역(省) 한글 이름 — 지도 훅(useWorldMap)이 이미 받은 지형에서만 읽는다.
 * 이름 하나 보이려고 지형(수백 KB)을 새로 받지 않는다: 지도를 거치지 않은 화면은 이름을 모른다(undefined).
 * 오래 갈 길은 가벼운 구역 이름표 API다(계약판, C0).
 */
interface ProvinceNames {
  readonly byId: ReadonlyMap<string, string>;
  readonly byIndex: readonly string[];
  readonly version: string | null;
}

const NO_NAMES: ProvinceNames = { byId: new Map(), byIndex: [], version: null };
let remembered: ProvinceNames = NO_NAMES;
const listeners = new Set<() => void>();

function notify() {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** 지도 훅이 지형을 받았을 때 부른다. 같은 지형(지문)이면 다시 적지 않는다. */
export function rememberProvinceNames(tiles: Pick<WorldTiles, 'provinceRecords'>, version: string | null = null): void {
  const records = tiles.provinceRecords;
  if (!records?.length) return;
  if (version !== null && remembered.version === version && remembered.byIndex.length === records.length) return;
  remembered = {
    byId: new Map(records.map((record) => [record.id, record.displayName])),
    byIndex: records.map((record) => record.displayName),
    version,
  };
  notify();
}

/** 구역 기록 id(`provinceRecords[].id`) 또는 번호로 이름을 찾는다. 모르면 undefined — 지어내지 않는다. */
export function provinceNameOf(id: string | number | null | undefined, names: ProvinceNames = remembered): string | undefined {
  if (id == null) return undefined;
  const name = typeof id === 'number'
    ? (Number.isInteger(id) && id >= 0 ? names.byIndex[id] : undefined)
    : names.byId.get(id);
  return name || undefined;
}

/**
 * 구역 이름을 주는 함수. 지도 훅이 나중에 지형을 받으면(작전실을 거쳐 오면) 다시 그려져 이름이 채워진다.
 * 이름을 모르면 화면은 「이름 모를 구역」 같은 자리 표시를 쓴다.
 */
export function useProvinceName(): (id: string | number | null | undefined) => string | undefined {
  const names = useSyncExternalStore(subscribe, () => remembered, () => NO_NAMES);
  return useCallback((id: string | number | null | undefined) => provinceNameOf(id, names), [names]);
}

/** 테스트끼리 적어 둔 이름을 나눠 쓰지 않게 비운다. */
export function resetProvinceNames(): void {
  remembered = NO_NAMES;
  notify();
}
