'use client';

import { useCallback, useSyncExternalStore } from 'react';
import type { WorldTiles } from './map/mapData';

/**
 * 구역(省) 한글 이름 — 지도 훅(useWorldMap)이 이미 받은 지형에서만 읽는다.
 * 이름 하나 보이려고 지형(수백 KB)을 새로 받지 않는다: 지도를 거치지 않은 화면은 이름을 모른다(undefined).
 * 오래 갈 길은 가벼운 구역 이름표 API다(계약판 K4-21).
 *
 * 지형은 서버마다 · 지문(`baseTilesSha256`)마다 다를 수 있어 이름표를 지문별로 따로 둔다.
 * 구역 기록 id 는 문자열이고 모양이 여럿이다: CHGIS 번호(`200012`) · `gc-…` · `SUB-…` · `ss-…` · `fc-…` · `KOR-…` · `DIRECT-…` · `X000`.
 * 도로 접경(road-forts `from/toProvinceId`)과 같은 집합이다.
 * 커밋된 여섯 판(2026-09-23 bb78b7c37 → 09-27 fb3ba8fc3)에서 같은 id 가 다른 곳을 가리킨 적은 없다.
 * id 가 새로 생기거나 사라지거나 읽기만 고쳐졌다(엄양현→광양현 등). 계약으로 보장된 것은 아니다.
 * 번호(순서)는 판마다 다르다. 그래서 지문을 모르면 id 로만 찾고(가장 최근에 받은 판), 번호는 지문이 맞을 때만 찾는다.
 */
interface ProvinceNames {
  readonly byId: ReadonlyMap<string, string>;
  readonly byIndex: readonly string[];
}

interface Remembered {
  readonly byVersion: ReadonlyMap<string, ProvinceNames>;
  readonly latest: ProvinceNames | null;
}

const NOTHING: Remembered = { byVersion: new Map(), latest: null };
let remembered: Remembered = NOTHING;
const listeners = new Set<() => void>();

function notify() {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** 지도 훅이 지형을 받았을 때 부른다. `version` 은 지형 지문(ETag sha256)이다. 같은 지문이면 다시 적지 않는다. */
export function rememberProvinceNames(tiles: Pick<WorldTiles, 'provinceRecords'>, version: string | null = null): void {
  const records = tiles.provinceRecords;
  if (!records?.length) return;
  const known = version === null ? undefined : remembered.byVersion.get(version);
  if (known && known.byIndex.length === records.length) {
    if (remembered.latest === known) return;
    remembered = { byVersion: remembered.byVersion, latest: known };
    notify();
    return;
  }
  const names: ProvinceNames = {
    byId: new Map(records.map((record) => [record.id, record.displayName])),
    byIndex: records.map((record) => record.displayName),
  };
  const byVersion = new Map(remembered.byVersion);
  if (version !== null) byVersion.set(version, names);
  remembered = { byVersion, latest: names };
  notify();
}

/** 이 지문(없으면 아무 판)의 구역 이름을 이미 아는가 — 이름만 받으려고 지형을 다시 청하지 않게 묻는다. */
export function provinceNamesKnown(version: string | null = null): boolean {
  return version === null ? remembered.latest !== null : remembered.byVersion.has(version);
}

/**
 * 구역 기록 id(`provinceRecords[].id`) 또는 번호로 이름을 찾는다. 모르면 undefined — 지어내지 않는다.
 * `version`(지형 지문)을 주면 그 판의 이름표만 본다. 주지 않으면 id 로만, 가장 최근에 받은 판에서 찾는다.
 */
export function provinceNameOf(
  id: string | number | null | undefined,
  version?: string | null,
  state: Remembered = remembered,
): string | undefined {
  if (id == null) return undefined;
  const names = version == null ? state.latest : state.byVersion.get(version);
  if (!names) return undefined;
  if (typeof id === 'number') {
    // 번호는 판마다 다르다 — 지문 없이 번호로 찾으면 다른 판의 그럴듯한 이름을 돌려줄 수 있다.
    if (version == null || !Number.isInteger(id) || id < 0) return undefined;
    return names.byIndex[id] || undefined;
  }
  return names.byId.get(id) || undefined;
}

/**
 * 구역 이름을 주는 함수. `version` 은 쓰는 화면이 아는 지형 지문(`strategicTopology.baseTilesSha256`)이다.
 * 지도 훅이 나중에 지형을 받으면(작전실을 거쳐 오면) 다시 그려져 이름이 채워진다.
 * 이름을 모르면 화면은 「이름 모를 구역」 같은 자리 표시를 쓴다.
 */
export function useProvinceName(version?: string | null): (id: string | number | null | undefined) => string | undefined {
  const state = useSyncExternalStore(subscribe, () => remembered, () => NOTHING);
  return useCallback((id: string | number | null | undefined) => provinceNameOf(id, version, state), [state, version]);
}

/** 테스트끼리 적어 둔 이름을 나눠 쓰지 않게 비운다. */
export function resetProvinceNames(): void {
  remembered = NOTHING;
  notify();
}
