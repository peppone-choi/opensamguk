'use client';

import { useEffect, useMemo, useState } from 'react';
import { UNOWNED_NATION_NAME, isOwnedNationVisual } from './nationVisual';
import { provinceNamesKnown, rememberProvinceNames } from './provinceNames';
import { parseTerrainEtagHash, type IsoCityOverlay, type WorldTiles } from './map/mapData';
import { validStrategicBinding, type StrategicTopologyBinding } from './strategicMap';
import { cityBadgesById } from './worldCityBadges';

export const WORLD_MAP_CODE = 'han-world-v3';

export interface WorldMapPreview {
  mapCode: string;
  width: number;
  height: number;
  cities: IsoCityOverlay[];
  nations: { id: number; name: string; color: string }[];
  strategicTopology?: StrategicTopologyBinding | null;
  provinceOccupancy?: { provinceRecordId: string; provinceIndex: number; nationId: number }[];
  /** Immutable topdown bake id, only when the server's bake matches the active world (game-api MapPreviewResponse). */
  topdownBakeId?: string;
  jurisdictionOwnership?: { jurisdictionId: string; nationId: number }[];
  commanderyControl?: { commanderyId: string; nationId: number }[];
}

export interface CommanderyCell {
  readonly no: number;
  readonly name: string;
  readonly col: number;
  readonly row: number;
  readonly focusCityId: number | null;
}

export interface LegendEntry {
  readonly nationId: number;
  readonly name: string;
  readonly color: string;
  readonly cities: number;
}

export type WorldMapState<P extends WorldMapPreview> =
  | { readonly kind: 'loading' }
  | { readonly kind: 'error'; readonly message: string }
  | { readonly kind: 'unsupported'; readonly mapCode: string }
  /** 지도(탑다운)가 그릴 판: 미리보기만 받는다. 지형 · 州 색인 · 省 그림은 청하지 않는다(옛 지도판은 지웠다, M2-9). */
  | {
    readonly kind: 'preview';
    readonly preview: P;
    readonly refreshError?: string;
    readonly legend: readonly LegendEntry[];
  };

export function worldTerrainUrl(baseTilesSha256: string | null, serverId?: string): string {
  const server = serverId ? `server=${encodeURIComponent(serverId)}&` : '';
  return `/api/game/api/map/terrain?${server}mapCode=${WORLD_MAP_CODE}`
    + (baseTilesSha256 ? `&baseTilesSha256=${encodeURIComponent(baseTilesSha256)}` : '');
}

export function buildWorldCities(preview: WorldMapPreview, badges = cityBadgesById(preview.cities, null, null)): IsoCityOverlay[] {
  const nations = new Map(preview.nations.map((n) => [n.id, n]));
  return preview.cities.map((city) => {
    const nation = nations.get(city.nationId);
    const owned = isOwnedNationVisual(city.nationId, nation?.color);
    return {
      ...city,
      // 지도 이름표는 縣 이름만 — 동명이지 구분 郡 은 commanderyName 으로 따로 간다.
      mapLabel: city.name,
      nationName: owned ? nation?.name : UNOWNED_NATION_NAME,
      nationColor: owned ? nation?.color : undefined,
      cityBadges: (badges.get(city.id) ?? []).filter((badge) => badge.kind !== 'event'),
      interactive: true,
    } satisfies IsoCityOverlay;
  });
}

/**
* 군국 번호(배열 자리) · 이름 · 대표 칸 목록 → 군국 표. 지도는 bake 장소 표의 군국을 넘긴다. 초점 城 은 미리보기에서
* **이름이 같은 군국의 치소**를 먼저, 없으면 그 군국의 아무 城 이나 id 가 가장 작은 것을 고른다. 城 없는 군국은 초점이 null 이다 — 지어내지 않는다.
*/
export function commanderyCells(
  juns: readonly { readonly name: string; readonly col: number; readonly row: number }[],
  preview: WorldMapPreview,
): CommanderyCell[] {
  const byCommandery = new Map<string, { seat: number | null; first: number }>();
  for (const city of [...preview.cities].sort((a, b) => a.id - b.id)) {
    const name = city.commanderyName;
    if (!name) continue;
    const entry = byCommandery.get(name) ?? { seat: null, first: city.id };
    if (city.isCommanderySeat && entry.seat == null) entry.seat = city.id;
    byCommandery.set(name, entry);
  }
  return juns.flatMap((jun, no) => {
    if (!Number.isFinite(jun.col) || !Number.isFinite(jun.row)) return [];
    const hit = byCommandery.get(jun.name);
    return [{ no, name: jun.name, col: jun.col, row: jun.row, focusCityId: hit ? hit.seat ?? hit.first : null }];
  });
}

export function buildLegend(preview: WorldMapPreview): LegendEntry[] {
  const counts = new Map<number, number>();
  for (const city of preview.cities) counts.set(city.nationId, (counts.get(city.nationId) ?? 0) + 1);
  return preview.nations
    .filter((n) => isOwnedNationVisual(n.id, n.color))
    .map((n) => ({ nationId: n.id, name: n.name, color: n.color, cities: counts.get(n.id) ?? 0 }))
    .sort((a, b) => b.cities - a.cities || a.nationId - b.nationId);
}


export interface WorldMapOptions<P extends WorldMapPreview> {
  loadPreview: (signal: AbortSignal) => Promise<P>;
  mapData?: P | null;
  refreshKey?: unknown;
  serverId?: string;
}

/**
 * 지도 미리보기를 받는다. 지형은 구역 이름 캐시(영지 · 공성 · 조정 화면의 useProvinceName)만 채우려고 뒤에서 받고,
 * 같은 지문의 이름을 이미 알면 받지 않는다 — 구역 이름표 API(K4-21)를 쓰기 전까지 남긴다(D113, #1231 교훈).
 */
export function useWorldMap<P extends WorldMapPreview>({
  loadPreview, mapData, refreshKey = 0, serverId,
}: WorldMapOptions<P>): WorldMapState<P> {
  const [raw, setRaw] = useState<
    | { kind: 'loading' }
    | { kind: 'error'; message: string }
    | { kind: 'unsupported'; mapCode: string }
    | { kind: 'preview'; preview: P; refreshError?: string }
  >({ kind: 'loading' });

  useEffect(() => {
    const controller = new AbortController();
    (async () => {
      const preview = mapData ?? await loadPreview(controller.signal);
      if (controller.signal.aborted) return;
      if (preview.mapCode !== WORLD_MAP_CODE) {
        setRaw({ kind: 'unsupported', mapCode: preview.mapCode });
        return;
      }
      setRaw({ kind: 'preview', preview: { ...preview } });
      const binding = preview.strategicTopology;
      const base = binding && validStrategicBinding(binding) ? binding.baseTilesSha256 : null;
      if (base && provinceNamesKnown(base)) return;
      // 이름만 받는다: 실패해도 지도 상태는 그대로 두고 콘솔에만 남긴다
      try {
        const response = await fetch(worldTerrainUrl(base, serverId), { signal: controller.signal });
        if (!response.ok) throw new Error(`지형을 받지 못했습니다(${response.status})`);
        const hash = parseTerrainEtagHash(response.headers.get('etag'));
        const tiles = (await response.json()) as WorldTiles;
        if (!controller.signal.aborted) rememberProvinceNames(tiles, hash ?? base);
      } catch (error) {
        if (!controller.signal.aborted) console.warn('[지도] 구역 이름', error);
      }
    })().catch((error: unknown) => {
      if (!controller.signal.aborted) {
        const message = error instanceof Error ? error.message : '지도를 불러오지 못했습니다.';
        setRaw((previous) => previous.kind === 'preview' ? { ...previous, refreshError: message } : { kind: 'error', message });
      }
    });
    return () => controller.abort();
  }, [loadPreview, mapData, refreshKey, serverId]);

  const legend = useMemo(() => (raw.kind === 'preview' ? buildLegend(raw.preview) : null), [raw]);

  return useMemo<WorldMapState<P>>(() => (raw.kind === 'preview'
    ? { kind: 'preview', preview: raw.preview, refreshError: raw.refreshError, legend: legend ?? [] }
    : raw), [raw, legend]);
}
